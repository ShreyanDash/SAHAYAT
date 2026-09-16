package com.sahayat.app.ui.screens.monitoring

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahayat.app.core.Format
import com.sahayat.app.core.Remote
import com.sahayat.app.core.valueOrNull
import com.sahayat.app.data.model.MonitoringState
import com.sahayat.app.ui.components.ActionStateBanner
import com.sahayat.app.ui.components.ActionState
import com.sahayat.app.ui.components.ButtonKind
import com.sahayat.app.ui.components.color
import com.sahayat.app.ui.components.InfoLine
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.SCard
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.SensorRing
import com.sahayat.app.ui.components.StatusPill
import com.sahayat.app.ui.components.Tone
import com.sahayat.app.ui.components.VSpace
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.navigation.Routes
import com.sahayat.app.ui.sahayatViewModel
import com.sahayat.app.ui.screens.home.DeviceStatusViewModel
import com.sahayat.app.ui.screens.home.DeviceView
import com.sahayat.app.ui.screens.home.batteryTone
import com.sahayat.app.ui.screens.home.monitoringTone
import com.sahayat.app.ui.theme.SType

@Composable
fun LiveMonitoringScreen(navigate: (String) -> Unit) {
    val s = LocalStrings.current
    val vm = sahayatViewModel { DeviceStatusViewModel(it.session, it.deviceService, it.alertService, it.notificationService) }
    val state by vm.state.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val connected = state.device as? DeviceView.Connected
    val status = connected?.status
    // "Active" is shown only when the band itself reports ACTIVE on a fresh heartbeat.
    val active = status?.monitoring == MonitoringState.ACTIVE
    val battery = status?.batteryPercent

    val (headline, body) = when {
        status == null -> s.waitingForDevice to when (val d = state.device) {
            is DeviceView.Unavailable -> s.error(d.error)
            DeviceView.NotPaired -> s.noDevicePairedBody
            else -> s.waitingForDeviceBody
        }
        active -> s.monitoring(MonitoringState.ACTIVE) to s.monitoringActiveBody
        else -> s.monitoring(status.monitoring) to s.monitoringIdleBody
    }

    ScreenScaffold(title = s.liveMonitoring) {
        ActionStateBanner(action, s.syncRequested, vm::clearAction)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            VSpace(8)
            SensorRing(live = active) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        battery?.let { "$it%" } ?: "—",
                        style = SType.TitleLarge,
                    )
                    Text(s.battery, style = SType.Caption)
                }
            }
            VSpace(16)
            Text(headline, style = SType.Title, textAlign = TextAlign.Center)
            VSpace(4)
            Text(body, style = SType.Body, textAlign = TextAlign.Center)
            VSpace(10)
            StatusPill(
                if (status != null) s.connected else s.notConnected,
                if (status != null) monitoringTone(status.monitoring) else Tone.Neutral,
            )
            VSpace(4)
            Text(s.ringVisualNote, style = SType.Caption, textAlign = TextAlign.Center)
        }

        SCard {
            Text(s.deviceTitle, style = SType.CardTitle)
            VSpace(4)
            InfoLine(s.patient, state.session.patient.valueOrNull?.name)
            InfoLine(s.deviceIdLabel, state.session.deviceId)
            InfoLine(s.monitoringStatus, status?.let { s.monitoring(it.monitoring) })
            InfoLine(s.motionSensor, status?.let { s.health(it.mpuHealth) })
            InfoLine(s.battery, status?.batteryPercent?.let { "$it%" }, valueColor = batteryTone(status?.batteryPercent).color())
            InfoLine(s.signal, status?.wifiRssi?.let { "${Format.signalBars(it)}/4 · $it dBm" })
            val lastSeen = status?.lastSeenMs
                ?: (state.device as? DeviceView.Disconnected)?.last?.lastSeenMs
            InfoLine(s.lastHeartbeat, lastSeen?.let { Format.relative(it, state.now) })
        }

        if (state.session.deviceId != null && status == null) {
            SButton(s.reconnect, vm::reconnect, Modifier.fillMaxWidth(), icon = SIcons.Refresh, loading = action is ActionState.Busy)
        }
        if (state.device == DeviceView.NotPaired) {
            SButton(s.pairNow, { navigate(Routes.pairing(false)) }, Modifier.fillMaxWidth(), icon = SIcons.Band)
        }
        SButton(s.diagnostics, { navigate(Routes.DIAGNOSTICS) }, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
    }
}
