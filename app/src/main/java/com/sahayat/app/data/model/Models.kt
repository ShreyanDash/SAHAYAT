package com.sahayat.app.data.model

/** Signed-in caregiver account (Firestore: users/{uid}). */
data class UserProfile(
    val uid: String,
    val email: String?,
    val displayName: String,
    val phone: String?,
    val activePatientId: String?,
)

/** The person wearing the SAHAYAT band (Firestore: patients/{patientId}). */
data class Patient(
    val id: String,
    val name: String,
    val age: Int?,
    val bloodGroup: String?,
    val emergencyNotes: String?,
    val deviceId: String?,
    val detection: DetectionSettings,
)

enum class Sensitivity { LOW, MEDIUM, HIGH }

data class DetectionSettings(
    val sensitivity: Sensitivity = Sensitivity.MEDIUM,
    /** Seconds to wait for a caregiver acknowledgement before escalating. */
    val escalationTimeoutSec: Int = 60,
    val smsFallbackEnabled: Boolean = true,
)

/** Caregiver contact (Firestore: patients/{patientId}/caregivers/{id}). */
data class Caregiver(
    val id: String,
    val name: String,
    val phone: String,
    val relationship: String,
    val priority: Int,
    val isPrimary: Boolean,
    val notifyPush: Boolean,
    val notifySms: Boolean,
)

enum class MonitoringState { ACTIVE, IDLE, PAUSED, UNKNOWN }

enum class ComponentHealth { OK, DEGRADED, FAULT, NOT_PRESENT, UNKNOWN }

/**
 * Status exactly as last reported by the wearable (Firestore: devices/{deviceId}).
 * Every field is nullable: `null` means the device has not reported it, and the UI
 * must show "Unavailable" rather than inventing a value.
 */
data class DeviceStatus(
    val deviceId: String,
    val patientId: String?,
    val online: Boolean,
    val monitoring: MonitoringState,
    val batteryPercent: Int?,
    val charging: Boolean?,
    val wifiRssi: Int?,
    val wifiSsid: String?,
    val lastSeenMs: Long?,
    val firmware: String?,
    val hardwareRev: String?,
    val picoHealth: ComponentHealth,
    val mpuHealth: ComponentHealth,
    val simHealth: ComponentHealth,
    val simSignal: Int?,
    val simNetwork: String?,
    val uptimeSec: Long?,
    val freeMemoryKb: Int?,
    val syncRequestedAtMs: Long?,
) {
    /**
     * The backend marks `online` from heartbeats; we additionally treat a stale heartbeat as
     * disconnected so a device that silently died never looks connected.
     */
    fun isConnected(now: Long = System.currentTimeMillis()): Boolean =
        online && lastSeenMs != null && now - lastSeenMs <= STALE_AFTER_MS

    companion object {
        const val STALE_AFTER_MS = 2 * 60_000L
    }
}

/** SOS lifecycle. Server-authoritative; the app only requests transitions. */
enum class SosStatus {
    IDLE, ALERT_RECEIVED, ACTIVE, ACKNOWLEDGED, CANCELLED, ESCALATED, RESOLVED;

    val isOpen: Boolean get() = this == ALERT_RECEIVED || this == ACTIVE || this == ESCALATED
    val isClosed: Boolean get() = this == CANCELLED || this == RESOLVED

    companion object {
        fun parse(raw: String?): SosStatus =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: ALERT_RECEIVED
    }
}

/** SMS fallback state as confirmed by the device/backend. Never set optimistically. */
enum class SmsStatus { NOT_REQUIRED, REQUESTED, SENDING, SENT, FAILED, UNAVAILABLE, UNKNOWN;
    companion object {
        fun parse(raw: String?): SmsStatus = entries.firstOrNull { it.name.equals(raw, true) }
            ?: if (raw == null) NOT_REQUIRED else UNKNOWN
    }
}

data class EventLocation(
    val lat: Double,
    val lng: Double,
    val accuracyM: Double?,
    val source: String?,
    /** When the phone got this fix (null if unknown). */
    val capturedAtMs: Long? = null,
)

data class Responder(val uid: String?, val name: String)

/** Seizure alert event (Firestore: events/{eventId}). */
data class SeizureEvent(
    val id: String,
    val patientId: String,
    val patientName: String?,
    val deviceId: String?,
    val alertType: String,
    val status: SosStatus,
    val detectedAtMs: Long,
    val endedAtMs: Long?,
    val durationSec: Long?,
    val batteryPercent: Int?,
    val location: EventLocation?,
    val confidence: Double?,
    val acknowledgedBy: Responder?,
    val acknowledgedAtMs: Long?,
    val responseTimeSec: Long?,
    val cancelledBy: Responder?,
    val escalateAtMs: Long?,
    val escalatedAtMs: Long?,
    val smsStatus: SmsStatus,
    val smsDetail: String?,
    val smsUpdatedAtMs: Long?,
    val resolvedAtMs: Long?,
    val notes: String?,
    /** True only for locally simulated developer events. Never sent to the backend. */
    val isTest: Boolean = false,
)

/** Data carried by an FCM emergency message. Parsed from the data payload only. */
data class AlertPayload(
    val eventId: String,
    val patientId: String,
    val patientName: String?,
    val deviceId: String?,
    val timestampMs: Long?,
    val battery: Int?,
    val location: EventLocation?,
    val alertType: String,
    val status: SosStatus,
)

enum class HistoryRange { ALL, WEEK, MONTH, CUSTOM }
