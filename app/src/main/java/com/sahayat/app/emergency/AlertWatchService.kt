package com.sahayat.app.emergency

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sahayat.app.MainActivity
import com.sahayat.app.R
import com.sahayat.app.SahayatApp
import com.sahayat.app.core.Remote
import com.sahayat.app.data.SessionPhase
import com.sahayat.app.data.model.AlertPayload
import com.sahayat.app.data.model.DeviceStatus
import com.sahayat.app.data.model.STALE_AFTER_MS
import com.sahayat.app.data.model.SeizureEvent
import com.sahayat.app.data.model.SosStatus
import com.sahayat.app.data.model.TEST_ALERT_TYPE
import com.sahayat.app.data.service.AppRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Caregiver phone: keeps a live Firestore listener on the patient's open SOS events and rings
 * the alarm the moment one appears. Replaces server push (no Cloud Functions / Blaze needed).
 * Works while this phone has internet; the SMS path ([SosSmsReceiver]) covers the rest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertWatchService : Service() {

    private val container get() = (application as SahayatApp).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false
    private val escalationShown = mutableSetOf<String>()
    private var lastDevice: DeviceStatus? = null
    private var bandWarningShown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything()
            return START_NOT_STICKY
        }
        if (!goForeground(getString(R.string.watch_listening))) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            watch()
        }
        return START_STICKY
    }

    private fun goForeground(text: String): Boolean = try {
        ensureChannel(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, build(text), type)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Cannot start foreground: ${e::class.java.simpleName}")
        false
    }

    private fun build(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, NOTIF_ID,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sahayat)
            .setColor(0xFF2AC976.toInt())
            .setContentTitle(getString(R.string.watch_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun setText(text: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(text)) }
    }

    private fun watch() {
        val session = container.session.state

        // Stop when the user signs out.
        session.onEach { if (it.phase == SessionPhase.SIGNED_OUT) stopEverything() }.launchIn(scope)

        // Open SOS events for this caregiver's patient.
        session.map { it.uid to it.patientId }.distinctUntilChanged().flatMapLatest { (uid, pid) ->
            if (uid == null || pid == null) emptyFlow() else container.alertService.observeOpenEvents(pid)
        }.onEach { r ->
            when (r) {
                is Remote.Data -> {
                    setText(getString(R.string.watch_listening))
                    r.value.forEach(::onOpenEvent)
                }
                is Remote.Unavailable -> setText(getString(R.string.watch_offline))
                Remote.Loading -> Unit
            }
        }.launchIn(scope)

        // Band health, as reported by the patient's phone.
        session.map { it.deviceId }.distinctUntilChanged().flatMapLatest { did ->
            if (did == null) emptyFlow() else container.deviceService.observeDevice(did)
        }.onEach { r ->
            if (r is Remote.Data) {
                lastDevice = r.value
                checkBand()
            }
        }.launchIn(scope)

        scope.launch {
            while (isActive) {
                delay(60_000)
                checkBand()
            }
        }
    }

    private fun onOpenEvent(e: SeizureEvent) {
        val now = System.currentTimeMillis()
        if (now - e.detectedAtMs > RING_WINDOW_MS) return
        if (AlertDedup.firstTime(e.id)) {
            Log.i(TAG, "Open SOS event received")
            container.sosInbox.onAlertReceived(e.toPayload())
            EmergencyAlertService.start(this, e.id, e.patientId, e.patientName, isTest = e.alertType == TEST_ALERT_TYPE)
        }
        if (e.status == SosStatus.ESCALATED && escalationShown.add(e.id)) {
            EmergencyNotifier.postGeneral(
                this, ("esc" + e.id).hashCode(),
                getString(R.string.alert_escalated_title), getString(R.string.alert_escalated_body), e.id,
            )
        }
    }

    private fun checkBand() {
        val d = lastDevice ?: return
        val seen = d.lastSeenMs
        val stale = d.patientId != null && (seen == null || System.currentTimeMillis() - seen > STALE_AFTER_MS)
        if (stale && !bandWarningShown) {
            bandWarningShown = true
            EmergencyNotifier.postGeneral(
                this, ("dev" + d.deviceId).hashCode(),
                getString(R.string.band_offline_title), getString(R.string.band_offline_body),
            )
        } else if (!stale) {
            bandWarningShown = false
        }
    }

    private fun stopEverything() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        _running.value = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SahayatWatch"
        private const val NOTIF_ID = 0x5B002
        private const val CHANNEL = "sahayat_watch"
        private const val ACTION_START = "com.sahayat.app.watch.START"
        private const val ACTION_STOP = "com.sahayat.app.watch.STOP"
        /** Older open events are shown in the app but don't start the alarm again. */
        private const val RING_WINDOW_MS = 30 * 60_000L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running

        fun ensureChannel(context: Context) {
            val ch = NotificationChannel(CHANNEL, "Alert listener", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while this phone listens for the patient's SOS alerts."
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        suspend fun shouldRun(context: Context): Boolean {
            val c = (context.applicationContext as SahayatApp).container
            return c.settings.currentProfile().role == AppRole.CAREGIVER && c.secureStore.uid != null
        }

        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, AlertWatchService::class.java).setAction(ACTION_START))
            _running.value = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "Listener start refused: ${e::class.java.simpleName}")
            false
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AlertWatchService::class.java)) }
            _running.value = false
        }
    }
}

/** Makes sure the Firestore listener and the SMS path don't ring twice for one event. */
object AlertDedup {
    private val raised = LinkedHashMap<String, Long>()

    @Synchronized
    fun firstTime(eventId: String): Boolean {
        val now = System.currentTimeMillis()
        raised.entries.removeAll { now - it.value > 6 * 3_600_000L }
        if (raised.containsKey(eventId)) return false
        raised[eventId] = now
        return true
    }
}

fun SeizureEvent.toPayload() = AlertPayload(
    eventId = id,
    patientId = patientId,
    patientName = patientName,
    deviceId = deviceId,
    timestampMs = detectedAtMs,
    battery = batteryPercent,
    location = location,
    alertType = alertType,
    status = status,
)
