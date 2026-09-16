/**
 * SAHAYAT backend (Firebase Cloud Functions v2).
 *
 * Alert path:  band --BLE--> patient phone (Patient mode) --callable--> backend --FCM--> caregiver phones
 * SMS backup:  backend --FCM "SEND_SMS"--> patient phone --its own SIM--> caregivers
 *
 * App callables (Firebase Auth):
 *   linkBand, unpairDevice, requestDeviceSync          – band ↔ patient association
 *   reportBandStatus, reportBandAlert, reportSmsResult – sent only by the patient's relay phone
 *   createInvite, joinPatient                          – caregivers join a patient's care circle
 *   acknowledgeEvent, cancelEvent, resolveEvent        – SOS state machine
 * Scheduled: escalateOverdueEvents, markStaleDevices
 *
 * The backend owns the SOS state machine. Clients only request transitions.
 */
import * as crypto from "crypto";
import { initializeApp } from "firebase-admin/app";
import { DocumentData, FieldValue, Timestamp, getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { setGlobalOptions } from "firebase-functions/v2";
import { HttpsError, onCall, CallableRequest } from "firebase-functions/v2/https";
import { onSchedule } from "firebase-functions/v2/scheduler";
import * as logger from "firebase-functions/logger";

initializeApp();
setGlobalOptions({ region: "asia-south1", maxInstances: 10 });

const db = getFirestore();

const DEVICE_ID = /^SAH-[A-Z0-9]{4,16}$/;
const STALE_MS = 2 * 60_000;
const INVITE_TTL_MS = 24 * 60 * 60_000;
const TEST_ALERT_TYPE = "DEVICE_TEST";

type Status = "IDLE" | "ALERT_RECEIVED" | "ACTIVE" | "ACKNOWLEDGED" | "CANCELLED" | "ESCALATED" | "RESOLVED";

/** Allowed server-side transitions. */
const TRANSITIONS: Record<string, Status[]> = {
  ALERT_RECEIVED: ["ACTIVE", "ACKNOWLEDGED", "CANCELLED", "ESCALATED"],
  ACTIVE: ["ACKNOWLEDGED", "CANCELLED", "ESCALATED"],
  ESCALATED: ["ACKNOWLEDGED", "CANCELLED"],
  ACKNOWLEDGED: ["RESOLVED"],
  CANCELLED: [],
  RESOLVED: [],
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function requireAuth(req: CallableRequest): string {
  if (!req.auth) throw new HttpsError("unauthenticated", "Sign in required");
  return req.auth.uid;
}

async function assertCaregiver(uid: string, patientId: string) {
  if (!patientId) throw new HttpsError("invalid-argument", "patientId required");
  const p = await db.doc(`patients/${patientId}`).get();
  if (!p.exists) throw new HttpsError("not-found", "Patient not found", { reason: "PATIENT_NOT_FOUND" });
  const uids: string[] = p.get("caregiverUids") ?? [];
  if (!uids.includes(uid)) throw new HttpsError("permission-denied", "Not a caregiver for this patient");
  return p;
}

/** The caller must be the phone that relays this band. */
async function assertRelay(uid: string, deviceId: string) {
  if (!DEVICE_ID.test(deviceId)) throw new HttpsError("invalid-argument", "Invalid device id", { reason: "INVALID_DEVICE_ID" });
  const dev = await db.doc(`devices/${deviceId}`).get();
  if (!dev.exists) throw new HttpsError("not-found", "Unknown band", { reason: "DEVICE_NOT_FOUND" });
  if (dev.get("relayUid") !== uid || !dev.get("patientId")) {
    throw new HttpsError("permission-denied", "This phone is not linked to the band");
  }
  return dev;
}

function clampInt(v: unknown, min: number, max: number): number | null {
  if (v === null || v === undefined || v === "") return null;
  const n = Number(v);
  return Number.isFinite(n) ? Math.min(max, Math.max(min, Math.round(n))) : null;
}

function str(v: unknown, max: number): string | null {
  return typeof v === "string" && v.trim() ? v.trim().slice(0, max) : null;
}

/** Sends a data-only, high-priority message to every token of the given users; prunes dead tokens. */
async function pushToUsers(uids: string[], data: Record<string, string>) {
  const unique = [...new Set(uids)].filter(Boolean);
  const tokenDocs = (await Promise.all(unique.map((u) => db.collection(`users/${u}/fcmTokens`).get())))
    .flatMap((q) => q.docs);
  if (tokenDocs.length === 0) {
    logger.warn("No FCM tokens registered for recipients", { recipients: unique.length, type: data.type });
    return { sent: 0, failed: 0 };
  }
  const res = await getMessaging().sendEachForMulticast({
    tokens: tokenDocs.map((d) => d.id),
    data,
    android: { priority: "high", ttl: 5 * 60_000 },
  });
  const dead: Promise<unknown>[] = [];
  res.responses.forEach((r, i) => {
    const code = r.error?.code ?? "";
    if (code.includes("registration-token-not-registered") || code.includes("invalid-argument")) {
      dead.push(tokenDocs[i].ref.delete());
    }
  });
  await Promise.all(dead);
  return { sent: res.successCount, failed: res.failureCount };
}

async function responderName(uid: string): Promise<string> {
  const u = await db.doc(`users/${uid}`).get();
  return (u.get("displayName") as string) || "Caregiver";
}

/** Simple per-user rate limit stored server-side. */
async function rateLimit(uid: string, key: string, max: number, windowMs: number) {
  const ref = db.doc(`rate_limits/${uid}_${key}`);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const now = Date.now();
    const start: number = snap.get("windowStart") ?? 0;
    const count: number = snap.get("count") ?? 0;
    if (now - start > windowMs) {
      tx.set(ref, { windowStart: now, count: 1 });
    } else if (count >= max) {
      throw new HttpsError("resource-exhausted", "Too many attempts. Try again later.");
    } else {
      tx.update(ref, { count: count + 1 });
    }
  });
}

