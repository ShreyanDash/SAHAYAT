# SAHAYAT — Band ↔ Phone ↔ Firestore Contract (free Spark plan)

```
Band (Pico W + MPU6050) ──Bluetooth LE──► Patient's phone ("Patient's phone" mode)
                                              ├── writes events/{id}, devices/{id}.status ──► Firestore
                                              │        caregiver phones keep a live listener ──► SOS alarm
                                              └── SMS "SAHAYAT SOS … Ref #<eventId>" ──► caregiver phones
                                                       (SAHAYAT's SMS receiver rings the alarm, no internet needed)
```

No Cloud Functions: `backend/firestore.rules` is the only server-side logic. Nothing is simulated —
battery, sensor health, signal and connection are what the band sent; SMS results are what the radio returned.

## 1. Bluetooth LE protocol (band = peripheral, phone = central)

Firmware: `firmware/pico_w/main.py` (MicroPython built-in `bluetooth`). App: `app/.../ble/BandProtocol.kt`, `BandBle.kt`.

```
SAHAYAT-BAND                                  (name in the scan response)
└── Service   7B7E0001-6B8A-4D9E-9C5B-2A4F6D8E1001   (in the advertising packet; the app's scan filter)
    ├── 7B7E0005-…  Device ID  READ           "SAH-0001"
    ├── 7B7E0002-…  Status     READ, NOTIFY   "MONITORING" | "STOPPED" | "SAHAYAT_OK" | "MPU_ERROR"
    │   └── 0x2902 CCCD
    ├── 7B7E0003-…  Alert      READ, NOTIFY   "ALERT|SAH-0001|SEIZURE|<ref>" → "…|CANCELLED|<ref>" or "…|ALERT_ACTIVE|<ref>"
    │   └── 0x2902 CCCD
    └── 7B7E0004-…  Command    READ, WRITE    "PING" | "START" | "STOP" | "STATUS"
```

* `0x2902` is the Client Characteristic Configuration Descriptor the stack adds under each NOTIFY
  characteristic. The app writes `01 00` to it to enable notifications. It is never used for scanning.
* `<ref>` = `<random boot id>-<n>`; the app also accepts the 3-field form without it.
* Text messages exceed 20 bytes, so the app requests MTU 185 (`BandProtocol.MTU`) before enabling notifications.

App connection sequence (`BandBleClient`), the band is "connected" only after all of it succeeds:
scan with a service-UUID filter → connect → discover → SAHAYAT service + 4 characteristics + both CCCDs present →
request MTU → read Device ID (must be `SAH-xxxx`, and equal to the paired id) → enable Status and Alert
notifications → read Status → read Alert → **ready** → write `PING` (expects `SAHAYAT_OK`) and `STATUS`.
Pairing (`BandProbe`) runs the same sequence once before linking the band in Firestore.

Alert handling on the phone: `SEIZURE` opens the band's 2-s cancel window. The SOS goes out on `ALERT_ACTIVE`,
or 4 s after `SEIZURE` if no follow-up arrives. `CANCELLED` stops it (and cancels the Firestore event if it was
already posted). The Alert value read right after connecting is processed only if its `<ref>` hasn't been handled.

## 2. Patient phone relay (`app/.../ble/BandLinkService.kt`)

Foreground service (`connectedDevice`), started after pairing, when the app opens and after reboot.

* Connects with auto-reconnect (2 → 30 s back-off), subscribes to Status + Alert, writes `STATUS` every 60 s.
  Home → Band link can send `START` / `STOP` / `PING`. A wrong Device ID or missing service stops the link and is shown.
* Status → `devices/{id}.status` (+ `lastSeen` server time) at most once per ~55 s, heartbeat read every 60 s.
  After 30 s without the link it writes `online: false`. Caregiver apps treat a band with no update for
  3 min as disconnected.
* Alert (event id = `<deviceId>_<ref>-<receive time base36>`, deterministic so retries and SMS agree).
  **Test mode** (switch on the Band link card) posts band alerts as `DEVICE_TEST`:
  1. Real alerts: SMS to all `notifySms` contacts first (`SmsFallback`, result from the radio).
  2. Firestore transaction creates `events/{id}` (status `ACTIVE`, `caregiverUids` copied from the patient,
     `escalateAt`, `sms` result). Offline → queued in encrypted prefs, retried when the internet returns.
  3. After `escalationTimeoutSec`, if still open → `ESCALATED`. TEST alerts (or real ones whose first SMS failed)
     then send one SMS and write the result to `sms`.
