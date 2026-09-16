package com.sahayat.app.emergency

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import com.sahayat.app.SahayatApp
import com.sahayat.app.core.Remote
import com.sahayat.app.data.model.AlertPayload
import com.sahayat.app.data.model.SosStatus
import com.sahayat.app.data.model.TEST_ALERT_TYPE
import com.sahayat.app.data.service.AppRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Caregiver phone: an SMS from the patient's phone ("SAHAYAT SOS … Ref #<eventId>") raises the
 * full alarm even when this phone has no internet. Free — it rides on the patient's SMS plan.
 */
class SosSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val body = readBody(intent) ?: return
        if (!body.contains(MARKER)) return
        val eventId = REF.find(body)?.groupValues?.get(1) ?: return
        val isTest = body.trimStart().startsWith("[TEST]")
        val name = NAME.find(body)?.groupValues?.get(1)?.trim()?.take(60)
        val location = LOC.find(body)?.let { m ->
            val lat = m.groupValues[1].toDoubleOrNull()
            val lng = m.groupValues[2].toDoubleOrNull()
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                com.sahayat.app.data.model.EventLocation(lat, lng, null, "SMS")
            } else null
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                raise(context.applicationContext, eventId, name, isTest, location)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun raise(
        context: Context,
        eventId: String,
        name: String?,
        isTest: Boolean,
        location: com.sahayat.app.data.model.EventLocation?,
    ) {
        val c = (context as SahayatApp).container
        if (c.settings.currentProfile().role != AppRole.CAREGIVER) return
        // If we can see the event and someone already handled it, don't ring again.
        val current = withTimeoutOrNull(4_000) {
            c.alertService.observeEvent(eventId).first { it !is Remote.Loading }
        }
        if (current is Remote.Data && !current.value.status.isOpen) return
        if (!AlertDedup.firstTime(eventId)) return
        Log.i(TAG, "SOS SMS received")
        val patientId = c.secureStore.patientId ?: "unknown"
        c.sosInbox.onAlertReceived(
            AlertPayload(
                eventId = eventId,
                patientId = patientId,
                patientName = name,
                deviceId = eventId.substringBefore('_').takeIf { it.startsWith("SAH-") },
                timestampMs = System.currentTimeMillis(),
                battery = null,
                location = location,
                alertType = if (isTest) TEST_ALERT_TYPE else "SEIZURE_SUSPECTED",
                status = SosStatus.ACTIVE,
            ),
        )
        EmergencyAlertService.start(context, eventId, patientId, name, isTest)
    }

    private fun readBody(intent: Intent): String? {
        val pdus = intent.extras?.get("pdus") as? Array<*> ?: return null
        val format = intent.getStringExtra("format")
        return pdus.mapNotNull { pdu ->
            (pdu as? ByteArray)?.let { SmsMessage.createFromPdu(it, format)?.messageBody }
        }.joinToString("").takeIf { it.isNotBlank() }
    }

    private companion object {
        const val TAG = "SahayatSmsIn"
        const val MARKER = "SAHAYAT SOS"
        val REF = Regex("Ref #([A-Za-z0-9_-]{4,128})")
        val LOC = Regex("maps\\.google\\.com/\\?q=(-?[0-9.]+),(-?[0-9.]+)")
        val NAME = Regex("possible seizure detected for (.+?)\\.")
    }
}