// ---------------------------------------------------------------------------
// Band ↔ patient association (called from the patient's phone)
// ---------------------------------------------------------------------------

export const linkBand = onCall(async (req) => {
  const uid = requireAuth(req);
  const deviceId = String(req.data?.deviceId ?? "").toUpperCase();
  const patientId = String(req.data?.patientId ?? "");
  const bandName = str(req.data?.bandName, 32);
  if (!DEVICE_ID.test(deviceId)) throw new HttpsError("invalid-argument", "Invalid band id", { reason: "INVALID_DEVICE_ID" });
  await assertCaregiver(uid, patientId);

  const devRef = db.doc(`devices/${deviceId}`);
  const patientRef = db.doc(`patients/${patientId}`);
  await db.runTransaction(async (tx) => {
    const [dev, patient] = await Promise.all([tx.get(devRef), tx.get(patientRef)]);
    const current = dev.exists ? dev.get("patientId") : null;
    if (current && current !== patientId) throw new HttpsError("already-exists", "Band linked to another patient");

    const previous: string | undefined = patient.get("deviceId");
    if (previous && previous !== deviceId) {
      tx.set(db.doc(`devices/${previous}`), { patientId: null, ownerUid: null, relayUid: null }, { merge: true });
    }
    tx.set(devRef, {
      patientId,
      ownerUid: uid,
      relayUid: uid,
      bandName,
      transport: "BLE",
      pairedAt: FieldValue.serverTimestamp(),
    }, { merge: true });
    tx.update(patientRef, { deviceId, patientPhoneUid: uid });
  });
  await db.doc(`users/${uid}`).set({ role: "PATIENT" }, { merge: true });
  logger.info("Band linked", { deviceId });
  return { status: "PAIRED", deviceOnline: false };
});

export const unpairDevice = onCall(async (req) => {
  const uid = requireAuth(req);
  const deviceId = String(req.data?.deviceId ?? "");
  const patientId = String(req.data?.patientId ?? "");
  await assertCaregiver(uid, patientId);
  await db.runTransaction(async (tx) => {
    const dev = await tx.get(db.doc(`devices/${deviceId}`));
    if (dev.exists && dev.get("patientId") !== patientId) throw new HttpsError("permission-denied", "Not this patient's band");
    tx.set(db.doc(`devices/${deviceId}`), {
      patientId: null, ownerUid: null, relayUid: null, "status": { online: false },
    }, { merge: true });
    tx.update(db.doc(`patients/${patientId}`), { deviceId: FieldValue.delete(), patientPhoneUid: FieldValue.delete() });
  });
  return { status: "UNPAIRED" };
});

