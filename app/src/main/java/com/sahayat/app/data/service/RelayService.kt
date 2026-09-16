package com.sahayat.app.data.service

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.sahayat.app.ble.BandStatus
import com.sahayat.app.ble.BandStatusKind
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Backend
import com.sahayat.app.core.ConnectivityMonitor
import com.sahayat.app.core.Outcome
import com.sahayat.app.core.guard
import com.sahayat.app.data.model.EventLocation
import com.sahayat.app.data.model.TEST_ALERT_TYPE
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.Date

/** An alert from the band, as it is written (and retried) to Firestore. */
data class RelayAlert(
    val alertId: String,
    val detectedAtMs: Long,
    val durationSec: Int?,
    val confidence: Double?,
    val battery: Int?,
    val test: Boolean,
    val smsStatus: String? = null,
    val smsDetail: String? = null,
    /** The patient phone's position when the alert happened (null = not available). */
    val location: EventLocation? = null,
)

fun EventLocation.toFirestore(): Map<String, Any?> = mapOf(
    "lat" to lat,
    "lng" to lng,
    "accuracy" to accuracyM,
    "source" to (source ?: "phone"),
    "capturedAt" to capturedAtMs?.let { Timestamp(Date(it)) },
)

/** [recipients] = caregiver accounts (other than this phone) that can now see the alert. */
data class RelayResult(val eventId: String, val created: Boolean, val recipients: Int)

data class Invite(val code: String, val expiresAtMs: Long)

data class JoinedPatient(val patientId: String, val patientName: String?)

/**
 * Everything the patient's relay phone and the care circle write — done directly in Firestore
 * (free Spark plan, no Cloud Functions). firestore.rules enforces who may write what.
 */
