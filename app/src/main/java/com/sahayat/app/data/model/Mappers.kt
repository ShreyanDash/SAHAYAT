package com.sahayat.app.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot

/* Manual, null-safe Firestore mapping. Missing fields stay null — never defaulted to fake values. */

internal fun Any?.asLongMs(): Long? = when (this) {
    is Timestamp -> toDate().time
    is Number -> toLong()
    is java.util.Date -> time
    else -> null
}

internal fun Any?.asInt(): Int? = (this as? Number)?.toInt()
internal fun Any?.asLong(): Long? = (this as? Number)?.toLong()
internal fun Any?.asDouble(): Double? = (this as? Number)?.toDouble()
internal fun Any?.asStr(): String? = (this as? String)?.takeIf { it.isNotBlank() }
internal fun Any?.asMap(): Map<*, *>? = this as? Map<*, *>

internal fun health(raw: Any?): ComponentHealth = when ((raw as? String)?.uppercase()) {
    "OK" -> ComponentHealth.OK
    "DEGRADED", "WEAK", "NO_NETWORK", "NO_SIM" -> ComponentHealth.DEGRADED
    "FAULT", "ERROR" -> ComponentHealth.FAULT
    "NOT_PRESENT", "ABSENT" -> ComponentHealth.NOT_PRESENT
    else -> ComponentHealth.UNKNOWN
}

fun DocumentSnapshot.toPatient(): Patient? {
    if (!exists()) return null
    val det = get("detection").asMap()
    return Patient(
        id = id,
        name = getString("name") ?: return null,
        age = get("age").asInt(),
        bloodGroup = get("bloodGroup").asStr(),
        emergencyNotes = get("emergencyNotes").asStr(),
        deviceId = get("deviceId").asStr(),
        detection = DetectionSettings(
            sensitivity = runCatching { Sensitivity.valueOf((det?.get("sensitivity") as? String) ?: "MEDIUM") }
                .getOrDefault(Sensitivity.MEDIUM),
            escalationTimeoutSec = det?.get("escalationTimeoutSec").asInt() ?: 60,
            smsFallbackEnabled = (det?.get("smsFallbackEnabled") as? Boolean) ?: true,
        ),
    )
}

fun DocumentSnapshot.toUserProfile(fallbackEmail: String?): UserProfile = UserProfile(
    uid = id,
    email = getString("email") ?: fallbackEmail,
    displayName = getString("displayName") ?: "",
    phone = get("phone").asStr(),
    activePatientId = get("activePatientId").asStr(),
)

fun DocumentSnapshot.toCaregiver(): Caregiver = Caregiver(
    id = id,
    name = getString("name") ?: "",
    phone = getString("phone") ?: "",
    relationship = getString("relationship") ?: "",
    priority = get("priority").asInt() ?: 0,
    isPrimary = getBoolean("isPrimary") ?: false,
    notifyPush = getBoolean("notifyPush") ?: true,
    notifySms = getBoolean("notifySms") ?: true,
)

fun DocumentSnapshot.toDeviceStatus(): DeviceStatus? {
    if (!exists()) return null
    val st = get("status").asMap()
    val sensors = st?.get("sensors").asMap()
    val sim = st?.get("sim").asMap()
    val lastSeen = st?.get("lastSeen").asLongMs()
    // Without a server to mark stale bands, "online" also requires a recent report from the patient's phone.
    val fresh = lastSeen != null && System.currentTimeMillis() - lastSeen < STALE_AFTER_MS
    return DeviceStatus(
        deviceId = id,
        patientId = get("patientId").asStr(),
        online = ((st?.get("online") as? Boolean) ?: false) && fresh,
        monitoring = when ((st?.get("monitoring") as? String)?.uppercase()) {
            "ACTIVE" -> MonitoringState.ACTIVE
            "IDLE" -> MonitoringState.IDLE
            "PAUSED" -> MonitoringState.PAUSED
            else -> MonitoringState.UNKNOWN
        },
        batteryPercent = st?.get("battery").asInt()?.coerceIn(0, 100),
        charging = st?.get("charging") as? Boolean,
        wifiRssi = st?.get("rssi").asInt(),
        wifiSsid = st?.get("ssid").asStr(),
        lastSeenMs = lastSeen,
        firmware = st?.get("firmware").asStr(),
        hardwareRev = get("hardwareRev").asStr(),
        picoHealth = health(sensors?.get("pico")),
        mpuHealth = health(sensors?.get("mpu6050")),
        simHealth = health(sim?.get("state")),
        simSignal = sim?.get("signal").asInt(),
        simNetwork = sim?.get("network").asStr(),
        uptimeSec = st?.get("uptimeSec").asLong(),
        freeMemoryKb = st?.get("freeMemKb").asInt(),
        syncRequestedAtMs = get("syncRequestedAt").asLongMs(),
    )
}

private fun responder(raw: Any?): Responder? {
    val m = raw.asMap() ?: return null
    return Responder(uid = m["uid"].asStr(), name = m["name"].asStr() ?: "Caregiver")
}

private fun location(raw: Any?): EventLocation? {
    val m = raw.asMap() ?: return null
    val lat = m["lat"].asDouble() ?: return null
    val lng = m["lng"].asDouble() ?: return null
    return EventLocation(lat, lng, m["accuracy"].asDouble(), m["source"].asStr(), m["capturedAt"].asLongMs())
}

fun DocumentSnapshot.toSeizureEvent(): SeizureEvent? {
    if (!exists()) return null
    val detected = get("detectedAt").asLongMs() ?: get("createdAt").asLongMs() ?: return null
    val sms = get("sms").asMap()
    return SeizureEvent(
        id = id,
        patientId = getString("patientId") ?: return null,
        patientName = get("patientName").asStr(),
        deviceId = get("deviceId").asStr(),
        alertType = get("alertType").asStr() ?: "SEIZURE_SUSPECTED",
        status = SosStatus.parse(getString("status")),
        detectedAtMs = detected,
        endedAtMs = get("endedAt").asLongMs(),
        durationSec = get("durationSec").asLong(),
        batteryPercent = get("battery").asInt(),
        location = location(get("location")),
        confidence = get("confidence").asDouble(),
        acknowledgedBy = responder(get("acknowledgedBy")),
        acknowledgedAtMs = get("acknowledgedAt").asLongMs(),
        responseTimeSec = get("responseTimeSec").asLong(),
        cancelledBy = responder(get("cancelledBy")),
        escalateAtMs = get("escalateAt").asLongMs(),
        escalatedAtMs = get("escalatedAt").asLongMs(),
        smsStatus = SmsStatus.parse(sms?.get("status") as? String),
        smsDetail = sms?.get("detail").asStr(),
        smsUpdatedAtMs = sms?.get("updatedAt").asLongMs(),
        resolvedAtMs = get("resolvedAt").asLongMs(),
        notes = get("notes").asStr(),
        // Bench/simulator alerts run through the real backend but are always labelled TEST.
        isTest = get("alertType").asStr() == TEST_ALERT_TYPE,
    )
}

const val TEST_ALERT_TYPE = "DEVICE_TEST"

/** A band not reported by the patient's phone for this long is shown as disconnected. */
const val STALE_AFTER_MS = 3 * 60_000L
