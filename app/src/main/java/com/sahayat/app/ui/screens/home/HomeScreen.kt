package com.sahayat.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.sahayat.app.emergency.AlertWatchService
import androidx.compose.ui.platform.LocalContext
import com.sahayat.app.ble.BandLinkPhase
import com.sahayat.app.ble.BandLinkService
import com.sahayat.app.ble.BandLinkState
import com.sahayat.app.ble.BandStatusKind
import com.sahayat.app.ui.components.SSwitchRow
import com.sahayat.app.core.Format
import com.sahayat.app.data.service.AppRole
import com.sahayat.app.ui.components.color
import com.sahayat.app.ui.screens.settings.bandLinkLabel
import com.sahayat.app.core.Remote
import com.sahayat.app.core.valueOrNull
import com.sahayat.app.data.model.DeviceStatus
import com.sahayat.app.data.model.MonitoringState
import com.sahayat.app.data.model.SeizureEvent
import com.sahayat.app.data.service.AlertReadiness
import com.sahayat.app.ui.components.ActionState
import com.sahayat.app.ui.components.ActionStateBanner
import com.sahayat.app.ui.components.Banner
import com.sahayat.app.ui.components.ButtonKind
import com.sahayat.app.ui.components.EmptyState
import com.sahayat.app.ui.components.ErrorState
import com.sahayat.app.ui.components.HSpace
import com.sahayat.app.ui.components.IconBadge
import com.sahayat.app.ui.components.InfoLine
import com.sahayat.app.ui.components.LoadingBlock
import com.sahayat.app.ui.components.MetricTile
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.SCard
import com.sahayat.app.ui.components.SIconButton
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.SListRow
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.SectionLabel
import com.sahayat.app.ui.components.StatusPill
import com.sahayat.app.ui.components.Tone
import com.sahayat.app.ui.components.VSpace
import com.sahayat.app.ui.components.tint
import com.sahayat.app.ui.i18n.AppStrings
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.navigation.Routes
import com.sahayat.app.ui.sahayatViewModel
import com.sahayat.app.ui.theme.SColors
import com.sahayat.app.ui.theme.SType

fun batteryTone(p: Int?): Tone = when {
    p == null -> Tone.Neutral
    p <= 15 -> Tone.Danger
    p <= 30 -> Tone.Warn
    else -> Tone.Ok
}

fun monitoringTone(m: MonitoringState): Tone = when (m) {
    MonitoringState.ACTIVE -> Tone.Ok
    MonitoringState.IDLE, MonitoringState.PAUSED -> Tone.Warn
    MonitoringState.UNKNOWN -> Tone.Neutral
}

