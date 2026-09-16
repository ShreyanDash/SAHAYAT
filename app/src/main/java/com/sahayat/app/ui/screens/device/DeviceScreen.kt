package com.sahayat.app.ui.screens.device

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sahayat.app.core.Format
import com.sahayat.app.core.Outcome
import com.sahayat.app.data.SessionRepository
import com.sahayat.app.data.service.DeviceService
import com.sahayat.app.ui.components.ActionState
import com.sahayat.app.ui.components.ActionStateBanner
import com.sahayat.app.ui.components.ButtonKind
import com.sahayat.app.ui.components.ConfirmDialog
import com.sahayat.app.ui.components.EmptyState
import com.sahayat.app.ui.components.ErrorState
import com.sahayat.app.ui.components.InfoLine
import com.sahayat.app.ui.components.LoadingBlock
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.SCard
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.SListRow
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.StatusPill
import com.sahayat.app.ui.components.Tone
import com.sahayat.app.ui.components.VSpace
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.sahayatViewModel
import com.sahayat.app.ui.screens.home.DeviceStatusViewModel
import com.sahayat.app.ui.screens.home.DeviceView
import com.sahayat.app.ui.theme.SType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class UnpairViewModel(private val devices: DeviceService, private val session: SessionRepository) : ViewModel() {
    private val _state = MutableStateFlow<ActionState>(ActionState.Idle)
    val state: StateFlow<ActionState> = _state

    fun unpair() {
        val s = session.state.value
        val did = s.deviceId ?: return
        val pid = s.patientId ?: return
        _state.value = ActionState.Busy
        viewModelScope.launch {
            _state.value = when (val r = devices.unpair(did, pid)) {
                is Outcome.Success -> ActionState.Done
                is Outcome.Failure -> ActionState.Failed(r.error)
            }
        }
    }

    fun clear() { _state.value = ActionState.Idle }
}

@Composable
fun DeviceScreen(onBack: () -> Unit, onDiagnostics: () -> Unit, onPair: () -> Unit) {
    val s = LocalStrings.current
    val vm = sahayatViewModel { DeviceStatusViewModel(it.session, it.deviceService, it.alertService, it.notificationService) }
    val unpairVm = sahayatViewModel { UnpairViewModel(it.deviceService, it.session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val unpairState by unpairVm.state.collectAsStateWithLifecycle()
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }

    ScreenScaffold(title = s.deviceTitle, onBack = onBack) {
        ActionStateBanner(action, s.syncRequested, vm::clearAction)
        ActionStateBanner(unpairState, s.done, unpairVm::clear)

        val status = when (val d = state.device) {
            is DeviceView.Connected -> d.status
            is DeviceView.Disconnected -> d.last
            else -> null
        }
        when (val d = state.device) {
            DeviceView.Loading -> LoadingBlock()
            is DeviceView.Unavailable -> ErrorState(s.error(d.error))
            DeviceView.NotPaired -> EmptyState(SIcons.Band, s.noDevicePaired, s.noDevicePairedBody, action = s.pairNow to onPair)
            is DeviceView.Connected, is DeviceView.Disconnected -> {
                val connected = d is DeviceView.Connected
                SCard {
                    Text(s.pairedDevice, style = SType.CardTitle)
                    VSpace(6)
                    StatusPill(if (connected) s.connected else s.disconnected, if (connected) Tone.Ok else Tone.Warn)
                    VSpace(6)
                    InfoLine(s.deviceIdLabel, state.session.deviceId)
                    InfoLine(s.monitoringStatus, if (connected) status?.let { s.monitoring(it.monitoring) } else s.notConnected)
                    InfoLine(s.battery, if (connected) status?.batteryPercent?.let { "$it%" } else null)
                    InfoLine(s.wifi, if (connected) status?.wifiSsid else null)
                    InfoLine(s.signal, if (connected) status?.wifiRssi?.let { "$it dBm" } else null)
                    InfoLine(s.firmware, status?.firmware)
                    InfoLine(s.hardware, status?.hardwareRev)
                    InfoLine(s.lastCommunication, status?.lastSeenMs?.let { Format.dateTime(it) })
                }
                SButton(
                    s.requestSync, vm::reconnect, Modifier.fillMaxWidth(),
                    icon = SIcons.Refresh, loading = action is ActionState.Busy,
                )
                SListRow(s.diagnostics, icon = SIcons.Board, onClick = onDiagnostics)
                SListRow(s.pairingTitle, icon = SIcons.Qr, onClick = onPair)
                SButton(
                    s.unpair, { confirmUnpair = true }, Modifier.fillMaxWidth(),
                    kind = ButtonKind.Danger, icon = SIcons.Unlink, loading = unpairState is ActionState.Busy,
                )
            }
        }
    }

    if (confirmUnpair) {
        ConfirmDialog(
            title = s.unpair,
            text = s.unpairConfirm,
            confirmLabel = s.unpair,
            danger = true,
            onConfirm = { confirmUnpair = false; unpairVm.unpair() },
            onDismiss = { confirmUnpair = false },
        )
    }
}