* `devices/{id}.syncRequestedAt` changed by a caregiver ("Reconnect") → writes `STATUS` to the band.
* Firestore `devices/{id}.status` holds only what the band reports: monitoring (from Status) and sensor health
  (`MPU_ERROR` → FAULT). Battery, firmware and uptime are `null` — this firmware doesn't measure them.
* If the band is unlinked from any phone, the relay stops and forgets it.

## 3. Caregiver phone (`emergency/AlertWatchService.kt`, `emergency/SosSmsReceiver.kt`)

* Foreground service (`specialUse`) with a Firestore snapshot listener:
  `events where patientId == P and caregiverUids array-contains me and status in [ALERT_RECEIVED, ACTIVE, ESCALATED]`.
  A new open event (detected < 30 min ago) starts `EmergencyAlertService` and opens the SOS screen. `ESCALATED` →
  notification. The band doc is watched for "not reporting".
* SMS containing `SAHAYAT SOS` and `Ref #<eventId>` (a `[TEST]` prefix = TEST) → same alarm. If the event is
  readable and already handled, it doesn't ring. Both paths are de-duplicated per event id.

## 4. Writes and what the rules allow

| Operation | Who | Firestore write |
|---|---|---|
| Link band | a caregiver of the patient (the patient's phone) | `devices/{id}` {patientId, relayUid = me, …} + `patients/{pid}` {deviceId, patientPhoneUid = me} |
| Unlink band | any caregiver | both set to `null` |
| Band status | the linked phone (`relayUid`) only | `devices/{id}.status`, `relaySeenAt` |
| Reconnect request | any caregiver | `devices/{id}.syncRequestedAt` |
| Post alert | a caregiver of the patient, `reporterUid = me`, `caregiverUids` must equal the patient's | `events/{id}` create, status `ACTIVE` |
| Acknowledge / Cancel / Escalate / Resolve | any caregiver on the event | status change allowed only along the state machine; `acknowledgedBy.uid` / `cancelledBy.uid` must be me |
| SMS result | the reporting phone only | `events/{id}.sms` |
| Invite | any caregiver | `invites/{CODE}` (8 chars from `A–Z 2–9`, expires ≤ 25 h); `get` only by exact code, never `list` |
| Join | anyone signed in holding a valid code | `patients/{pid}.caregiverUids` += me only, with `lastInvite = CODE` |

## 5. SOS state machine (server-authoritative)

```
ACTIVE ─┬─► ACKNOWLEDGED ─► RESOLVED
        ├─► CANCELLED
        └─► ESCALATED ─┬─► ACKNOWLEDGED ─► RESOLVED
                       └─► CANCELLED
```

`IDLE` is the app-side state when nothing is open. Transitions are Firestore transactions: if another caregiver
already handled the event, the app shows the current status instead of failing.

## 6. Firestore layout

```
users/{uid}                      displayName, email, phone, role (PATIENT|CAREGIVER), activePatientId, patientIds[]
patients/{pid}                   name, age, bloodGroup, emergencyNotes, ownerUid, caregiverUids[], deviceId,
                                 patientPhoneUid, lastInvite, detection{sensitivity, escalationTimeoutSec, smsFallbackEnabled}
patients/{pid}/caregivers/{id}   name, phone, relationship, priority, isPrimary, notifyPush, notifySms
devices/{deviceId}               patientId, ownerUid, relayUid, transport:"BLE", bandName, relaySeenAt, syncRequestedAt,
                                 status{online, lastSeen, battery, charging, rssi, firmware, uptimeSec, monitoring,
                                        sensors{pico, mpu6050}, sim{state:"NOT_PRESENT"}}
events/{eventId}                 patientId, patientName, caregiverUids[], reporterUid, deviceId, alertType, status,
                                 detectedAt, durationSec, confidence, battery, location, escalateAt,
                                 acknowledgedBy{uid,name}, acknowledgedAt, responseTimeSec, cancelledBy, cancelledAt, escalatedAt,
                                 sms{status, detail, updatedAt}, resolvedAt, notes
invites/{code}                   patientId, createdBy, createdAt, expiresAt
```

`caregiverUids` is copied onto each event so the rules can authorise reads without extra lookups.
`location` = `{lat, lng, accuracy, source, capturedAt}` from the patient phone's fused location provider at alert time
(null if permission/Location is off or no fix within 6 s), then updated by the reporting phone every 30 s for up to
15 min while the alert isn't resolved/cancelled (`locationUpdatedAt`; rules allow only the reporter to write it).
SOS texts include `https://maps.google.com/?q=lat,lng`, which the caregiver app's SMS receiver also reads.
Caregivers see it on an osmdroid (OpenStreetMap) map in the SOS / history screens and a full-screen map.
