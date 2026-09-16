package com.sahayat.app.emergency

import com.sahayat.app.data.model.AlertPayload
import com.sahayat.app.data.model.EventLocation
import com.sahayat.app.data.model.SosStatus
import org.json.JSONObject

/**
 * Parses the FCM *data* payload sent by the backend.
 *
 * Expected keys (all strings, as FCM data values always are):
 *   type        = "SOS_ALERT" | "SOS_STATUS" | "DEVICE_STATUS"
 *   eventId, patientId, patientName?, deviceId?, timestamp (epoch ms), battery?,
 *   location?   = JSON {"lat":..,"lng":..,"accuracy":..,"source":".."}
 *   alertType   = e.g. "SEIZURE_SUSPECTED"
 *   status?     = SosStatus name
 */
object AlertPayloadParser {
    const val TYPE_SOS_ALERT = "SOS_ALERT"
    const val TYPE_SOS_STATUS = "SOS_STATUS"
    const val TYPE_DEVICE_STATUS = "DEVICE_STATUS"

    private val ID = Regex("^[A-Za-z0-9_-]{4,128}$")

    fun parse(data: Map<String, String>): AlertPayload? {
        val eventId = data["eventId"]?.takeIf { ID.matches(it) } ?: return null
        val patientId = data["patientId"]?.takeIf { ID.matches(it) } ?: return null
        return AlertPayload(
            eventId = eventId,
            patientId = patientId,
            patientName = data["patientName"]?.take(80)?.takeIf { it.isNotBlank() },
            deviceId = data["deviceId"]?.take(64)?.takeIf { it.isNotBlank() },
            timestampMs = data["timestamp"]?.toLongOrNull(),
            battery = data["battery"]?.toIntOrNull()?.takeIf { it in 0..100 },
            location = data["location"]?.let(::parseLocation),
            alertType = data["alertType"] ?: "SEIZURE_SUSPECTED",
            status = SosStatus.parse(data["status"]),
        )
    }

    private fun parseLocation(raw: String): EventLocation? = runCatching {
        val o = JSONObject(raw)
        val lat = o.getDouble("lat")
        val lng = o.getDouble("lng")
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        EventLocation(
            lat, lng,
            if (o.has("accuracy")) o.getDouble("accuracy") else null,
            o.optString("source").takeIf { it.isNotBlank() },
        )
    }.getOrNull()
}
