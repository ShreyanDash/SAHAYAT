package com.sahayat.app.emergency

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Sends backup SMS from **this phone's own SIM** (the patient's phone).
 * The result reported is what Android's radio actually returned for each message —
 * "SENT" means the SIM accepted it for delivery, never assumed.
 */
object SmsFallback {
    private const val TAG = "SahayatSMS"
    private const val ACTION_SENT = "com.sahayat.app.SMS_SENT"
    private val seq = AtomicInteger(1)

    data class Report(val status: String, val detail: String, val sentTo: Int, val attempted: Int)

    const val SENT = "SENT"
    const val FAILED = "FAILED"
    const val UNAVAILABLE = "UNAVAILABLE"

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /** Why SMS can't be sent right now, or null if it can. */
    fun unavailableReason(context: Context): String? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) return "This phone cannot send SMS"
        if (!hasPermission(context)) return "SMS permission not granted"
        val tm = context.getSystemService(TelephonyManager::class.java)
        if (tm != null && tm.simState != TelephonyManager.SIM_STATE_READY) return "No SIM ready"
        return null
    }

    @Suppress("DEPRECATION")
    private fun manager(context: Context): SmsManager? = runCatching {
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
    }.getOrNull()

    suspend fun send(context: Context, numbers: List<String>, text: String): Report {
        val targets = numbers.map { n -> n.filter { it.isDigit() || it == '+' } }.filter { it.length >= 10 }.distinct()
        if (targets.isEmpty()) return Report(UNAVAILABLE, "No SMS contacts", 0, 0)
        unavailableReason(context)?.let { return Report(UNAVAILABLE, it, 0, targets.size) }
        val sms = manager(context) ?: return Report(UNAVAILABLE, "SMS service unavailable", 0, targets.size)

        var ok = 0
        for (number in targets) {
            if (sendOne(context, sms, number, text)) ok++
        }
        // Never log phone numbers or message text.
        Log.i(TAG, "SMS fallback: $ok/${targets.size} accepted by the radio")
        return if (ok > 0) {
            Report(SENT, "Sent to $ok of ${targets.size} contact(s)", ok, targets.size)
        } else {
            Report(FAILED, "Radio rejected all ${targets.size} message(s)", 0, targets.size)
        }
    }

    private suspend fun sendOne(context: Context, sms: SmsManager, number: String, text: String): Boolean {
        val parts = runCatching { sms.divideMessage(text) }.getOrNull() ?: arrayListOf(text)
        val action = "$ACTION_SENT.${seq.getAndIncrement()}"
        val result = withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { cont ->
                val remaining = AtomicInteger(parts.size)
                var failed = false
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context, intent: Intent) {
                        if (resultCode != Activity.RESULT_OK) failed = true
                        if (remaining.decrementAndGet() == 0) {
                            runCatching { context.unregisterReceiver(this) }
                            if (cont.isActive) cont.resume(!failed)
                        }
                    }
                }
                ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                val intents = ArrayList(
                    parts.indices.map { i ->
                        PendingIntent.getBroadcast(
                            context,
                            (action.hashCode() * 31) + i,
                            Intent(action).setPackage(context.packageName),
                            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    },
                )
                try {
                    if (parts.size == 1) sms.sendTextMessage(number, null, parts[0], intents[0], null)
                    else sms.sendMultipartTextMessage(number, null, parts, intents, null)
                } catch (e: Exception) {
                    Log.w(TAG, "SMS send threw ${e::class.java.simpleName}")
                    runCatching { context.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resume(false)
                }
            }
        }
        return result == true
    }
}
