package com.sahayat.app.ble

import java.util.UUID

/**
 * Bluetooth LE protocol spoken by the SAHAYAT band (firmware/pico_w/main.py). UTF-8 text.
 *
 *   Service    7B7E0001-6B8A-4D9E-9C5B-2A4F6D8E1001   (the app scans for this)
 *   Device ID  7B7E0005-…  READ           "SAH-0001"
 *   Status     7B7E0002-…  READ + NOTIFY  "MONITORING" | "STOPPED" | "SAHAYAT_OK" | "MPU_ERROR"
 *   Alert      7B7E0003-…  READ + NOTIFY  "ALERT|<deviceId>|SEIZURE|CANCELLED|ALERT_ACTIVE|<ref>"
 *   Command    7B7E0004-…  READ + WRITE   "PING" | "START" | "STOP" | "STATUS"
 *
 * 0x2902 ([CCCD]) is the standard descriptor under Status and Alert used to turn notifications on.
 * It is never used to find the band.
 */
object BandProtocol {
    val SERVICE: UUID = UUID.fromString("7B7E0001-6B8A-4D9E-9C5B-2A4F6D8E1001")
    val STATUS: UUID = UUID.fromString("7B7E0002-6B8A-4D9E-9C5B-2A4F6D8E1001")
    val ALERT: UUID = UUID.fromString("7B7E0003-6B8A-4D9E-9C5B-2A4F6D8E1001")
    val COMMAND: UUID = UUID.fromString("7B7E0004-6B8A-4D9E-9C5B-2A4F6D8E1001")
    val DEVICE_ID: UUID = UUID.fromString("7B7E0005-6B8A-4D9E-9C5B-2A4F6D8E1001")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val EXPECTED_NAME = "SAHAYAT-BAND"
    /** MTU requested before notifications are enabled (messages exceed the 20-byte default). */
    const val MTU = 185

    private val DEVICE_ID_REGEX = Regex("^SAH-[A-Z0-9]{4,16}$")

    fun isValidDeviceId(id: String?): Boolean = id != null && DEVICE_ID_REGEX.matches(id)

    private fun text(bytes: ByteArray?): String? =
        bytes?.let { runCatching { String(it, Charsets.UTF_8) }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }

    fun parseDeviceId(bytes: ByteArray?): String? = text(bytes)?.uppercase()?.takeIf { isValidDeviceId(it) }

    fun parseStatus(bytes: ByteArray?): BandStatus? {
        val raw = text(bytes) ?: return null
        val kind = when (raw.uppercase()) {
            "MONITORING" -> BandStatusKind.MONITORING
            "STOPPED" -> BandStatusKind.STOPPED
            "SAHAYAT_OK" -> BandStatusKind.PING_OK
            "MPU_ERROR" -> BandStatusKind.SENSOR_ERROR
            else -> BandStatusKind.UNKNOWN
        }
        return BandStatus(kind, raw.take(40), System.currentTimeMillis())
    }

    /** "ALERT|SAH-0001|SEIZURE" or "ALERT|SAH-0001|SEIZURE|3fa1-2". */
    fun parseAlert(bytes: ByteArray?): BandAlert? {
        val raw = text(bytes) ?: return null
        val parts = raw.split('|')
        if (parts.size < 3 || !parts[0].equals("ALERT", ignoreCase = true)) return null
        val deviceId = parts[1].trim().uppercase().takeIf { isValidDeviceId(it) } ?: return null
        val kind = when (parts[2].trim().uppercase()) {
            "SEIZURE" -> BandAlertKind.SEIZURE
            "CANCELLED" -> BandAlertKind.CANCELLED
            "ALERT_ACTIVE" -> BandAlertKind.ALERT_ACTIVE
            else -> return null
        }
        val ref = parts.getOrNull(3)?.trim()?.filter { it.isLetterOrDigit() || it == '-' }?.take(24)?.ifBlank { null }
        return BandAlert(deviceId, kind, ref, raw.take(60), System.currentTimeMillis())
    }

    fun ping(): ByteArray = "PING".toByteArray(Charsets.UTF_8)
    fun start(): ByteArray = "START".toByteArray(Charsets.UTF_8)
    fun stop(): ByteArray = "STOP".toByteArray(Charsets.UTF_8)
    fun status(): ByteArray = "STATUS".toByteArray(Charsets.UTF_8)
}

enum class BandStatusKind { MONITORING, STOPPED, PING_OK, SENSOR_ERROR, UNKNOWN }

/**
 * A status message exactly as the band sent it. The current firmware reports no battery,
 * firmware version or uptime, so those stay null and the UI shows "Unavailable".
 */
data class BandStatus(
    val kind: BandStatusKind,
    val raw: String,
    val receivedAtMs: Long,
) {
    val batteryPercent: Int? get() = null
    val charging: Boolean? get() = null
    val firmware: String? get() = null
    val uptimeSec: Long? get() = null
}

enum class BandAlertKind { SEIZURE, CANCELLED, ALERT_ACTIVE }

data class BandAlert(
    val deviceId: String,
    val kind: BandAlertKind,
    /** Unique per alert on newer firmware; null if the band sent the 3-field form. */
    val ref: String?,
    val raw: String,
    val receivedAtMs: Long,
)
