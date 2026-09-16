package com.sahayat.app.emergency

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sahayat.app.SahayatApp
import com.sahayat.app.core.Outcome
import com.sahayat.app.data.TestAlertRepository
import com.sahayat.app.data.model.SosStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles "Acknowledge" / "Cancel" tapped directly on the SOS notification.
 * The alarm is only silenced after the backend confirms the transition; on failure the
 * alarm keeps going and the caregiver is told to open the app.
 */
class AlertActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getStringExtra(EmergencyNotifier.EXTRA_EVENT_ID) ?: return
        // Only local developer alerts are handled on-device; backend test alerts go to the backend.
        val isTest = TestAlertRepository.isTestId(eventId)
        val container = (context.applicationContext as SahayatApp).container
        val target = when (intent.action) {
            ACTION_ACK -> SosStatus.ACKNOWLEDGED
            ACTION_CANCEL -> SosStatus.CANCELLED
            else -> return
        }

        if (isTest) {
            container.testAlerts.transition(eventId, target)
            EmergencyAlertService.stop(context, eventId)
            return
        }

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val result = withTimeoutOrNull(9_000) {
                    if (target == SosStatus.ACKNOWLEDGED) container.alertService.acknowledge(eventId)
                    else container.alertService.cancelFalseAlarm(eventId)
                }
                if (result is Outcome.Success) {
                    EmergencyAlertService.stop(context, eventId)
                } else {
                    EmergencyNotifier.postGeneral(
                        context, ("fail$eventId").hashCode(),
                        "Response not sent",
                        "Could not reach SAHAYAT servers. Open the app to respond — the alert is still active.",
                        eventId,
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_ACK = "com.sahayat.app.SOS_ACK"
        const val ACTION_CANCEL = "com.sahayat.app.SOS_CANCEL"
    }
}
