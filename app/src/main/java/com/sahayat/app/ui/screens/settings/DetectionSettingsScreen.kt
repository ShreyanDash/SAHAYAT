package com.sahayat.app.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.sahayat.app.core.Outcome
import com.sahayat.app.core.Remote
import com.sahayat.app.data.SessionRepository
import com.sahayat.app.data.model.DetectionSettings
import com.sahayat.app.data.model.Sensitivity
import com.sahayat.app.data.service.PatientService
import com.sahayat.app.ui.components.ActionState
import com.sahayat.app.ui.components.ActionStateBanner
import com.sahayat.app.ui.components.ErrorState
import com.sahayat.app.ui.components.LoadingBlock
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.SCard
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.SSegmented
import com.sahayat.app.ui.components.SSwitchRow
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.VSpace
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.sahayatViewModel
import com.sahayat.app.ui.theme.SColors
import com.sahayat.app.ui.theme.SType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class DetectionViewModel(private val patients: PatientService, val session: SessionRepository) : ViewModel() {
    private val _save = MutableStateFlow<ActionState>(ActionState.Idle)
    val save: StateFlow<ActionState> = _save

    fun save(settings: DetectionSettings) {
        val pid = session.state.value.patientId ?: return
        _save.value = ActionState.Busy
        viewModelScope.launch {
            _save.value = when (val r = patients.updateDetection(pid, settings)) {
                is Outcome.Success -> ActionState.Done
                is Outcome.Failure -> ActionState.Failed(r.error)
            }
        }
    }

    fun clear() { _save.value = ActionState.Idle }
}

@Composable
fun DetectionSettingsScreen(onBack: () -> Unit) {
    val s = LocalStrings.current
    val vm = sahayatViewModel { DetectionViewModel(it.patientService, it.session) }
    val session by vm.session.state.collectAsStateWithLifecycle()
    val save by vm.save.collectAsStateWithLifecycle()

    ScreenScaffold(title = s.detectionTitle, onBack = onBack) {
        ActionStateBanner(save, s.syncedToDevice, vm::clear)
        when (val p = session.patient) {
            Remote.Loading -> LoadingBlock()
            is Remote.Unavailable -> ErrorState(s.error(p.error))
            is Remote.Data -> {
                val current = p.value.detection
                var sensitivity by rememberSaveable(current) { mutableStateOf(current.sensitivity) }
                var timeout by rememberSaveable(current) { mutableStateOf(current.escalationTimeoutSec) }
                var sms by rememberSaveable(current) { mutableStateOf(current.smsFallbackEnabled) }

                SCard {
                    Text(s.detectionSensitivity, style = SType.CardTitle)
                    VSpace(10)
                    SSegmented(
                        options = listOf(Sensitivity.LOW to s.sensLow, Sensitivity.MEDIUM to s.sensMedium, Sensitivity.HIGH to s.sensHigh),
                        selected = sensitivity,
                        onSelect = { sensitivity = it },
                    )
                    VSpace(10)
                    Text(
                        when (sensitivity) {
                            Sensitivity.LOW -> s.sensLowBody
                            Sensitivity.MEDIUM -> s.sensMediumBody
                            Sensitivity.HIGH -> s.sensHighBody
                        },
                        style = SType.Body,
                    )
                    VSpace(8)
                    Text(s.sensitivityNotOnBand, style = SType.Caption, color = SColors.Warning)
                }
                SCard {
                    Text("${s.escalationTimeout}: $timeout${s.seconds}", style = SType.CardTitle)
                    VSpace(4)
                    Text(s.escalationTimeoutBody, style = SType.Body)
                    Slider(
                        value = timeout.toFloat(),
                        onValueChange = { timeout = (it / 15f).roundToInt() * 15 },
                        valueRange = 15f..300f,
                        steps = 18,
                        colors = SliderDefaults.colors(
                            thumbColor = SColors.Accent,
                            activeTrackColor = SColors.Accent,
                            inactiveTrackColor = SColors.CardAlt,
                        ),
                    )
                }
                SSwitchRow(s.smsFallback, sms, { sms = it }, subtitle = s.smsFallbackBody, icon = SIcons.Sms)
                val changed = sensitivity != current.sensitivity || timeout != current.escalationTimeoutSec || sms != current.smsFallbackEnabled
                SButton(
                    s.save,
                    { vm.save(DetectionSettings(sensitivity, timeout, sms)) },
                    Modifier.fillMaxWidth(),
                    loading = save is ActionState.Busy,
                    enabled = changed,
                )
            }
        }
    }
}
