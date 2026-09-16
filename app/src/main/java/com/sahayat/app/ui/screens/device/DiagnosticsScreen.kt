package com.sahayat.app.ui.screens.device

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahayat.app.core.Format
import com.sahayat.app.data.model.ComponentHealth
import com.sahayat.app.data.model.DeviceStatus
import com.sahayat.app.ui.components.SCard
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.SListRow
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.SectionLabel
import com.sahayat.app.ui.components.InfoLine
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.ActionStateBanner
import com.sahayat.app.ui.components.ActionState
import com.sahayat.app.ui.components.VSpace
import com.sahayat.app.ui.i18n.AppStrings
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.sahayatViewModel
import com.sahayat.app.ui.screens.home.DeviceStatusViewModel
import com.sahayat.app.ui.screens.home.DeviceView
import com.sahayat.app.ui.theme.SColors
import com.sahayat.app.ui.theme.SType
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier

private fun healthColor(h: ComponentHealth): Color = when (h) {
    ComponentHealth.OK -> SColors.Accent
    ComponentHealth.DEGRADED -> SColors.Warning
    ComponentHealth.FAULT -> SColors.Danger
    ComponentHealth.NOT_PRESENT, ComponentHealth.UNKNOWN -> SColors.TextTertiary
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit, onNotificationSettings: () -> Unit) {
    val s = LocalStrings.current
    val vm = sahayatViewModel { DeviceStatusViewModel(it.session, it.deviceService, it.alertService, it.notificationService) }
    val state by vm.state.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()

    // Only a fresh report counts as "live"; a stale one is shown as Not Connected for live values.
    val live: DeviceStatus? = (state.device as? DeviceView.Connected)?.status
    val last: DeviceStatus? = live ?: (state.device as? DeviceView.Disconnected)?.last

    ScreenScaffold(title = s.diagnosticsTitle, onBack = onBack) {
        Text(s.diagnosticsNote, style = SType.Caption)
        ActionStateBanner(action, s.syncRequested, vm::clearAction)

        SectionLabel(s.wearable)
        SCard {
            ComponentLine(s, s.diagPico, live?.picoHealth)
            ComponentLine(s, s.diagMpu, live?.mpuHealth)
            ComponentLine(s, s.diagSim, live?.simHealth)
            InfoLine(s.network, live?.let { st -> listOfNotNull(st.simNetwork, st.simSignal?.let { "CSQ $it" }).joinToString(" · ").ifBlank { null } })
            InfoLine(s.wifi, live?.let { st -> listOfNotNull(st.wifiSsid, st.wifiRssi?.let { "$it dBm" }).joinToString(" · ").ifBlank { null } })
            InfoLine(s.battery, live?.batteryPercent?.let { "$it%" + if (live?.charging == true) " · ${s.charging}" else "" })
            InfoLine(s.signal, live?.wifiRssi?.let { "${Format.signalBars(it)}/4" })
            InfoLine(s.firmware, last?.firmware)
            InfoLine(s.uptime, live?.uptimeSec?.let { Format.duration(it) })
            InfoLine(s.freeMemory, live?.freeMemoryKb?.let { "$it KB" })
            InfoLine(s.lastCommunication, last?.lastSeenMs?.let { Format.relative(it, state.now) } ?: if (state.session.deviceId == null) s.noDevicePaired else null)
        }
        if (state.session.deviceId != null) {
            SButton(s.requestSync, vm::reconnect, Modifier.fillMaxWidth(), icon = SIcons.Refresh, loading = action is ActionState.Busy)
        }

        SectionLabel(s.thisPhone)
        val r = state.readiness
        SCard {
            InfoLine(s.internet, if (com.sahayat.app.ui.components.LocalGlobalStatus.current.online) s.online else s.offline)
            InfoLine(s.backend, if (r?.backendConfigured == true) s.configured else s.notConfigured)
            InfoLine(s.pushMessaging, when {
                r == null -> null
                !r.playServicesAvailable -> s.unavailable
                r.tokenRegistered -> s.registered
                else -> s.notRegistered
            })
            InfoLine(s.notificationPermission, when (r?.notificationsAllowed) {
                true -> s.allowed
                false -> s.notAllowed
                null -> null
            })
            InfoLine(s.fullScreenAlerts, when (r?.fullScreenIntentAllowed) {
                true -> s.allowed
                false -> s.notAllowed
                null -> null
            })
        }
        SListRow(s.notifications, icon = SIcons.Notifications, onClick = onNotificationSettings)
        VSpace(4)
    }
}

@Composable
private fun ComponentLine(s: AppStrings, label: String, health: ComponentHealth?) {
    if (health == null) {
        InfoLine(label, s.notConnected, SColors.TextTertiary)
    } else {
        InfoLine(label, s.health(health), healthColor(health))
    }
}
