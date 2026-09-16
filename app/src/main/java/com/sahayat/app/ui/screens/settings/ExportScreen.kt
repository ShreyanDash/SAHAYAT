package com.sahayat.app.ui.screens.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Format
import com.sahayat.app.core.Outcome
import com.sahayat.app.core.Remote
import com.sahayat.app.core.valueOrNull
import com.sahayat.app.data.SessionRepository
import com.sahayat.app.data.model.DeviceStatus
import com.sahayat.app.data.model.HistoryRange
import com.sahayat.app.data.service.AuthService
import com.sahayat.app.data.service.DeviceService
import com.sahayat.app.data.service.ExportRequest
import com.sahayat.app.data.service.ExportService
import com.sahayat.app.data.service.ExportedFile
import com.sahayat.app.data.service.HistoryService
import com.sahayat.app.ui.components.Banner
import com.sahayat.app.ui.components.ButtonKind
import com.sahayat.app.ui.components.EmptyState
import com.sahayat.app.ui.components.InfoLine
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.SCard
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.SSegmented
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.Tone
import com.sahayat.app.ui.components.VSpace
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.sahayatViewModel
import com.sahayat.app.ui.screens.history.DateWindow
import com.sahayat.app.ui.screens.history.DateWindows
import com.sahayat.app.ui.theme.SColors
import com.sahayat.app.ui.theme.SType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class ExportUiState(
    val window: DateWindow = DateWindows.of(HistoryRange.ALL),
    val busy: Boolean = false,
    val error: AppError? = null,
    val file: ExportedFile? = null,
    val saved: Boolean = false,
)

class ExportViewModel(
    private val session: SessionRepository,
    private val history: HistoryService,
    private val devices: DeviceService,
    private val exporter: ExportService,
    private val auth: AuthService,
) : ViewModel() {
    private val _state = MutableStateFlow(ExportUiState())
    val state: StateFlow<ExportUiState> = _state

    fun setWindow(w: DateWindow) = _state.update { it.copy(window = w, file = null, error = null, saved = false) }

    fun generate() {
        if (_state.value.busy) return
        val s = session.state.value
        val patient = s.patient.valueOrNull
        if (patient == null) {
            _state.update { it.copy(error = (s.patient as? Remote.Unavailable)?.error ?: AppError.NoPatient) }
            return
        }
        _state.update { it.copy(busy = true, error = null, file = null, saved = false) }
        viewModelScope.launch {
            val w = _state.value.window
            when (val events = history.fetchEvents(patient.id, w.fromMs, w.toMs)) {
                is Outcome.Failure -> _state.update { it.copy(busy = false, error = events.error) }
                is Outcome.Success -> {
                    val device: DeviceStatus? = patient.deviceId?.let { id ->
                        withTimeoutOrNull(5_000) {
                            devices.observeDevice(id).first { r -> r !is Remote.Loading }.valueOrNull
                        }
                    }
                    val result = exporter.export(
                        ExportRequest(
                            patient = patient,
                            fromMs = w.fromMs,
                            toMs = w.toMs,
                            events = events.value,
                            device = device,
                            generatedBy = s.profile?.displayName?.ifBlank { null } ?: auth.currentEmail ?: "SAHAYAT caregiver",
                        ),
                    )
                    _state.update {
                        when (result) {
                            is Outcome.Success -> it.copy(busy = false, file = result.value)
                            is Outcome.Failure -> it.copy(busy = false, error = result.error)
                        }
                    }
                }
            }
        }
    }

    fun saveTo(uri: android.net.Uri) {
        val file = _state.value.file ?: return
        viewModelScope.launch {
            when (val r = exporter.copyTo(file.file, uri)) {
                is Outcome.Success -> _state.update { it.copy(saved = true) }
                is Outcome.Failure -> _state.update { it.copy(error = r.error) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(onBack: () -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val vm = sahayatViewModel {
        ExportViewModel(it.session, it.historyService, it.deviceService, it.exportService, it.authService)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.saveTo(uri)
    }

    ScreenScaffold(title = s.exportTitle, subtitle = s.exportBody, onBack = onBack) {
        state.error?.let { Banner(s.error(it), Tone.Danger) }
        SCard {
            Text(s.dateRange, style = SType.CardTitle)
            VSpace(10)
            SSegmented(
                options = listOf(
                    HistoryRange.ALL to s.filterAll,
                    HistoryRange.WEEK to s.filterWeek,
                    HistoryRange.MONTH to s.filterMonth,
                    HistoryRange.CUSTOM to s.filterCustom,
                ),
                selected = state.window.range,
                onSelect = { if (it == HistoryRange.CUSTOM) { picking = true } else { vm.setWindow(DateWindows.of(it)) } },
            )
            VSpace(8)
            InfoLine(s.from, state.window.fromMs?.let(Format::date) ?: s.filterAll)
            InfoLine(s.to, state.window.toMs?.let(Format::date) ?: Format.date(System.currentTimeMillis()))
        }
        SButton(s.generatePdf, vm::generate, Modifier.fillMaxWidth(), icon = SIcons.Pdf, loading = state.busy)

        state.file?.let { f ->
            SCard {
                EmptyState(SIcons.Pdf, s.exportReady, "${s.countEvents(f.eventCount)} · ${f.pageCount} page(s)")
                SButton(
                    s.share,
                    {
                        val send = Intent(Intent.ACTION_SEND)
                            .setType("application/pdf")
                            .putExtra(Intent.EXTRA_STREAM, f.uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { context.startActivity(Intent.createChooser(send, s.share)) }
                    },
                    Modifier.fillMaxWidth(),
                    icon = SIcons.Share,
                )
                VSpace(10)
                SButton(
                    s.saveToDevice, { saveLauncher.launch(f.file.name) }, Modifier.fillMaxWidth(),
                    kind = ButtonKind.Secondary, icon = SIcons.Save,
                )
                if (state.saved) {
                    VSpace(10)
                    Banner(s.savedToDevice, Tone.Ok)
                }
            }
        }
    }

    if (picking) {
        val picker = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                SButton(
                    s.apply,
                    {
                        val from = picker.selectedStartDateMillis
                        val to = picker.selectedEndDateMillis ?: from
                        if (from != null && to != null) vm.setWindow(DateWindows.of(HistoryRange.CUSTOM, from, to))
                        picking = false
                    },
                    enabled = picker.selectedStartDateMillis != null,
                )
            },
            dismissButton = { SButton(s.cancel, { picking = false }, kind = ButtonKind.Ghost) },
            colors = DatePickerDefaults.colors(containerColor = SColors.CardAlt),
        ) {
            DateRangePicker(
                state = picker,
                title = { Text(s.selectDates, style = SType.CardTitle, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
                showModeToggle = false,
                colors = DatePickerDefaults.colors(
                    containerColor = SColors.CardAlt,
                    selectedDayContainerColor = SColors.Accent,
                    dayInSelectionRangeContainerColor = SColors.AccentMuted,
                ),
            )
        }
    }
}