/** Asks the relay phone to read fresh status from the band right away. */
export const requestDeviceSync = onCall(async (req) => {
  const uid = requireAuth(req);
  const deviceId = String(req.data?.deviceId ?? "");
  const dev = await db.doc(`devices/${deviceId}`).get();
  if (!dev.exists) throw new HttpsError("not-found", "Unknown band", { reason: "DEVICE_NOT_FOUND" });
  await assertCaregiver(uid, dev.get("patientId"));
  await dev.ref.update({ syncRequestedAt: FieldValue.serverTimestamp() });
  const relay: string | undefined = dev.get("relayUid");
  if (relay) await pushToUsers([relay], { type: "BAND_SYNC", deviceId });
  return { status: "REQUESTED" };
});

// ---------------------------------------------------------------------------
// Reports from the relay phone
// ---------------------------------------------------------------------------

/**
 * data: { deviceId, connected, battery?, charging?, monitoring?, mpu?, firmware?, uptimeSec?, rssi? }
 * `connected=false` means the phone lost the Bluetooth link to the band.
 */
export const reportBandStatus = onCall(async (req) => {
  const uid = requireAuth(req);
  const deviceId = String(req.data?.deviceId ?? "").toUpperCase();
  const dev = await assertRelay(uid, deviceId);
  const d = req.data ?? {};
  const connected = d.connected === true;
  const wasOnline = dev.get("status.online") === true;

  if (connected) {
    await dev.ref.set({
      relaySeenAt: FieldValue.serverTimestamp(),
      status: {
        online: true,
        lastSeen: FieldValue.serverTimestamp(),
        battery: clampInt(d.battery, 0, 100),
        charging: typeof d.charging === "boolean" ? d.charging : null,
        rssi: clampInt(d.rssi, -120, 0),
        ssid: null,
        firmware: str(d.firmware, 16),
        uptimeSec: clampInt(d.uptimeSec, 0, 10 ** 9),
        freeMemKb: null,
        monitoring: ["ACTIVE", "IDLE", "PAUSED"].includes(d.monitoring) ? d.monitoring : null,
        sensors: {
          pico: "OK",
          mpu6050: ["OK", "FAULT"].includes(d.mpu) ? d.mpu : null,
        },
        sim: { state: "NOT_PRESENT", signal: null, network: null },
      },
    }, { merge: true });
  } else {
    await dev.ref.set({ relaySeenAt: FieldValue.serverTimestamp(), status: { online: false } }, { merge: true });
    if (wasOnline) {
      const p = await db.doc(`patients/${dev.get("patientId")}`).get();
      const recipients = (p.get("caregiverUids") ?? []).filter((u: string) => u !== uid);
      await pushToUsers(recipients, {
        type: "DEVICE_STATUS",
        deviceId,
        patientId: String(dev.get("patientId")),
        eventId: `dev_${deviceId}`,
        title: "SAHAYAT band disconnected",
        body: "The band lost its Bluetooth link to the patient's phone. Alerts can't be raised until it reconnects.",
      });
    }
  }
  return { ok: true };
});

/**
 * data: { deviceId, alertId, detectedAt (ms), durationSec?, confidence? (0..1), battery?, test?,
 *         location?: {lat,lng,accuracy,source}, sms?: {status, detail} }
 * alertId makes retries idempotent (the phone retries when the network was down).
 */
