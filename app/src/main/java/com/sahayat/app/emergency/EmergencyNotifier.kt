package com.sahayat.app.emergency

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.sahayat.app.MainActivity
import com.sahayat.app.R

/**
 * Notification channels and SOS notifications.
 *
 * Channels:
 *  - [CHANNEL_SOS]: high importance, no channel sound — [EmergencyAlertService] plays the looping
 *    alarm itself on the ALARM stream so it can be stopped the instant the alert is handled.
 *  - [CHANNEL_SOS_FALLBACK]: used only if the foreground service can't be started; the channel
 *    plays the alarm sound and the notification is marked insistent (repeats until dismissed).
 *  - [CHANNEL_GENERAL]: device offline, low battery, escalation updates.
 */
object EmergencyNotifier {
    const val CHANNEL_SOS = "sahayat_sos"
    const val CHANNEL_SOS_FALLBACK = "sahayat_sos_fallback"
    const val CHANNEL_GENERAL = "sahayat_general"

    const val EXTRA_EVENT_ID = "sahayat.eventId"
    const val EXTRA_PATIENT_ID = "sahayat.patientId"
    const val EXTRA_IS_TEST = "sahayat.isTest"

    val VIBRATION_PATTERN = longArrayOf(0, 800, 400, 800, 400, 1200, 800)

    fun notificationId(eventId: String) = 0x5A000 + (eventId.hashCode() and 0xFFF)

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val alarmAttrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val sos = NotificationChannel(CHANNEL_SOS, "Seizure emergency alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Full-screen emergency alerts from the SAHAYAT wearable."
            setSound(null, null)
            enableVibration(false) // vibration is driven by EmergencyAlertService
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            // Takes effect only if the user has granted Do Not Disturb access before the channel is created.
            if (nm.isNotificationPolicyAccessGranted) setBypassDnd(true)
        }
        val fallback = NotificationChannel(CHANNEL_SOS_FALLBACK, "Seizure emergency alerts (backup)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Used when the emergency alarm service can't start."
            setSound(alarmUri(), alarmAttrs)
            enableVibration(true)
            vibrationPattern = VIBRATION_PATTERN
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            if (nm.isNotificationPolicyAccessGranted) setBypassDnd(true)
        }
        val general = NotificationChannel(CHANNEL_GENERAL, "Device & status updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Device disconnected, low battery, escalation and SMS updates."
        }
        nm.createNotificationChannels(listOf(sos, fallback, general))
    }

    /** Recreates emergency channels so a newly granted DND access applies. */
    fun recreateEmergencyChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(CHANNEL_SOS)
        nm.deleteNotificationChannel(CHANNEL_SOS_FALLBACK)
        ensureChannels(context)
    }

    fun alarmUri(): Uri? =
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

    fun sosIntent(context: Context, eventId: String, patientId: String?, isTest: Boolean): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("sahayat://sos/$eventId")
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_PATIENT_ID, patientId)
            putExtra(EXTRA_IS_TEST, isTest)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    private fun actionIntent(context: Context, action: String, eventId: String, isTest: Boolean): PendingIntent {
        val i = Intent(context, AlertActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_EVENT_ID, eventId)
            putExtra(EXTRA_IS_TEST, isTest)
        }
        return PendingIntent.getBroadcast(
            context, (action + eventId).hashCode(), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun buildSos(
        context: Context,
        eventId: String,
        patientId: String?,
        patientName: String?,
        isTest: Boolean,
        fallback: Boolean,
    ): Notification {
        val open = PendingIntent.getActivity(
            context, eventId.hashCode(), sosIntent(context, eventId, patientId, isTest),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val who = patientName ?: "Patient"
        val title = if (isTest) "TEST ALERT — not a real event" else "Possible seizure detected"
        val text = if (isTest) "Developer test of the SOS flow. No real device event."
        else "$who's SAHAYAT band reported a possible seizure. Tap to respond."

        val b = NotificationCompat.Builder(context, if (fallback) CHANNEL_SOS_FALLBACK else CHANNEL_SOS)
            .setSmallIcon(R.drawable.ic_stat_sahayat)
            .setColor(0xFF2AC976.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(0, "Acknowledge — I'm on my way", actionIntent(context, AlertActionReceiver.ACTION_ACK, eventId, isTest))
            .addAction(0, "Cancel — False Alarm", actionIntent(context, AlertActionReceiver.ACTION_CANCEL, eventId, isTest))
        if (fallback) {
            b.setSound(alarmUri()).setVibrate(VIBRATION_PATTERN)
        } else {
            b.setSilent(true)
        }
        val n = b.build()
        if (fallback) n.flags = n.flags or Notification.FLAG_INSISTENT
        return n
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun postGeneral(context: Context, id: Int, title: String, text: String, eventId: String? = null) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        val intent = if (eventId != null) sosIntent(context, eventId, null, false)
        else Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL_GENERAL)
            .setSmallIcon(R.drawable.ic_stat_sahayat)
            .setColor(0xFF2AC976.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(id, n)
    }

    fun cancel(context: Context, eventId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(notificationId(eventId))
    }
}