@Composable
fun HomeScreen(navigate: (String) -> Unit, onOpenTab: (com.sahayat.app.ui.navigation.MainTab) -> Unit) {
    val s = LocalStrings.current
    val vm = sahayatViewModel { DeviceStatusViewModel(it.session, it.deviceService, it.alertService, it.notificationService) }
    val state by vm.state.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val patient = state.session.patient.valueOrNull
    val container = com.sahayat.app.ui.LocalContainer.current
    val local by container.settings.profile.collectAsStateWithLifecycle(null)
    // Name the user typed during setup; shown only until the backend patient profile is available.
    val patientName = patient?.name ?: local?.patientName?.ifBlank { null }

    ScreenScaffold(
        title = s.dashboard,
        eyebrow = s.homeEyebrow,
        subtitle = patientName?.let { "${s.patient}: $it" },
        
    ) {
        state.openEvents.firstOrNull()?.let { ActiveAlertCard(it, s) { navigate(Routes.sos(it.id)) } }

        if (local?.role == AppRole.CAREGIVER && !state.session.isPreview) CaregiverPathsCard(s)

        if (local?.role == AppRole.PATIENT && !state.session.isPreview) {
            com.sahayat.app.ui.components.LocationPermissionCard()
        }

        if (local?.role == AppRole.PATIENT) {
            val link by container.bandLink.state.collectAsStateWithLifecycle()
            val context = LocalContext.current
            BandLinkCard(
                link, s,
                onStart = {
                    if (!BandLinkService.start(context)) navigate(Routes.pairing(onboarding = false))
                },
                onOpen = { navigate(Routes.pairing(onboarding = false)) },
                onOpenAlert = { id -> navigate(Routes.sos(id)) },
                onCommand = { cmd -> BandLinkService.sendCommand(context, cmd) },
                onTestMode = { container.bandLink.setTestMode(it) },
            )
        }

        ActionStateBanner(action, doneText = s.syncRequested, onDismiss = vm::clearAction)

        when (val d = state.device) {
            DeviceView.Loading -> LoadingBlock()
            is DeviceView.Unavailable -> SCard { ErrorState(s.error(d.error)) }
            DeviceView.NotPaired -> SCard {
                EmptyState(SIcons.Band, s.noDevicePaired, s.noDevicePairedBody, action = s.pairNow to { navigate(Routes.pairing(false)) })
            }
            is DeviceView.Disconnected -> {
                DisconnectedCard(d, s, state.now, onReconnect = vm::reconnect, onDiagnostics = { navigate(Routes.DIAGNOSTICS) }, busy = action is ActionState.Busy)
                MetricsGrid(null, lastSeen = d.last?.lastSeenMs, now = state.now, onClick = { navigate(Routes.DEVICE) })
            }
            is DeviceView.Connected -> {
                ConnectedCard(d.status, s, onClick = { onOpenTab(com.sahayat.app.ui.navigation.MainTab.MONITOR) })
                MetricsGrid(d.status, lastSeen = d.status.lastSeenMs, now = state.now, onClick = { navigate(Routes.DEVICE) })
            }
        }

        SCard {
            Text(s.patient, style = SType.CardTitle)
            VSpace(4)
            InfoLine(s.patient, patientName)
            if (patient == null) local?.patientAge?.let { InfoLine(s.age, it.toString()) }
            InfoLine(s.deviceIdLabel, state.session.deviceId ?: if (patient != null) s.noDevicePaired else null)
        }

        state.readiness?.let { ReadinessRow(it, s) { navigate(Routes.NOTIFICATION_SETTINGS) } }

        SectionLabel(s.quickActions)
        SListRow(s.liveMonitoring, icon = SIcons.Monitor, onClick = { onOpenTab(com.sahayat.app.ui.navigation.MainTab.MONITOR) })
        SListRow(s.deviceTitle, icon = SIcons.Band, onClick = { navigate(Routes.DEVICE) })
        SListRow(s.historyTitle, icon = SIcons.History, onClick = { onOpenTab(com.sahayat.app.ui.navigation.MainTab.HISTORY) })
        SListRow(s.exportLogs, icon = SIcons.Pdf, onClick = { navigate(Routes.EXPORT) })
    }
}

/** Shows, honestly, which of this caregiver phone's two alert paths are ready. */
@Composable
private fun CaregiverPathsCard(s: AppStrings) {
    val context = LocalContext.current
    val running by AlertWatchService.running.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val smsOk = remember(tick) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    }
    val batteryOk = remember(tick) {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    SCard {
        Text(s.caregiverReadiness, style = SType.CardTitle)
        VSpace(10)
        PathRow(if (running) s.listenerRunning else s.listenerStopped, running, if (running) null else (s.start to { AlertWatchService.start(context) }))
        PathRow(if (smsOk) s.smsAlarmOn else s.smsAlarmOff, smsOk, if (smsOk) null else (s.allow to { smsLauncher.launch(Manifest.permission.RECEIVE_SMS) }))
        PathRow(
            if (batteryOk) s.batteryUnrestricted else s.batteryRestricted, batteryOk,
            if (batteryOk) null else (s.allow to {
                @android.annotation.SuppressLint("BatteryLife")
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
                runCatching { context.startActivity(i) }
                Unit
            }),
        )
    }
}

@Composable
private fun PathRow(text: String, ok: Boolean, action: Pair<String, () -> Unit>?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        StatusPill(if (ok) "OK" else "!", if (ok) Tone.Ok else Tone.Warn)
        HSpace(10)
        Text(text, style = SType.Body, modifier = Modifier.weight(1f))
        if (action != null) {
            HSpace(8)
            SButton(action.first, action.second, kind = ButtonKind.Secondary)
        }
    }
    VSpace(8)
}