export const reportBandAlert = onCall(async (req) => {
  const uid = requireAuth(req);
  const d = req.data ?? {};
  const deviceId = String(d.deviceId ?? "").toUpperCase();
  const dev = await assertRelay(uid, deviceId);
  const alertId = String(d.alertId ?? "");
  if (!/^[A-Za-z0-9_-]{2,40}$/.test(alertId)) throw new HttpsError("invalid-argument", "alertId required");

  const patientId: string = dev.get("patientId");
  const patient = await db.doc(`patients/${patientId}`).get();
  const caregiverUids: string[] = patient.get("caregiverUids") ?? [];
  const timeoutSec = clampInt(patient.get("detection.escalationTimeoutSec"), 15, 600) ?? 60;
  const isTest = d.test === true;
  const alertType = isTest ? TEST_ALERT_TYPE : "SEIZURE_SUSPECTED";

  const eventId = `${deviceId}_${alertId}`;
  const ref = db.doc(`events/${eventId}`);
  const detectedMs = Number(d.detectedAt) > 0 ? Math.min(Number(d.detectedAt), Date.now()) : Date.now();
  const loc = d.location;
  const location = loc && Number.isFinite(loc.lat) && Number.isFinite(loc.lng)
    ? { lat: loc.lat, lng: loc.lng, accuracy: Number.isFinite(loc.accuracy) ? loc.accuracy : null, source: str(loc.source, 24) ?? "phone" }
    : null;
  const smsStatus = ["SENT", "FAILED", "UNAVAILABLE"].includes(d.sms?.status) ? d.sms.status : null;

  const created = await db.runTransaction(async (tx) => {
    const existing = await tx.get(ref);
    if (existing.exists) return false;
    tx.set(ref, {
      patientId,
      patientName: patient.get("name") ?? null,
      caregiverUids,
      reporterUid: uid,
      deviceId,
      alertType,
      status: "ACTIVE",
      detectedAt: Timestamp.fromMillis(detectedMs),
      createdAt: FieldValue.serverTimestamp(),
      durationSec: clampInt(d.durationSec, 0, 3600),
      confidence: Number.isFinite(d.confidence) ? Math.min(1, Math.max(0, d.confidence)) : null,
      battery: clampInt(d.battery, 0, 100),
      location,
      escalateAt: Timestamp.fromMillis(Date.now() + timeoutSec * 1000),
      sms: smsStatus
        ? { status: smsStatus, detail: str(d.sms?.detail, 120), updatedAt: FieldValue.serverTimestamp() }
        : { status: "NOT_REQUIRED" },
    });
    return true;
  });

  let push = { sent: 0, failed: 0 };
  if (created) {
    // Everyone in the care circle except the phone that reported it.
    push = await pushToUsers(caregiverUids.filter((u) => u !== uid), {
      type: "SOS_ALERT",
      eventId,
      patientId,
      patientName: String(patient.get("name") ?? ""),
      deviceId,
      timestamp: String(detectedMs),
      battery: d.battery != null ? String(clampInt(d.battery, 0, 100)) : "",
      location: location ? JSON.stringify(location) : "",
      alertType,
      status: "ACTIVE",
    });
    await ref.update({ push: { ...push, at: FieldValue.serverTimestamp() } });
  } else {
    const ev = await ref.get();
    push = { sent: ev.get("push.sent") ?? 0, failed: ev.get("push.failed") ?? 0 };
  }
  return { eventId, created, pushDelivered: push.sent > 0, recipients: push.sent };
});

/** data: { eventId, status: SENDING|SENT|FAILED|UNAVAILABLE, detail? } — from the patient phone's SIM. */
export const reportSmsResult = onCall(async (req) => {
  const uid = requireAuth(req);
  const status = String(req.data?.status ?? "");
  if (!["SENDING", "SENT", "FAILED", "UNAVAILABLE"].includes(status)) throw new HttpsError("invalid-argument", "Bad status");
  const ref = db.doc(`events/${String(req.data?.eventId ?? "")}`);
  const ev = await ref.get();
  if (!ev.exists) throw new HttpsError("not-found", "Event not found", { reason: "EVENT_NOT_FOUND" });
  const patient = await db.doc(`patients/${ev.get("patientId")}`).get();
  if (patient.get("patientPhoneUid") !== uid) throw new HttpsError("permission-denied", "Not the patient's phone");
  await ref.update({
    sms: { status, detail: str(req.data?.detail, 120), updatedAt: FieldValue.serverTimestamp() },
  });
  return { ok: true };
});

// ---------------------------------------------------------------------------
// Care circle
// ---------------------------------------------------------------------------