class RelayService(
    private val backend: Backend,
    private val connectivity: ConnectivityMonitor,
) {
    private val db get() = backend.firestore
    private val uid get() = backend.auth?.currentUser?.uid

    private suspend fun <T> run(block: suspend () -> T): Outcome<T> {
        if (db == null) return Outcome.Failure(AppError.BackendNotConfigured)
        if (uid == null) return Outcome.Failure(AppError.NotAuthenticated)
        if (!connectivity.isOnline.first()) return Outcome.Failure(AppError.NoInternet)
        return guard(20_000) { block() }
    }

    // ------------------------------------------------------------------ band link

    suspend fun linkBand(patientId: String, deviceId: String, bandName: String): Outcome<Unit> {
        if (!DEVICE_ID.matches(deviceId)) return Outcome.Failure(AppError.InvalidDeviceId)
        val result: Outcome<Unit> = run<Unit> {
            val fs = db!!
            val me = uid!!
            val devRef = fs.collection("devices").document(deviceId)
            val patientRef = fs.collection("patients").document(patientId)
            val conflict: Boolean = fs.runTransaction<Boolean> { tx ->
                val dev = tx.get(devRef)
                val patient = tx.get(patientRef)
                val current = if (dev.exists()) dev.getString("patientId") else null
                if (current != null && current != patientId) return@runTransaction true
                val previous = patient.getString("deviceId")
                if (previous != null && previous != deviceId) {
                    tx.set(
                        fs.collection("devices").document(previous),
                        mapOf("patientId" to null, "ownerUid" to null, "relayUid" to null),
                        SetOptions.merge(),
                    )
                }
                tx.set(
                    devRef,
                    mapOf(
                        "patientId" to patientId,
                        "ownerUid" to me,
                        "relayUid" to me,
                        "bandName" to bandName.take(32),
                        "transport" to "BLE",
                        "pairedAt" to FieldValue.serverTimestamp(),
                    ),
                    SetOptions.merge(),
                )
                tx.update(patientRef, mapOf("deviceId" to deviceId, "patientPhoneUid" to me))
                false
            }.await()
            if (conflict) throw LinkConflict()
            fs.collection("users").document(me).set(mapOf("role" to AppRole.PATIENT.name), SetOptions.merge()).await()
            Unit
        }
        // The band is already linked to a different patient.
        if (result is Outcome.Failure) {
            val error = result.error
            if (error is AppError.Unknown && error.detail == "LinkConflict") {
                return Outcome.Failure(AppError.DeviceAlreadyLinked)
            }
            if (error == AppError.PermissionDenied) {
                return Outcome.Failure(AppError.BandLinkDenied)
            }
        }
        return result
    }

    private class LinkConflict : Exception()

    suspend fun unlinkBand(patientId: String, deviceId: String): Outcome<Unit> = run<Unit> {
        val fs = db!!
        val batch = fs.batch()
        batch.set(
            fs.collection("devices").document(deviceId),
            mapOf("patientId" to null, "ownerUid" to null, "relayUid" to null, "status" to mapOf("online" to false)),
            SetOptions.merge(),
        )
        batch.update(fs.collection("patients").document(patientId), mapOf("deviceId" to null, "patientPhoneUid" to null))
        batch.commit().await()
        Unit
    }

    suspend fun reportStatus(deviceId: String, connected: Boolean, status: BandStatus?, rssi: Int?): Outcome<Unit> = run<Unit> {
        // Only what the band actually reports. The current firmware sends no battery, firmware or uptime,
        // so those are written as null and every screen shows "Unavailable".
        val st: Map<String, Any?> = if (connected) {
            val monitoring = when (status?.kind) {
                BandStatusKind.MONITORING -> "ACTIVE"
                BandStatusKind.STOPPED -> "PAUSED"
                else -> null
            }
            val mpu = when (status?.kind) {
                BandStatusKind.SENSOR_ERROR -> "FAULT"
                BandStatusKind.MONITORING, BandStatusKind.STOPPED -> "OK"
                else -> null
            }
            buildMap {
                put("online", true)
                put("lastSeen", FieldValue.serverTimestamp())
                put("battery", null)
                put("charging", null)
                put("rssi", rssi)
                put("firmware", null)
                put("uptimeSec", null)
                if (monitoring != null) put("monitoring", monitoring)
                put("sensors", buildMap { put("pico", "OK"); if (mpu != null) put("mpu6050", mpu) })
                put("sim", mapOf("state" to "NOT_PRESENT"))
            }
        } else {
            mapOf("online" to false)
        }
        db!!.collection("devices").document(deviceId)
            .set(mapOf("status" to st, "relaySeenAt" to FieldValue.serverTimestamp()), SetOptions.merge())
            .await()
        Unit
    }

    // ------------------------------------------------------------------ alerts

    /** Event ids are deterministic so SMS sent while offline can already name the event. */
    fun eventIdFor(deviceId: String, alertId: String) = "${deviceId}_$alertId"

    suspend fun reportAlert(deviceId: String, patientId: String, a: RelayAlert, escalationSec: Int): Outcome<RelayResult> = run {
        val fs = db!!
        val me = uid!!
        val eventId = eventIdFor(deviceId, a.alertId)
        val ref = fs.collection("events").document(eventId)
        val patient = fs.collection("patients").document(patientId).get().await()
        @Suppress("UNCHECKED_CAST")
        val caregiverUids = (patient.get("caregiverUids") as? List<String>).orEmpty()
        val created = fs.runTransaction { tx ->
            if (tx.get(ref).exists()) return@runTransaction false
            val now = System.currentTimeMillis()
            tx.set(
                ref,
                mapOf(
                    "patientId" to patientId,
                    "patientName" to patient.getString("name"),
                    "caregiverUids" to caregiverUids,
                    "reporterUid" to me,
                    "deviceId" to deviceId,
                    "alertType" to if (a.test) TEST_ALERT_TYPE else "SEIZURE_SUSPECTED",
                    "status" to "ACTIVE",
                    "detectedAt" to Timestamp(Date(a.detectedAtMs.coerceAtMost(now))),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "durationSec" to a.durationSec,
                    "confidence" to a.confidence,
                    "battery" to a.battery,
                    "location" to a.location?.toFirestore(),
                    "escalateAt" to Timestamp(Date(now + escalationSec * 1000L)),
                    "sms" to if (a.smsStatus != null) {
                        mapOf("status" to a.smsStatus, "detail" to a.smsDetail, "updatedAt" to FieldValue.serverTimestamp())
                    } else {
                        mapOf("status" to "NOT_REQUIRED")
                    },
                ),
            )
            true
        }.await()
        RelayResult(eventId, created, caregiverUids.count { it != me })
    }

    /** Live position while an alert is open (only the reporting phone may write it — see firestore.rules). */
    suspend fun updateLocation(eventId: String, location: EventLocation): Outcome<Unit> = run<Unit> {
        db!!.collection("events").document(eventId).update(
            mapOf("location" to location.toFirestore(), "locationUpdatedAt" to FieldValue.serverTimestamp()),
        ).await()
        Unit
    }

    /** Marks an unanswered alert ESCALATED. Returns true if this call changed it. */
    suspend fun escalateIfOpen(eventId: String): Outcome<Boolean> = run {
        val fs = db!!
        val ref = fs.collection("events").document(eventId)
        fs.runTransaction { tx ->
            val status = tx.get(ref).getString("status")
            if (status != "ACTIVE" && status != "ALERT_RECEIVED") return@runTransaction false
            tx.update(ref, mapOf("status" to "ESCALATED", "escalatedAt" to FieldValue.serverTimestamp()))
            true
        }.await()
    }

    suspend fun eventStatus(eventId: String): Outcome<String?> = run {
        db!!.collection("events").document(eventId).get().await().getString("status")
    }

    suspend fun reportSms(eventId: String, status: String, detail: String?): Outcome<Unit> = run<Unit> {
        db!!.collection("events").document(eventId).update(
            "sms", mapOf("status" to status, "detail" to detail?.take(120), "updatedAt" to FieldValue.serverTimestamp()),
        ).await()
        Unit
    }

    // ------------------------------------------------------------------ care circle

    suspend fun createInvite(patientId: String): Outcome<Invite> = run {
        val fs = db!!
        val me = uid!!
        var result: Invite? = null
        for (attempt in 0 until 5) {
            val code = randomCode()
            val exp = System.currentTimeMillis() + INVITE_TTL_MS
            val ok = runCatching {
                fs.collection("invites").document(code).set(
                    mapOf(
                        "patientId" to patientId,
                        "createdBy" to me,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "expiresAt" to Timestamp(Date(exp)),
                    ),
                ).await()
            }.isSuccess
            if (ok) {
                result = Invite(code, exp)
                break
            }
        }
        result ?: throw IllegalStateException("Could not create invite")
    }

    suspend fun joinPatient(code: String): Outcome<JoinedPatient> {
        val clean = normalizeCode(code)
        if (clean.length != CODE_LEN) return Outcome.Failure(AppError.InvalidInvite)
        val r = run {
            val fs = db!!
            val me = uid!!
            val invite = runCatching { fs.collection("invites").document(clean).get().await() }.getOrNull()
            val pid = invite?.getString("patientId")
            val exp = invite?.getTimestamp("expiresAt")?.toDate()?.time ?: 0L
            if (pid == null || exp < System.currentTimeMillis()) throw InvalidInvite()
            fs.collection("patients").document(pid).update(
                mapOf("caregiverUids" to FieldValue.arrayUnion(me), "lastInvite" to clean),
            ).await()
            fs.collection("users").document(me).set(
                mapOf(
                    "activePatientId" to pid,
                    "patientIds" to FieldValue.arrayUnion(pid),
                    "role" to AppRole.CAREGIVER.name,
                ),
                SetOptions.merge(),
            ).await()
            val name = runCatching { fs.collection("patients").document(pid).get().await().getString("name") }.getOrNull()
            JoinedPatient(pid, name)
        }
        return if (r is Outcome.Failure && (r.error as? AppError.Unknown)?.detail == "InvalidInvite") {
            Outcome.Failure(AppError.InvalidInvite)
        } else r
    }

    private class InvalidInvite : Exception()

    companion object {
        private val DEVICE_ID = Regex("^SAH-[A-Z0-9]{4,16}$")
        private const val INVITE_TTL_MS = 24 * 60 * 60_000L
        const val CODE_LEN = 8
        // No 0/O/1/I to avoid typos.
        private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val random = SecureRandom()

        fun randomCode(): String = String(CharArray(CODE_LEN) { ALPHABET[random.nextInt(ALPHABET.length)] })

        fun normalizeCode(raw: String): String = raw.uppercase().filter { it in ALPHABET }.take(CODE_LEN)

        fun formatCode(code: String): String = if (code.length == CODE_LEN) code.chunked(4).joinToString("-") else code
    }
}