@Composable
private fun BandLinkCard(
    link: BandLinkState,
    s: AppStrings,
    onStart: () -> Unit,
    onOpen: () -> Unit,
    onOpenAlert: (String) -> Unit,
    onCommand: (String) -> Unit,
    onTestMode: (Boolean) -> Unit,
) {
    val tone = when (link.phase) {
        BandLinkPhase.CONNECTED -> Tone.Ok
        BandLinkPhase.CONNECTING, BandLinkPhase.DISCONNECTED -> Tone.Warn
        BandLinkPhase.NOT_PAIRED, BandLinkPhase.STOPPED -> Tone.Neutral
        BandLinkPhase.BLUETOOTH_OFF, BandLinkPhase.NO_PERMISSION, BandLinkPhase.INCOMPATIBLE -> Tone.Danger
    }
    SCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(
                when (link.phase) {
                    BandLinkPhase.CONNECTED -> SIcons.BluetoothConnected
                    BandLinkPhase.BLUETOOTH_OFF, BandLinkPhase.NO_PERMISSION, BandLinkPhase.INCOMPATIBLE -> SIcons.BluetoothOff
                    BandLinkPhase.CONNECTING, BandLinkPhase.DISCONNECTED -> SIcons.BluetoothSearching
                    else -> SIcons.Bluetooth
                },
                tone.color(),
                48,
            )
            HSpace(14)
            Column(Modifier.weight(1f)) {
                Text(s.bandLink, style = SType.CardTitle, color = tint(SColors.TextPrimary))
                Text(
                    listOfNotNull(link.name, link.deviceId, bandLinkLabel(s, link.phase)).joinToString(" · "),
                    style = SType.Caption, color = tint(SColors.TextSecondary),
                )
            }
        }
        link.error?.takeIf { link.phase == BandLinkPhase.INCOMPATIBLE || !link.connected }?.let {
            VSpace(8)
            Text(s.bandLinkError(it), style = SType.Caption, color = SColors.Danger)
        }
        if (link.pendingAlert != null) {
            VSpace(8)
            Banner(s.alertPendingOnBand, Tone.Warn, icon = SIcons.Alert)
        }
        if (link.connected) {
            val st = link.status
            VSpace(10)
            InfoLine(
                s.bandStateLabel,
                when (st?.kind) {
                    BandStatusKind.MONITORING -> s.bandMonitoring
                    BandStatusKind.STOPPED -> s.bandStopped
                    BandStatusKind.SENSOR_ERROR -> s.bandSensorError
                    else -> s.bandStateUnknown
                },
                valueColor = when (st?.kind) {
                    BandStatusKind.MONITORING -> SColors.Accent
                    BandStatusKind.SENSOR_ERROR -> SColors.Danger
                    BandStatusKind.STOPPED -> SColors.Warning
                    else -> SColors.TextSecondary
                },
            )
            InfoLine(s.lastPing, link.lastPingOkMs?.let { Format.relative(it) })
            InfoLine(s.bandSignal, link.rssi?.let { "${Format.signalBars(it)}/4 ($it dBm)" })
            InfoLine(s.battery, null)
            link.lastAlert?.let { InfoLine(s.lastBandMessage, it.raw) }
            VSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (st?.kind == BandStatusKind.STOPPED) {
                    SButton(s.resumeMonitoring, { onCommand(BandLinkService.CMD_START) }, Modifier.weight(1f))
                } else {
                    SButton(s.pauseMonitoring, { onCommand(BandLinkService.CMD_STOP) }, Modifier.weight(1f), kind = ButtonKind.Secondary)
                }
                SButton(s.pingBand, { onCommand(BandLinkService.CMD_PING) }, Modifier.weight(1f), kind = ButtonKind.Secondary)
            }
        }
        link.lastRelay?.let { r ->
            VSpace(10)
            val what = when {
                r.eventId != null && r.pushRecipients != null -> s.relayedTo(r.pushRecipients)
                r.queuedOffline -> s.relayQueued
                else -> s.loading
            }
            InfoLine(
                s.lastRelay + if (r.isTest) " (${s.testLabel})" else "",
                "${Format.relative(r.atMs)} · $what",
                valueColor = if (r.queuedOffline) SColors.Warning else SColors.TextPrimary,
            )
            r.smsStatus?.let { InfoLine(s.backupSms, listOfNotNull(it, r.smsDetail).joinToString(" · ")) }
            r.eventId?.let { id ->
                VSpace(8)
                SButton(s.openAlert, { onOpenAlert(id) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
            }
        }
        if (link.pendingAlerts > 0) InfoLine(s.alertsWaiting, link.pendingAlerts.toString(), valueColor = SColors.Warning)
        if (link.paired) {
            VSpace(10)
            SSwitchRow(s.testModeTitle, link.testMode, onTestMode, subtitle = s.testModeBody, icon = SIcons.Test)
        }
        if (link.phase == BandLinkPhase.STOPPED || link.phase == BandLinkPhase.NO_PERMISSION) {
            VSpace(12)
            SButton(s.startRelay, onStart, Modifier.fillMaxWidth(), icon = SIcons.Bluetooth)
        } else if (link.phase == BandLinkPhase.NOT_PAIRED) {
            VSpace(12)
            SButton(s.pairNow, onOpen, Modifier.fillMaxWidth(), icon = SIcons.Bluetooth)
        }
    }
}