export const createInvite = onCall(async (req) => {
  const uid = requireAuth(req);
  const patientId = String(req.data?.patientId ?? "");
  await assertCaregiver(uid, patientId);
  await rateLimit(uid, "invite", 20, 60 * 60_000);
  for (let attempt = 0; attempt < 5; attempt++) {
    const code = String(crypto.randomInt(0, 1_000_000)).padStart(6, "0");
    const ref = db.doc(`invites/${code}`);
    const ok = await db.runTransaction(async (tx) => {
      const snap = await tx.get(ref);
      if (snap.exists && (snap.get("expiresAt") as Timestamp).toMillis() > Date.now()) return false;
      tx.set(ref, {
        patientId,
        createdBy: uid,
        createdAt: FieldValue.serverTimestamp(),
        expiresAt: Timestamp.fromMillis(Date.now() + INVITE_TTL_MS),
      });
      return true;
    });
    if (ok) return { code, expiresAt: Date.now() + INVITE_TTL_MS };
  }
  throw new HttpsError("unavailable", "Could not create an invite code, try again.");
});

export const joinPatient = onCall(async (req) => {
  const uid = requireAuth(req);
  const code = String(req.data?.code ?? "");
  if (!/^[0-9]{6}$/.test(code)) throw new HttpsError("invalid-argument", "Invalid code", { reason: "INVALID_INVITE" });
  await rateLimit(uid, "join", 10, 60 * 60_000);
  const invite = await db.doc(`invites/${code}`).get();
  if (!invite.exists || (invite.get("expiresAt") as Timestamp).toMillis() < Date.now()) {
    throw new HttpsError("not-found", "Invite not found or expired", { reason: "INVALID_INVITE" });
  }
  const patientId: string = invite.get("patientId");
  const patientRef = db.doc(`patients/${patientId}`);
  const patient = await patientRef.get();
  if (!patient.exists) throw new HttpsError("not-found", "Patient not found", { reason: "INVALID_INVITE" });
  await patientRef.update({ caregiverUids: FieldValue.arrayUnion(uid) });
  await db.doc(`users/${uid}`).set({
    activePatientId: patientId,
    patientIds: FieldValue.arrayUnion(patientId),
    role: "CAREGIVER",
  }, { merge: true });
  logger.info("Caregiver joined", { patientId });
  return { patientId, patientName: patient.get("name") ?? null };
});

// ---------------------------------------------------------------------------
// SOS state machine
// ---------------------------------------------------------------------------

async function transition(req: CallableRequest, to: Status, extra: (uid: string, name: string, data: DocumentData) => object) {
  const uid = requireAuth(req);
  const eventId = String(req.data?.eventId ?? "");
  if (!eventId) throw new HttpsError("invalid-argument", "eventId required");
  const ref = db.doc(`events/${eventId}`);
  const name = await responderName(uid);

  const result = await db.runTransaction(async (tx) => {
    const ev = await tx.get(ref);
    if (!ev.exists) throw new HttpsError("not-found", "Event not found", { reason: "EVENT_NOT_FOUND" });
    const data = ev.data()!;
    if (!(data.caregiverUids ?? []).includes(uid)) throw new HttpsError("permission-denied", "Not a caregiver");
    const from = data.status as Status;
    if (from === to) return { status: from, changed: false, data };
    if (!(TRANSITIONS[from] ?? []).includes(to)) {
      // Someone else already handled it: report the current state instead of failing.
      return { status: from, changed: false, data };
    }
    tx.update(ref, { status: to, updatedAt: FieldValue.serverTimestamp(), ...extra(uid, name, data) });
    return { status: to, changed: true, data };
  });

  if (result.changed) {
    await pushToUsers(result.data.caregiverUids ?? [], {
      type: "SOS_STATUS",
      eventId,
      patientId: String(result.data.patientId),
      status: result.status,
      alertType: String(result.data.alertType ?? ""),
    });
  }
  return { status: result.status };
}

export const acknowledgeEvent = onCall((req) =>
  transition(req, "ACKNOWLEDGED", (uid, name, data) => {
    const detected: Timestamp | undefined = data.detectedAt;
    return {
      acknowledgedBy: { uid, name },
      acknowledgedAt: FieldValue.serverTimestamp(),
      responseTimeSec: detected ? Math.max(0, Math.round((Date.now() - detected.toMillis()) / 1000)) : null,
    };
  }),
);

