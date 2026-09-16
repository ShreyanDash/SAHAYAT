package com.sahayat.app.ui.screens.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Outcome
import com.sahayat.app.data.SessionRepository
import com.sahayat.app.data.service.DeviceService
import com.sahayat.app.data.service.PairingCredentials
import com.sahayat.app.data.service.PairingResult
import com.sahayat.app.data.service.SettingsRepository
import com.sahayat.app.ui.LocalContainer
import com.sahayat.app.ui.components.Banner
import com.sahayat.app.ui.components.ButtonKind
import com.sahayat.app.ui.components.EmptyState
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.SCard
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.SSegmented
import com.sahayat.app.ui.components.STextField
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.Tone
import com.sahayat.app.ui.components.VSpace
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.sahayatViewModel
import com.sahayat.app.ui.theme.SColors
import com.sahayat.app.ui.theme.SShapes
import com.sahayat.app.ui.theme.SType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface PairingUiState {
    data object Idle : PairingUiState
    data class Pairing(val deviceId: String) : PairingUiState
    data class Success(val result: PairingResult) : PairingUiState
    data class Failed(val error: AppError) : PairingUiState
}

class PairingViewModel(
    private val devices: DeviceService,
    private val session: SessionRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val state: StateFlow<PairingUiState> = _state

    fun pair(credentials: PairingCredentials) {
        if (_state.value is PairingUiState.Pairing) return
        val pid = session.state.value.patientId
        if (pid == null) {
            _state.value = PairingUiState.Failed(
                if (session.state.value.uid == null) AppError.NotAuthenticated else AppError.NoPatient,
            )
            return
        }
        _state.value = PairingUiState.Pairing(credentials.deviceId)
        viewModelScope.launch {
            _state.value = when (val r = devices.pair(credentials, pid)) {
                is Outcome.Success -> PairingUiState.Success(r.value)
                is Outcome.Failure -> PairingUiState.Failed(r.error)
            }
        }
    }

    fun reportInvalidQr() { _state.value = PairingUiState.Failed(AppError.InvalidDeviceId) }
    fun reset() { _state.value = PairingUiState.Idle }
}

private enum class PairMode { QR, MANUAL }

@Composable
fun DevicePairingScreen(onboarding: Boolean, onBack: () -> Unit, onFinished: () -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val vm = sahayatViewModel { PairingViewModel(it.deviceService, it.session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val session by container.session.state.collectAsStateWithLifecycle()

    var mode by rememberSaveable { mutableStateOf(PairMode.QR) }
    var deviceId by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var cameraError by remember { mutableStateOf(false) }
    var hasCamera by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCamera = it }

    val finish: () -> Unit = {
        scope.launch {
            if (onboarding) container.settings.setOnboardingComplete(true)
            onFinished()
        }
    }

    ScreenScaffold(
        title = s.pairingTitle,
        subtitle = s.pairingSubtitle,
        onBack = if (onboarding) null else onBack,
        bottom = {
            if (onboarding && state !is PairingUiState.Success) {
                SButton(s.pairLater, finish, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
            }
        },
    ) {
        if (session.patientId == null && !session.isPreview && session.uid != null) {
            Banner(s.needPatientFirst, Tone.Warn)
        }

        when (val st = state) {
            is PairingUiState.Success -> {
                SCard {
                    EmptyState(
                        icon = if (st.result.deviceOnline) SIcons.Check else SIcons.WifiOff,
                        title = s.pairSuccessTitle,
                        body = if (st.result.deviceOnline) s.pairSuccessBody else s.pairSuccessOffline,
                    )
                    Text("${s.deviceIdLabel}: ${st.result.deviceId}", style = SType.Caption, modifier = Modifier.align(Alignment.CenterHorizontally))
                    VSpace(12)
                    SButton(if (onboarding) s.continueToDashboard else s.done, finish, Modifier.fillMaxWidth())
                }
            }
            is PairingUiState.Failed -> Banner(
                text = if (st.error == AppError.InvalidDeviceId && mode == PairMode.QR) s.invalidQr else s.error(st.error),
                tone = Tone.Danger,
                action = s.retry to vm::reset,
            )
            else -> Unit
        }

        if (state !is PairingUiState.Success) {
            SSegmented(
                options = listOf(PairMode.QR to s.scanQr, PairMode.MANUAL to s.manualEntry),
                selected = mode,
                onSelect = { mode = it; vm.reset() },
            )

            val pairing = state is PairingUiState.Pairing
            when (mode) {
                PairMode.QR -> {
                    when {
                        !hasCamera -> SCard {
                            EmptyState(SIcons.Qr, s.scanQr, s.cameraPermissionNeeded, action = s.grantCamera to {
                                cameraLauncher.launch(Manifest.permission.CAMERA)
                            })
                        }
                        cameraError -> Banner(s.unavailable, Tone.Warn, action = s.manualEntry to { mode = PairMode.MANUAL })
                        else -> {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(SShapes.Hero)
                                    .border(BorderStroke(2.dp, SColors.Accent), SShapes.Hero),
                            ) {
                                QrScanner(
                                    modifier = Modifier.fillMaxSize(),
                                    paused = pairing || state is PairingUiState.Failed,
                                    onResult = { raw ->
                                        val creds = DeviceService.parseQr(raw)
                                        if (creds == null) vm.reportInvalidQr() else vm.pair(creds)
                                    },
                                    onError = { cameraError = true },
                                )
                                if (pairing) {
                                    Banner(s.pairing, Tone.Neutral, Modifier.align(Alignment.BottomCenter).padding(12.dp))
                                }
                            }
                            Text(s.pointCamera, style = SType.Caption, modifier = Modifier.align(Alignment.CenterHorizontally))
                        }
                    }
                }
                PairMode.MANUAL -> {
                    val normalized = DeviceService.normalizeId(deviceId)
                    val idErr = if (deviceId.isNotBlank() && !DeviceService.isValidDeviceId(normalized)) s.errInvalidDeviceId else null
                    val codeErr = if (code.isNotBlank() && !DeviceService.isValidCode(code)) s.errInvalidPairingCode else null
                    STextField(deviceId, { deviceId = it.uppercase().take(24) }, s.deviceId, hint = s.deviceIdHint, error = idErr, enabled = !pairing)
                    STextField(
                        code, { v -> code = v.filter { it.isDigit() }.take(6) }, s.pairingCode,
                        hint = s.pairingCodeHint, keyboardType = KeyboardType.NumberPassword, error = codeErr, enabled = !pairing,
                    )
                    SButton(
                        if (pairing) s.pairing else s.pairDevice,
                        { vm.pair(PairingCredentials(normalized, code)) },
                        Modifier.fillMaxWidth(),
                        loading = pairing,
                        icon = SIcons.Band,
                        enabled = DeviceService.isValidDeviceId(normalized) && DeviceService.isValidCode(code),
                    )
                }
            }
        }
    }
}