@Composable
private fun ActiveAlertCard(e: SeizureEvent, s: AppStrings, onOpen: () -> Unit) {
    SCard(onClick = onOpen, color = SColors.DangerMuted) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(SIcons.Alert, SColors.Danger, 44)
            HSpace(12)
            Column(Modifier.weight(1f)) {
                Text(s.activeAlert, style = SType.CardTitle, color = tint(SColors.TextPrimary))
                Text("${s.sosStatus(e.status)} · ${Format.relative(e.detectedAtMs)}", style = SType.Caption, color = tint(SColors.TextSecondary))
            }
            Text(s.openAlert, style = SType.Button, color = tint(SColors.Danger))
        }
    }
}

@Composable
private fun DisconnectedCard(
    d: DeviceView.Disconnected,
    s: AppStrings,
    now: Long,
    onReconnect: () -> Unit,
    onDiagnostics: () -> Unit,
    busy: Boolean,
) {
    SCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(SIcons.WifiOff, SColors.Warning, 48)
            HSpace(14)
            Column(Modifier.weight(1f)) {
                Text(s.deviceNotConnected, style = SType.CardTitle)
                StatusPill(s.disconnected, Tone.Warn)
            }
        }
        VSpace(12)
        Text(d.error?.let { s.error(it) } ?: s.deviceNotConnectedBody, style = SType.Body)
        d.last?.lastSeenMs?.let {
            VSpace(4)
            Text("${s.lastHeartbeat}: ${Format.relative(it, now)}", style = SType.Caption)
        }
        VSpace(14)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SButton(s.reconnect, onReconnect, Modifier.weight(1f), icon = SIcons.Refresh, loading = busy)
            SButton(s.diagnostics, onDiagnostics, Modifier.weight(1f), kind = ButtonKind.Secondary)
        }
    }
}

@Composable
private fun ConnectedCard(st: DeviceStatus, s: AppStrings, onClick: () -> Unit) {
    SCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(SIcons.Monitor, if (st.monitoring == MonitoringState.ACTIVE) SColors.Accent else SColors.Warning, 48)
            HSpace(14)
            Column(Modifier.weight(1f)) {
                Text(s.monitoring(st.monitoring), style = SType.CardTitle, color = tint(SColors.TextPrimary))
                Text(s.monitoringStatus, style = SType.Caption, color = tint(SColors.TextSecondary))
            }
            StatusPill(s.connected, Tone.Ok)
        }
    }
}

@Composable
private fun MetricsGrid(st: DeviceStatus?, lastSeen: Long?, now: Long, onClick: () -> Unit) {
    val s = LocalStrings.current
    val battery = st?.batteryPercent
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        MetricTile(
            icon = when {
                battery == null -> SIcons.BatteryUnknown
                st?.charging == true -> SIcons.BatteryCharging
                battery <= 15 -> SIcons.BatteryLow
                else -> SIcons.Battery
            },
            label = s.battery + if (st?.charging == true) " · ${s.charging}" else "",
            value = st?.batteryPercent?.let { "$it%" },
            tone = batteryTone(st?.batteryPercent),
            modifier = Modifier.weight(1f),
            onClick = onClick,
        )
        MetricTile(
            icon = SIcons.Signal,
            label = s.signal,
            value = st?.wifiRssi?.let { "${Format.signalBars(it)}/4" },
            tone = st?.wifiRssi?.let { if (Format.signalBars(it) <= 1) Tone.Warn else Tone.Ok } ?: Tone.Neutral,
            modifier = Modifier.weight(1f),
            onClick = onClick,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        MetricTile(
            icon = SIcons.Sync,
            label = s.lastSync,
            value = lastSeen?.let { Format.relative(it, now) },
            modifier = Modifier.weight(1f),
            onClick = onClick,
        )
        MetricTile(
            icon = if (st != null) SIcons.Wifi else SIcons.WifiOff,
            label = s.deviceConnection,
            value = if (st != null) s.connected else s.notConnected,
            tone = if (st != null) Tone.Ok else Tone.Warn,
            modifier = Modifier.weight(1f),
            onClick = onClick,
        )
    }
}

@Composable
private fun ReadinessRow(r: AlertReadiness, s: AppStrings, onFix: () -> Unit) {
    val ready = r.backendConfigured && r.playServicesAvailable && r.notificationsAllowed &&
        r.emergencyChannelEnabled && r.fullScreenIntentAllowed && r.tokenRegistered
    if (ready) {
        Banner(s.alertsReady, Tone.Ok, icon = SIcons.Notifications)
    } else {
        Banner(s.alertsNotReady, Tone.Warn, icon = SIcons.Notifications, action = s.fixNow to onFix)
    }
}
