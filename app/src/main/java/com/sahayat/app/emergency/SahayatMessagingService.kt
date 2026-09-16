package com.sahayat.app.emergency

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sahayat.app.SahayatApp
import com.sahayat.app.ble.BandLinkService
import org.json.JSONArray
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Receives FCM messages in every app state (foreground, background, killed, screen locked).
 * The backend must send **data-only, priority=high** messages so this is always invoked and
 * the app, not the system tray, decides how to raise the alarm.
 */
class SahayatMessagingService : FirebaseMessagingService() {

    private val container get() = (application as SahayatApp).container

    override fun onNewToken(token: String) {
        val uid = container.secureStore.uid ?: return
        runBlocking {
            withTimeoutOrNull(10_000) { container.notificationService.onTokenRefreshed(uid, token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val type = data["type"]
        // Never log payload contents (patient data) — only the type.
        Log.i(TAG, "FCM message type=$type priority=${message.priority}")

        // Ignore anything for a patient this phone isn't signed in for.
        if (container.secureStore.uid == null) return

        // Requests addressed to the patient's relay phone.
        when (type) {
            TYPE_SEND_SMS -> {
                handleSendSms(data)
                return
            }
            TYPE_BAND_SYNC -> {
                if (container.secureStore.bandId != null && data["deviceId"] == container.secureStore.bandId) {
                    BandLinkService.sync(this)
                }
                return
            }
        }

        val payload = AlertPayloadParser.parse(data)
        val myPatient = container.secureStore.patientId
        if (payload != null && myPatient != null && payload.patientId != myPatient) {
            Log.w(TAG, "Ignoring alert for a different patient")
            return
        }

        when (type) {
            AlertPayloadParser.TYPE_SOS_ALERT -> {
                if (payload == null) {
                    Log.w(TAG, "Malformed SOS payload ignored")
                    return
                }
                if (payload.status.isOpen) {
                    container.sosInbox.onAlertReceived(payload)
                    EmergencyAlertService.start(
                        this, payload.eventId, payload.patientId, payload.patientName,
                        isTest = payload.alertType == com.sahayat.app.data.model.TEST_ALERT_TYPE,
                    )
                }
            }
            AlertPayloadParser.TYPE_SOS_STATUS -> {
                if (payload == null) return
                if (!payload.status.isOpen) {
                    EmergencyAlertService.stop(this, payload.eventId)
                }
                if (payload.status.name == "ESCALATED") {
                    EmergencyNotifier.postGeneral(
                        this, payload.eventId.hashCode(),
                        "Alert escalated",
                        "No acknowledgement within the escalation window. Backup contacts are being notified.",
                        payload.eventId,
                    )
                }
            }
            AlertPayloadParser.TYPE_DEVICE_STATUS -> {
                val title = data["title"]?.take(80) ?: return
                val body = data["body"]?.take(240) ?: return
                EmergencyNotifier.postGeneral(this, ("dev" + (data["deviceId"] ?: "")).hashCode(), title, body)
            }
            else -> {
                // Plain notification messages are shown by the system in background; show them in foreground too.
                message.notification?.let { n ->
                    EmergencyNotifier.postGeneral(this, (n.title ?: "").hashCode(), n.title ?: "SAHAYAT", n.body ?: "")
                }
            }
        }
    }

    /** Escalation: the backend asks this (patient) phone to text caregivers from its SIM. */
    private fun handleSendSms(data: Map<String, String>) {
        val eventId = data["eventId"]?.takeIf { EVENT_ID.matches(it) } ?: return
        val patientId = data["patientId"]
        if (patientId != null && patientId != container.secureStore.patientId) return
        val text = data["text"]?.take(480) ?: return
        val numbers = runCatching {
            val arr = JSONArray(data["numbers"] ?: "[]")
            List(arr.length()) { arr.getString(it) }
        }.getOrDefault(emptyList())
        runBlocking {
            withTimeoutOrNull(18_000) {
                container.relayService.reportSms(eventId, "SENDING", null)
                val report = SmsFallback.send(this@SahayatMessagingService, numbers, text)
                container.relayService.reportSms(eventId, report.status, report.detail)
            }
        }
    }

    private companion object {
        const val TAG = "SahayatFCM"
        const val TYPE_SEND_SMS = "SEND_SMS"
        const val TYPE_BAND_SYNC = "BAND_SYNC"
        val EVENT_ID = Regex("^[A-Za-z0-9_-]{4,128}$")
    }
}
