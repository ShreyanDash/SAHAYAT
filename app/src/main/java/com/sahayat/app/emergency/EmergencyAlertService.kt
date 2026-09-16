package com.sahayat.app.emergency

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sahayat.app.SahayatApp
import com.sahayat.app.core.Remote
import com.sahayat.app.data.TestAlertRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

/**
 * Foreground service that owns the emergency alarm: looping alarm-stream audio plus a repeating
 * vibration, shown with a full-screen SOS notification. It stops immediately when the caregiver
 * acknowledges/cancels, or when the backend reports the event is no longer open (e.g. another
 * caregiver responded).
 *
 * OS limits (stated honestly): audio uses the ALARM stream, which most devices keep audible in
 * silent mode, and Do Not Disturb lets alarms through by default — but the user or OEM can
 * change either. Bypassing DND for the notification requires the user to grant DND access.
 */
class EmergencyAlertService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var watchJob: Job? = null
    private var timeoutJob: Job? = null
    private var currentEventId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /** Called directly (same process) so stopping never depends on a background service start. */
    internal fun stopFor(eventId: String?) {
        if (eventId == null || eventId == currentEventId) stopAlarm()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val eventId = intent.getStringExtra(EmergencyNotifier.EXTRA_EVENT_ID) ?: return stopAndReturn()
                val patientId = intent.getStringExtra(EmergencyNotifier.EXTRA_PATIENT_ID)
                val patientName = intent.getStringExtra(EXTRA_PATIENT_NAME)
                val isTest = intent.getBooleanExtra(EmergencyNotifier.EXTRA_IS_TEST, false)
                startAlarm(eventId, patientId, patientName, isTest)
            }
            ACTION_STOP -> {
                val eventId = intent.getStringExtra(EmergencyNotifier.EXTRA_EVENT_ID)
                if (eventId == null || eventId == currentEventId) stopAlarm()
            }
            else -> return stopAndReturn()
        }
        return START_NOT_STICKY
    }

    private fun stopAndReturn(): Int {
        stopAlarm()
        return START_NOT_STICKY
    }

    private fun startAlarm(eventId: String, patientId: String?, patientName: String?, isTest: Boolean) {
        val notification = EmergencyNotifier.buildSos(this, eventId, patientId, patientName, isTest, fallback = false)
        val nid = EmergencyNotifier.notificationId(eventId)
        try {
            ServiceCompat.startForeground(
                this, nid, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed: ${e::class.java.simpleName}")
            postFallback(this, eventId, patientId, patientName, isTest)
            stopSelf()
            return
        }

        // A new event replaces any previous one.
        currentEventId?.takeIf { it != eventId }?.let { EmergencyNotifier.cancel(this, it) }
        releaseOutputs()
        currentEventId = eventId
        _ringingEventId.value = eventId

        acquireWakeLock()
        scope.launch {
            val settings = (application as SahayatApp).container.settings.current()
            if (currentEventId != eventId) return@launch
            if (settings.emergencySound) startAudio()
            if (settings.emergencyVibration) startVibration()
        }

        watchJob?.cancel()
        // Local developer alerts have no backend document; everything else is watched.
        if (!TestAlertRepository.isTestId(eventId)) {
            val alerts = (application as SahayatApp).container.alertService
            watchJob = scope.launch {
                alerts.observeEvent(eventId).collect { r ->
                    if (r is Remote.Data && !r.value.status.isOpen) stopAlarm()
                }
            }
        }
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(MAX_RING_MS)
            // Stop the noise after the safety window but keep the notification visible.
            releaseOutputs()
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sahayat:sos").apply {
            setReferenceCounted(false)
            acquire(MAX_RING_MS + 5_000)
        }
    }

    private fun startAudio() {
        val uri = EmergencyNotifier.alarmUri() ?: return
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@EmergencyAlertService, uri)
                isLooping = true
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }.onFailure { Log.w(TAG, "Alarm audio unavailable: ${it::class.java.simpleName}") }.getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!v.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(EmergencyNotifier.VIBRATION_PATTERN, 0)
        if (Build.VERSION.SDK_INT >= 33) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        }
        vibrator = v
    }

    private fun releaseOutputs() {
        player?.runCatching { if (isPlaying) stop(); release() }
        player = null
        vibrator?.cancel()
        vibrator = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun stopAlarm() {
        releaseOutputs()
        watchJob?.cancel()
        timeoutJob?.cancel()
        currentEventId?.let { EmergencyNotifier.cancel(this, it) }
        currentEventId = null
        _ringingEventId.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        releaseOutputs()
        _ringingEventId.value = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SahayatSOS"
        const val ACTION_START = "com.sahayat.app.SOS_START"
        const val ACTION_STOP = "com.sahayat.app.SOS_STOP"
        const val EXTRA_PATIENT_NAME = "sahayat.patientName"
        /** Hard cap on continuous noise so a lost phone doesn't ring forever. */
        const val MAX_RING_MS = 10 * 60_000L

        @Volatile
        private var instance: EmergencyAlertService? = null

        private val _ringingEventId = MutableStateFlow<String?>(null)
        /** Event currently sounding the alarm, if any (for the in-app indicator). */
        val ringingEventId: StateFlow<String?> = _ringingEventId

        fun start(context: Context, eventId: String, patientId: String?, patientName: String?, isTest: Boolean) {
            val i = Intent(context, EmergencyAlertService::class.java).apply {
                action = ACTION_START
                putExtra(EmergencyNotifier.EXTRA_EVENT_ID, eventId)
                putExtra(EmergencyNotifier.EXTRA_PATIENT_ID, patientId)
                putExtra(EXTRA_PATIENT_NAME, patientName)
                putExtra(EmergencyNotifier.EXTRA_IS_TEST, isTest)
            }
            try {
                ContextCompat.startForegroundService(context, i)
            } catch (e: Exception) {
                // Background-start restriction (e.g. normal-priority FCM): fall back to an insistent notification.
                Log.w(TAG, "Could not start alarm service: ${e::class.java.simpleName}")
                postFallback(context, eventId, patientId, patientName, isTest)
            }
        }

        fun stop(context: Context, eventId: String?) {
            eventId?.let { EmergencyNotifier.cancel(context, it) }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                val svc = instance
                if (svc != null) {
                    svc.stopFor(eventId)
                } else if (_ringingEventId.value != null) {
                    val i = Intent(context, EmergencyAlertService::class.java).apply {
                        action = ACTION_STOP
                        putExtra(EmergencyNotifier.EXTRA_EVENT_ID, eventId)
                    }
                    runCatching { context.startService(i) }
                }
            }
        }

        @SuppressLint("MissingPermission")
        internal fun postFallback(context: Context, eventId: String, patientId: String?, patientName: String?, isTest: Boolean) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (!nm.areNotificationsEnabled()) return
            val settingsSound = runBlocking { (context.applicationContext as SahayatApp).container.settings.settings.first().emergencySound }
            val n = EmergencyNotifier.buildSos(context, eventId, patientId, patientName, isTest, fallback = settingsSound)
            nm.notify(EmergencyNotifier.notificationId(eventId), n)
        }
    }
}