export const cancelEvent = onCall((req) =>
  transition(req, "CANCELLED", (uid, name) => ({
    cancelledBy: { uid, name },
    cancelReason: String(req.data?.reason ?? "FALSE_ALARM").slice(0, 40),
    resolvedAt: FieldValue.serverTimestamp(),
  })),
);

export const resolveEvent = onCall((req) =>
  transition(req, "RESOLVED", () => ({
    resolvedAt: FieldValue.serverTimestamp(),
    notes: req.data?.notes ? String(req.data.notes).slice(0, 500) : null,
  })),
);

// ---------------------------------------------------------------------------
// Scheduled jobs
// ---------------------------------------------------------------------------

/** No acknowledgement in time → ESCALATED, re-alert everyone, ask the patient phone to send SMS. */
export const escalateOverdueEvents = onSchedule("every 1 minutes", async () => {
  const now = Timestamp.now();
  const overdue = await db.collection("events")
    .where("status", "in", ["ALERT_RECEIVED", "ACTIVE"])
    .where("escalateAt", "<=", now)
    .limit(50)
    .get();

  for (const doc of overdue.docs) {
    const data = doc.data();
    const patient = await db.doc(`patients/${data.patientId}`).get();
    const smsEnabled = patient.get("detection.smsFallbackEnabled") !== false;
    const phoneUid: string | undefined = patient.get("patientPhoneUid");
    const contacts = await db.collection(`patients/${data.patientId}/caregivers`)
      .where("notifySms", "==", true).orderBy("priority").limit(5).get();
    const numbers = contacts.docs.map((c) => c.get("phone")).filter(Boolean);
    const alreadySent = data.sms?.status === "SENT";
    const canSms = smsEnabled && numbers.length > 0 && !!phoneUid && !alreadySent;

    const escalated = await db.runTransaction(async (tx) => {
      const fresh = await tx.get(doc.ref);
      if (!["ALERT_RECEIVED", "ACTIVE"].includes(fresh.get("status"))) return false;
      const update: DocumentData = { status: "ESCALATED", escalatedAt: FieldValue.serverTimestamp() };
      if (!alreadySent) {
        update.sms = canSms
          ? { status: "REQUESTED", updatedAt: FieldValue.serverTimestamp() }
          : {
            status: "UNAVAILABLE",
            detail: !smsEnabled ? "SMS fallback disabled" : numbers.length === 0 ? "No SMS contacts" : "No patient phone linked",
            updatedAt: FieldValue.serverTimestamp(),
          };
      }
      tx.update(doc.ref, update);
      return true;
    });
    if (!escalated) continue;

    await pushToUsers(data.caregiverUids ?? [], {
      type: "SOS_STATUS",
      eventId: doc.id,
      patientId: String(data.patientId),
      status: "ESCALATED",
      alertType: String(data.alertType ?? ""),
    });
    if (canSms && phoneUid) {
      const prefix = data.alertType === TEST_ALERT_TYPE ? "[TEST] " : "";
      await pushToUsers([phoneUid], {
        type: "SEND_SMS",
        eventId: doc.id,
        patientId: String(data.patientId),
        numbers: JSON.stringify(numbers),
        text: `${prefix}SAHAYAT ALERT: possible seizure for ${data.patientName ?? "the patient"}. No one has responded in the app. Please check on them now.`,
      });
    }
  }
});

/** A band whose relay phone stopped reporting is shown as disconnected, and caregivers are told. */
export const markStaleDevices = onSchedule("every 2 minutes", async () => {
  const cutoff = Timestamp.fromMillis(Date.now() - STALE_MS);
  const stale = await db.collection("devices")
    .where("status.online", "==", true)
    .where("status.lastSeen", "<", cutoff)
    .limit(100)
    .get();
  for (const d of stale.docs) {
    await d.ref.update({ "status.online": false });
    const patientId = d.get("patientId");
    if (!patientId) continue;
    const p = await db.doc(`patients/${patientId}`).get();
    await pushToUsers(p.get("caregiverUids") ?? [], {
      type: "DEVICE_STATUS",
      deviceId: d.id,
      patientId,
      eventId: `dev_${d.id}`,
      title: "SAHAYAT band not reporting",
      body: "No update from the band or the patient's phone for over 2 minutes. Alerts may not get through.",
    });
  }
});
