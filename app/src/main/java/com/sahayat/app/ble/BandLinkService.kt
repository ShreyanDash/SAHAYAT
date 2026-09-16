package com.sahayat.app.ble

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
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Outcome
import com.sahayat.app.core.Remote
import com.sahayat.app.core.valueOrNull
import com.sahayat.app.data.service.AppRole
import com.sahayat.app.data.service.RelayAlert
import com.sahayat.app.emergency.EmergencyAlertService
import com.sahayat.app.emergency.EmergencyNotifier
import com.sahayat.app.emergency.SmsFallback
import com.sahayat.app.location.PhoneLocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Patient-phone relay. Keeps the Bluetooth link to the band open in the background and:
 *  - forwards band status to Firestore (so caregivers see the real connection / monitoring state),
 *  - forwards seizure alerts to the backend, which pushes SOS to every caregiver phone,
 *  - if the internet is down, texts caregivers from this phone's SIM and retries later.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BandLinkService : Service(), BandBleClient.Listener {

    private val container get() = (application as SahayatApp).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var client: BandBleClient? = null
    private var deviceId: String? = null
    private var lastReportMs = 0L
    private var disconnectJob: Job? = null
    private val alertLock = Any()
    private val liveLocations = java.util.concurrent.ConcurrentHashMap<String, com.sahayat.app.data.model.EventLocation>()
    /** band alert key → alert id used for the Firestore event */
    private val relayedAlerts = LinkedHashMap<String, String>()
    private val confirmJobs = HashMap<String, Job>()
    private var pendingNoRefKey: String? = null
    private val queueLock = Mutex()
    private var observersStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_SYNC, ACTION_COMMAND -> {
                val c = client
                if (c != null) {
                    // Every startForegroundService() call must be answered with startForeground().
                    if (!goForeground()) return START_NOT_STICKY
                    val cmd = when (intent.getStringExtra(EXTRA_COMMAND)) {
                        CMD_START -> BandProtocol.start()
                        CMD_STOP -> BandProtocol.stop()
                        CMD_PING -> BandProtocol.ping()
                        else -> BandProtocol.status()
                    }
                    c.write(cmd)
                    return START_STICKY
                }
            }
        }

        val store = container.secureStore
        val address = store.bandAddress
        val id = store.bandId
        if (address == null || id == null) {
            container.bandLink.update { it.copy(phase = BandLinkPhase.NOT_PAIRED) }
            shutdown()
            return START_NOT_STICKY
        }
        if (!BlePermissions.granted(this)) {
            container.bandLink.update { it.copy(phase = BandLinkPhase.NO_PERMISSION) }
            shutdown()
            return START_NOT_STICKY
        }
        if (!goForeground()) {
            shutdown()
            return START_NOT_STICKY
        }

        if (client == null || deviceId != id) {
            client?.release()
            deviceId = id
            container.bandLink.update {
                it.copy(
                    phase = if (BlePermissions.bluetoothOn(this)) BandLinkPhase.CONNECTING else BandLinkPhase.BLUETOOTH_OFF,
                    deviceId = id, address = address, name = store.bandName, error = null,
                )
            }
            // expectedDeviceId: the band must answer with the Device ID it was paired as.
            client = BandBleClient(this, this, expectedDeviceId = id).also { it.connect(address) }
        }
        startObservers()
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        ensureChannel(this)
        var type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        // Location type only when Android will allow it (precise/approximate + "all the time"),
        // otherwise starting the service would be refused.
        if (Build.VERSION.SDK_INT >= 29 && PhoneLocation.hasPermission(this) && PhoneLocation.hasBackground(this)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        val text = getString(
            if (container.bandLink.state.value.connected) R.string.band_link_connected else R.string.band_link_connecting,
        )
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Cannot start foreground: ${e::class.java.simpleName}")
        false
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, NOTIF_ID,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_LINK)
            .setSmallIcon(R.drawable.ic_stat_sahayat)
            .setColor(0xFF2AC976.toInt())
            .setContentTitle(getString(R.string.band_link_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(pi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    // ------------------------------------------------------------------ observers

    private fun startObservers() {
        if (observersStarted) return
        observersStarted = true
        val session = container.session.state
        var sawOurBand = false

        // Patient document: name, detection settings, and remote unpairing.
        session.onEach { st ->
            val patient = st.patient.valueOrNull ?: return@onEach
            container.secureStore.patientName = patient.name
            container.secureStore.smsFallbackEnabled = patient.detection.smsFallbackEnabled
            val ours = container.secureStore.bandId
            if (patient.deviceId != null && patient.deviceId == ours) sawOurBand = true
            if (sawOurBand && ours != null && patient.deviceId != ours) {
                Log.i(TAG, "Band was unlinked from this patient; stopping relay")
                container.bandLink.onUnpaired()
                shutdown()
                return@onEach
            }
        }.launchIn(scope)

        // Caregiver phone numbers for offline SMS, kept fresh while online.
        session.map { it.patientId }.distinctUntilChanged().flatMapLatest { pid ->
            if (pid == null) emptyFlow() else container.caregiverService.observeCaregivers(pid)
        }.onEach { r ->
            if (r is Remote.Data) {
                container.secureStore.smsNumbers = r.value
                    .filter { it.notifySms && it.phone.isNotBlank() }
                    .sortedBy { it.priority }
                    .map { it.phone }
            }
        }.launchIn(scope)

        // A caregiver tapped "Reconnect": ask the band for a fresh status.
        var lastSync: Long? = null
        container.secureStore.bandId?.let { bid ->
            container.deviceService.observeDevice(bid).onEach { r ->
                val at = (r as? Remote.Data)?.value?.syncRequestedAtMs ?: return@onEach
                if (lastSync != null && at != lastSync) {
                    client?.write(BandProtocol.status())
                    lastReportMs = 0L
                    reportStatus(force = true)
                }
                lastSync = at
            }.launchIn(scope)
        }

        // Retry queued alerts whenever the internet comes back.
        container.connectivity.isOnline.filter { it }.onEach { flushQueue() }.launchIn(scope)

        // Heartbeat: keep the backend's "last seen" fresh even if a status notification is missed.
        scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                val c = client ?: continue
                if (c.isReady) {
                    // The band answers STATUS with a Status notification (MONITORING / STOPPED).
                    c.write(BandProtocol.status())
                    c.readRssi()
                }
                flushQueue()
            }
        }
    }

    // ------------------------------------------------------------------ BLE callbacks

    override fun onConnectionChanged(connected: Boolean) {
        val now = System.currentTimeMillis()
        if (connected) {
            disconnectJob?.cancel()
            container.bandLink.update { it.copy(phase = BandLinkPhase.CONNECTED, connectedSinceMs = now, error = null) }
            updateNotification(getString(R.string.band_link_connected))
            // Round-trip check (band answers SAHAYAT_OK) and current monitoring state.
            client?.write(BandProtocol.ping())
            client?.write(BandProtocol.status())
            client?.readRssi()
            lastReportMs = 0L
            reportStatus(force = true)
        } else {
            container.bandLink.update {
                if (it.phase == BandLinkPhase.INCOMPATIBLE) {
                    it.copy(connectedSinceMs = null)
                } else {
                    it.copy(
                        phase = if (BlePermissions.bluetoothOn(this)) BandLinkPhase.DISCONNECTED else BandLinkPhase.BLUETOOTH_OFF,
                        connectedSinceMs = null,
                    )
                }
            }
            updateNotification(getString(R.string.band_link_reconnecting))
            // Tell caregivers only if the link stays down (brief drops are normal for BLE).
            disconnectJob?.cancel()
            disconnectJob = scope.launch {
                delay(DISCONNECT_GRACE_MS)
                if (client?.isReady != true) sendStatus(connected = false)
            }
        }
    }

    override fun onError(error: BandLinkError) {
        val incompatible = error == BandLinkError.WRONG_DEVICE || error == BandLinkError.DEVICE_ID_INVALID ||
            error == BandLinkError.SERVICE_NOT_FOUND || error == BandLinkError.CHARACTERISTIC_MISSING
        container.bandLink.update {
            it.copy(error = error, phase = if (incompatible) BandLinkPhase.INCOMPATIBLE else it.phase)
        }
        if (incompatible) updateNotification(getString(R.string.band_link_incompatible))
    }

    override fun onStatus(status: BandStatus) {
        if (status.kind == BandStatusKind.PING_OK) {
            container.bandLink.update { it.copy(lastPingOkMs = status.receivedAtMs) }
            return
        }
        val changed = container.bandLink.state.value.status?.kind != status.kind
        container.bandLink.update { it.copy(status = status) }
        reportStatus(force = changed)
    }

    override fun onRssi(rssi: Int) {
        container.bandLink.update { it.copy(rssi = rssi) }
    }

    /**
     * SEIZURE opens the band's 2-s cancel window. Caregivers are alerted on ALERT_ACTIVE, or after
     * [CONFIRM_MS] if the band's follow-up never arrives (e.g. the link dropped). CANCELLED stops it —
     * and if an alert was already posted, it is cancelled in Firestore too.
     */
    override fun onAlert(alert: BandAlert, fromNotification: Boolean) {
        val store = container.secureStore
        if (alert.deviceId != deviceId) {
            Log.w(TAG, "Alert for a different device id ignored")
            return
        }
        if (!fromNotification) {
            // Value read right after connecting: only act on an alert we have not handled yet.
            val ref = alert.ref ?: return
            val last = store.lastAlertRef
            if (last == null) {
                store.lastAlertRef = "$ref|done"
                return
            }
            if (last == "$ref|done") return
        }
        container.bandLink.update { it.copy(lastAlert = alert) }

        synchronized(alertLock) {
            val key = alert.ref ?: when (alert.kind) {
                BandAlertKind.SEIZURE -> "t" + (alert.receivedAtMs / 1000).toString(36)
                else -> pendingNoRefKey ?: ("t" + (alert.receivedAtMs / 1000).toString(36))
            }.also { if (alert.kind == BandAlertKind.SEIZURE) pendingNoRefKey = it }

            when (alert.kind) {
                BandAlertKind.SEIZURE -> {
                    if (relayedAlerts.containsKey(key) || confirmJobs.containsKey(key)) return
                    store.lastAlertRef = "$key|seen"
                    container.bandLink.update { it.copy(pendingAlert = alert) }
                    updateNotification(getString(R.string.band_link_pending))
                    confirmJobs[key] = scope.launch {
                        delay(CONFIRM_MS)
                        confirmAlert(key, alert)
                    }
                }
                BandAlertKind.ALERT_ACTIVE -> {
                    confirmJobs.remove(key)?.cancel()
                    scope.launch { confirmAlert(key, alert) }
                }
                BandAlertKind.CANCELLED -> {
                    confirmJobs.remove(key)?.cancel()
                    if (pendingNoRefKey == key) pendingNoRefKey = null
                    store.lastAlertRef = "$key|done"
                    container.bandLink.update { it.copy(pendingAlert = null) }
                    updateNotification(getString(R.string.band_link_cancelled))
                    val alertId = relayedAlerts[key]
                    val id = deviceId
                    if (alertId != null && id != null) {
                        // Our timer fired before the band's CANCELLED arrived: withdraw the posted alert.
                        scope.launch {
                            container.alertService.cancelFalseAlarm(container.relayService.eventIdFor(id, alertId))
                        }
                    }
                }
            }
        }
    }

    private suspend fun confirmAlert(key: String, alert: BandAlert) {
        val alertId = synchronized(alertLock) {
            confirmJobs.remove(key)
            if (relayedAlerts.containsKey(key)) return
            val id = key.filter { it.isLetterOrDigit() || it == '-' }.take(24) + "-" +
                (alert.receivedAtMs / 1000).toString(36)
            relayedAlerts[key] = id
            while (relayedAlerts.size > 50) relayedAlerts.remove(relayedAlerts.keys.first())
            if (pendingNoRefKey == key) pendingNoRefKey = null
            id
        }
        container.secureStore.lastAlertRef = "$key|done"
        container.bandLink.update { it.copy(pendingAlert = null) }
        val relay = RelayAlert(
            alertId = alertId,
            detectedAtMs = alert.receivedAtMs,
            durationSec = null,
            confidence = null,
            battery = null,
            test = container.secureStore.bandTestMode,
        )
        relayAlert(relay, fromQueue = false)
    }

    // ------------------------------------------------------------------ backend

    private fun reportStatus(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastReportMs < STATUS_REPORT_MS) return
        lastReportMs = now
        scope.launch { sendStatus(connected = true) }
    }

    private suspend fun sendStatus(connected: Boolean) {
        val id = deviceId ?: return
        val st = container.bandLink.state.value
        val r = container.relayService.reportStatus(id, connected, st.status, st.rssi)
        container.bandLink.update {
            when (r) {
                is Outcome.Success -> it.copy(lastBackendReportMs = System.currentTimeMillis(), backendError = null)
                is Outcome.Failure -> it.copy(backendError = r.error.javaClass.simpleName)
            }
        }
    }

    private fun escalationSec(): Int =
        container.session.state.value.patient.valueOrNull?.detection?.escalationTimeoutSec ?: 60

    private suspend fun relayAlert(original: RelayAlert, fromQueue: Boolean) {
        val id = deviceId ?: return
        val relay = container.relayService
        val eventId = relay.eventIdFor(id, original.alertId)
        var a = original
        if (!fromQueue) {
            container.bandLink.update { it.copy(lastRelay = RelayOutcome(a.alertId, a.test, a.detectedAtMs)) }
            updateNotification(getString(if (a.test) R.string.band_link_test_alert else R.string.band_link_alert))
            // Where is the patient? Real phone fix, or null ("Location unavailable") — never guessed.
            if (a.location == null) {
                val loc = PhoneLocation.current(this, timeoutMs = 6_000)
                if (loc != null) a = a.copy(location = loc)
            }
            // Real alerts: text caregivers straight away from this phone's SIM. This path is free and
            // works even when this phone or the caregiver's phone has no internet.
            if (!a.test) {
                val rep = if (container.secureStore.smsFallbackEnabled) {
                    SmsFallback.send(this, container.secureStore.smsNumbers, smsText(eventId, test = false, escalated = false, location = a.location))
                } else {
                    SmsFallback.Report(SmsFallback.UNAVAILABLE, "SMS backup turned off", 0, 0)
                }
                a = a.copy(smsStatus = rep.status, smsDetail = rep.detail)
                container.bandLink.update {
                    it.copy(lastRelay = it.lastRelay?.copy(smsStatus = rep.status, smsDetail = rep.detail))
                }
            }
        }

        val pid = container.session.state.value.patientId ?: container.secureStore.patientId
        val r = if (pid == null) Outcome.Failure(AppError.NoPatient) else relay.reportAlert(id, pid, a, escalationSec())
        when (r) {
            is Outcome.Success -> {
                val res = r.value
                removeFromQueue(a.alertId)
                container.bandLink.update {
                    it.copy(
                        lastRelay = RelayOutcome(
                            a.alertId, a.test, a.detectedAtMs,
                            eventId = res.eventId, pushRecipients = res.recipients,
                            smsStatus = a.smsStatus, smsDetail = a.smsDetail,
                        ),
                        pendingAlerts = queueSize(),
                    )
                }
                updateNotification(getString(R.string.band_link_connected))
                if (res.created) {
                    val patient = container.session.state.value.patient.valueOrNull
                    EmergencyAlertService.start(this, res.eventId, patient?.id ?: pid, patient?.name, isTest = a.test)
                    container.sosInbox.requestOpen(res.eventId)
                    scheduleEscalation(res.eventId, a)
                    startLiveLocation(res.eventId, a.location)
                }
            }
            is Outcome.Failure -> {
                Log.w(TAG, "Alert relay failed: ${r.error.javaClass.simpleName}")
                if (r.error == AppError.PermissionDenied || r.error == AppError.NotAuthenticated) {
                    // This phone is no longer the band's relay; nothing a retry can fix.
                    removeFromQueue(a.alertId)
                    EmergencyNotifier.postGeneral(
                        this, a.alertId.hashCode(),
                        getString(R.string.band_alert_rejected_title), getString(R.string.band_alert_rejected_body),
                    )
                    return
                }
                addToQueue(a)
                container.bandLink.update {
                    it.copy(
                        lastRelay = RelayOutcome(
                            a.alertId, a.test, a.detectedAtMs, queuedOffline = true,
                            smsStatus = a.smsStatus, smsDetail = a.smsDetail,
                        ),
                        pendingAlerts = queueSize(),
                    )
                }
                if (!fromQueue) {
                    val body = when {
                        a.test -> getString(R.string.band_alert_offline_nosms, "TEST alerts never send SMS")
                        a.smsStatus == SmsFallback.SENT -> getString(R.string.band_alert_offline_sms, a.smsDetail ?: "")
                        else -> getString(R.string.band_alert_offline_nosms, a.smsDetail ?: "SMS failed")
                    }
                    EmergencyNotifier.postGeneral(this, a.alertId.hashCode(), getString(R.string.band_alert_offline_title), body)
                }
            }
        }
    }

    /** Without a server, this phone escalates its own unanswered alerts. */
    private fun scheduleEscalation(eventId: String, a: RelayAlert) {
        val waitMs = escalationSec() * 1000L
        scope.launch {
            delay(waitMs)
            val r = container.relayService.escalateIfOpen(eventId)
            if (r !is Outcome.Success || !r.value) return@launch
            // TEST alerts send one clearly marked [TEST] SMS here so the SMS path can be checked.
            // Real alerts already texted everyone; text again only if that first attempt failed.
            val needSms = a.test || a.smsStatus != SmsFallback.SENT
            if (!needSms) return@launch
            val rep = if (container.secureStore.smsFallbackEnabled) {
                SmsFallback.send(this@BandLinkService, container.secureStore.smsNumbers, smsText(eventId, a.test, escalated = true, location = liveLocations[eventId] ?: a.location))
            } else {
                SmsFallback.Report(SmsFallback.UNAVAILABLE, "SMS backup turned off", 0, 0)
            }
            container.relayService.reportSms(eventId, rep.status, rep.detail)
            container.bandLink.update {
                val last = it.lastRelay
                if (last?.eventId == eventId) it.copy(lastRelay = last.copy(smsStatus = rep.status, smsDetail = rep.detail)) else it
            }
        }
    }

    /**
     * While the alert is open, keep the event's location fresh (every 30 s, for up to 15 min) so caregivers
     * see where the patient actually is on the map.
     */
    private fun startLiveLocation(eventId: String, first: com.sahayat.app.data.model.EventLocation?) {
        if (!PhoneLocation.hasPermission(this)) return
        if (first != null) liveLocations[eventId] = first
        scope.launch {
            // Keeps updating after a caregiver acknowledges (they may be on the way); stops once resolved/cancelled.
            val until = System.currentTimeMillis() + LIVE_LOCATION_MS
            while (isActive && System.currentTimeMillis() < until) {
                delay(LIVE_LOCATION_EVERY_MS)
                val status = (container.relayService.eventStatus(eventId) as? Outcome.Success)?.value
                if (status != null && status !in setOf("ALERT_RECEIVED", "ACTIVE", "ESCALATED", "ACKNOWLEDGED")) break
                val loc = PhoneLocation.current(this@BandLinkService, timeoutMs = 10_000) ?: continue
                liveLocations[eventId] = loc
                container.relayService.updateLocation(eventId, loc)
            }
            liveLocations.remove(eventId)
        }
    }

    private fun smsText(
        eventId: String,
        test: Boolean,
        escalated: Boolean,
        location: com.sahayat.app.data.model.EventLocation? = liveLocations[eventId],
    ): String {
        val name = container.session.state.value.patient.valueOrNull?.name
            ?: container.secureStore.patientName
            ?: "the patient"
        val prefix = if (test) "[TEST] " else ""
        val wait = if (escalated) " Nobody has responded in the app yet." else ""
        // "SAHAYAT SOS" and "Ref #<id>" let the SAHAYAT app on a caregiver's phone raise the full alarm.
        val where = location?.let { " Location: ${PhoneLocation.mapsLink(it)}" } ?: " Location unavailable."
        return "${prefix}SAHAYAT SOS: possible seizure detected for $name.$wait Please check on them now.$where Ref #$eventId"
    }

    // ------------------------------------------------------------------ offline queue

    private fun readQueue(): MutableList<RelayAlert> =
        container.secureStore.pendingAlerts.orEmpty().lines().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching {
                val o = JSONObject(line)
                RelayAlert(
                    alertId = o.getString("id"),
                    detectedAtMs = o.getLong("at"),
                    durationSec = if (o.has("dur")) o.getInt("dur") else null,
                    confidence = if (o.has("conf")) o.getDouble("conf") else null,
                    battery = if (o.has("bat")) o.getInt("bat") else null,
                    test = o.optBoolean("test", false),
                    smsStatus = o.optString("sms").ifBlank { null },
                    smsDetail = o.optString("smsd").ifBlank { null },
                    location = if (o.has("lat") && o.has("lng")) {
                        com.sahayat.app.data.model.EventLocation(
                            lat = o.getDouble("lat"),
                            lng = o.getDouble("lng"),
                            accuracyM = if (o.has("acc")) o.getDouble("acc") else null,
                            source = o.optString("src").ifBlank { null },
                            capturedAtMs = if (o.has("lat_at")) o.getLong("lat_at") else null,
                        )
                    } else null,
                )
            }.getOrNull()
        }.toMutableList()

    private fun writeQueue(list: List<RelayAlert>) {
        container.secureStore.pendingAlerts = list.takeLast(20).joinToString("\n") { a ->
            JSONObject().apply {
                put("id", a.alertId)
                put("at", a.detectedAtMs)
                a.durationSec?.let { put("dur", it) }
                a.confidence?.let { put("conf", it) }
                a.battery?.let { put("bat", it) }
                put("test", a.test)
                a.smsStatus?.let { put("sms", it) }
                a.smsDetail?.let { put("smsd", it) }
                a.location?.let { l ->
                    put("lat", l.lat)
                    put("lng", l.lng)
                    l.accuracyM?.let { put("acc", it) }
                    l.source?.let { put("src", it) }
                    l.capturedAtMs?.let { put("lat_at", it) }
                }
            }.toString()
        }
    }

    private suspend fun addToQueue(a: RelayAlert) = queueLock.withLock {
        val q = readQueue()
        q.removeAll { it.alertId == a.alertId }
        q.add(a)
        writeQueue(q)
    }

    private suspend fun removeFromQueue(alertId: String) = queueLock.withLock {
        val q = readQueue()
        if (q.removeAll { it.alertId == alertId }) writeQueue(q)
    }

    private fun queueSize() = readQueue().size

    private suspend fun flushQueue() {
        val q = queueLock.withLock { readQueue() }
        for (a in q) {
            // Alerts older than a day are dropped; they would only confuse caregivers now.
            if (System.currentTimeMillis() - a.detectedAtMs > 24 * 3_600_000L) {
                removeFromQueue(a.alertId)
                continue
            }
            relayAlert(a, fromQueue = true)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private fun shutdown() {
        client?.release()
        client = null
        container.bandLink.update {
            it.copy(phase = if (it.paired) BandLinkPhase.STOPPED else BandLinkPhase.NOT_PAIRED, connectedSinceMs = null)
        }
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        client?.release()
        client = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SahayatLink"
        private const val NOTIF_ID = 0x5B001
        const val CHANNEL_LINK = "sahayat_band_link"
        private const val ACTION_START = "com.sahayat.app.band.START"
        private const val ACTION_STOP = "com.sahayat.app.band.STOP"
        private const val ACTION_SYNC = "com.sahayat.app.band.SYNC"
        private const val ACTION_COMMAND = "com.sahayat.app.band.COMMAND"
        private const val EXTRA_COMMAND = "command"
        const val CMD_START = "START"
        const val CMD_STOP = "STOP"
        const val CMD_PING = "PING"
        const val CMD_STATUS = "STATUS"
        /** Wait this long for the band's CANCELLED/ALERT_ACTIVE after SEIZURE (band window is 2 s). */
        private const val CONFIRM_MS = 4_000L
        private const val LIVE_LOCATION_MS = 15 * 60_000L
        private const val LIVE_LOCATION_EVERY_MS = 30_000L
        private const val STATUS_REPORT_MS = 55_000L
        private const val HEARTBEAT_MS = 60_000L
        private const val DISCONNECT_GRACE_MS = 30_000L

        fun ensureChannel(context: Context) {
            val ch = NotificationChannel(CHANNEL_LINK, "Band connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while this phone keeps the Bluetooth link to the SAHAYAT band."
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        /** Should this phone be running the relay? */
        suspend fun shouldRun(context: Context): Boolean {
            val c = (context.applicationContext as SahayatApp).container
            val role = c.settings.currentProfile().role
            return role == AppRole.PATIENT && c.secureStore.bandAddress != null && c.secureStore.uid != null
        }

        /** Starts (or re-attaches) the relay. Safe to call repeatedly. Returns false if it could not start. */
        fun start(context: Context): Boolean {
            val c = (context.applicationContext as SahayatApp).container
            if (c.secureStore.bandAddress == null) return false
            if (!BlePermissions.granted(context)) {
                c.bandLink.update { it.copy(phase = BandLinkPhase.NO_PERMISSION) }
                return false
            }
            return try {
                ContextCompat.startForegroundService(context, Intent(context, BandLinkService::class.java).setAction(ACTION_START))
                true
            } catch (e: Exception) {
                Log.w(TAG, "Relay start refused: ${e::class.java.simpleName}")
                false
            }
        }

        fun sync(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, BandLinkService::class.java).setAction(ACTION_SYNC))
            }
        }

        /** Sends START / STOP / PING / STATUS to the connected band. */
        fun sendCommand(context: Context, command: String) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, BandLinkService::class.java).setAction(ACTION_COMMAND).putExtra(EXTRA_COMMAND, command),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, BandLinkService::class.java)) }
            val c = (context.applicationContext as SahayatApp).container
            c.bandLink.update { it.copy(phase = if (it.paired) BandLinkPhase.STOPPED else BandLinkPhase.NOT_PAIRED, connectedSinceMs = null) }
        }
    }
}
