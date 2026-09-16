package com.sahayat.app.ui.screens.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sahayat.app.core.Format
import com.sahayat.app.core.Remote
import com.sahayat.app.data.SessionRepository
import com.sahayat.app.data.model.HistoryRange
import com.sahayat.app.data.model.SeizureEvent
import com.sahayat.app.data.service.HistoryService
import com.sahayat.app.ui.components.ButtonKind
import com.sahayat.app.ui.components.EmptyState
import com.sahayat.app.ui.components.ErrorState
import com.sahayat.app.ui.components.HSpace
import com.sahayat.app.ui.components.IconBadge
import com.sahayat.app.ui.components.InteractiveSurface
import com.sahayat.app.ui.components.LoadingBlock
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.SIconButton
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.SSegmented
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.StatusPill
import com.sahayat.app.ui.components.color
import com.sahayat.app.ui.components.tint
import com.sahayat.app.ui.i18n.AppStrings
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.sahayatViewModel
import com.sahayat.app.ui.screens.sos.sosTone
import com.sahayat.app.ui.theme.SColors
import com.sahayat.app.ui.theme.SShapes
import com.sahayat.app.ui.theme.SType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

data class DateWindow(val range: HistoryRange, val fromMs: Long?, val toMs: Long?)

object DateWindows {
    fun of(range: HistoryRange, customFrom: Long? = null, customTo: Long? = null): DateWindow {
        val cal = Calendar.getInstance()
        return when (range) {
            HistoryRange.ALL -> DateWindow(range, null, null)
            HistoryRange.WEEK -> {
                cal.set(Calendar.DAY_OF_WEEK, cal.firstDayOfWeek)
                startOfDay(cal)
                DateWindow(range, cal.timeInMillis, null)
            }
            HistoryRange.MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                startOfDay(cal)
                DateWindow(range, cal.timeInMillis, null)
            }
            HistoryRange.CUSTOM -> DateWindow(range, customFrom, customTo?.let { it + 24 * 3600_000L - 1 })
        }
    }

    private fun startOfDay(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val history: HistoryService,
    session: SessionRepository,
) : ViewModel() {
    private val _window = MutableStateFlow(DateWindows.of(HistoryRange.ALL))
    val window: StateFlow<DateWindow> = _window

    val events: StateFlow<Remote<List<SeizureEvent>>> = combine(session.state, _window) { s, w -> s to w }
        .flatMapLatest { (s, w) ->
            val pid = s.patientId
            val patient = s.patient
            when {
                pid != null -> history.observeEvents(pid, w.fromMs, w.toMs)
                patient is Remote.Unavailable -> flowOf<Remote<List<SeizureEvent>>>(patient)
                else -> flowOf<Remote<List<SeizureEvent>>>(Remote.Loading)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Remote.Loading)

    fun select(range: HistoryRange) {
        if (range != HistoryRange.CUSTOM) _window.value = DateWindows.of(range)
    }

    fun setCustom(fromMs: Long, toMs: Long) {
        _window.value = DateWindows.of(HistoryRange.CUSTOM, fromMs, toMs)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onOpen: (String) -> Unit, onExport: () -> Unit) {
    val s = LocalStrings.current
    val vm = sahayatViewModel { HistoryViewModel(it.historyService, it.session) }
    val window by vm.window.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(false) }

    ScreenScaffold(
        title = s.historyTitle,
        actions = { SIconButton(SIcons.Pdf, s.exportLogs, onExport) },
    ) {
        SSegmented(
            options = listOf(
                HistoryRange.ALL to s.filterAll,
                HistoryRange.WEEK to s.filterWeek,
                HistoryRange.MONTH to s.filterMonth,
                HistoryRange.CUSTOM to s.filterCustom,
            ),
            selected = window.range,
            onSelect = { if (it == HistoryRange.CUSTOM) { picking = true } else { vm.select(it) } },
        )
        if (window.range == HistoryRange.CUSTOM && window.fromMs != null) {
            InteractiveSurface(onClick = { picking = true }, shape = SShapes.Pill, color = SColors.Card) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(SIcons.Calendar, size = 28)
                    HSpace(8)
                    Text(
                        "${Format.date(window.fromMs!!)} – ${window.toMs?.let(Format::date) ?: ""}",
                        style = SType.Caption, color = tint(SColors.TextPrimary),
                    )
                }
            }
        }

        when (val r = events) {
            Remote.Loading -> LoadingBlock()
            is Remote.Unavailable -> ErrorState(s.error(r.error))
            is Remote.Data -> {
                if (r.value.isEmpty()) {
                    EmptyState(SIcons.History, s.noHistory, s.noHistoryBody)
                } else {
                    r.value.forEach { e -> EventRow(e, s) { onOpen(e.id) } }
                }
            }
        }
    }

    if (picking) {
        val pickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                SButton(
                    s.apply,
                    {
                        val from = pickerState.selectedStartDateMillis
                        val to = pickerState.selectedEndDateMillis ?: from
                        if (from != null && to != null) vm.setCustom(from, to)
                        picking = false
                    },
                    enabled = pickerState.selectedStartDateMillis != null,
                )
            },
            dismissButton = { SButton(s.cancel, { picking = false }, kind = ButtonKind.Ghost) },
            colors = DatePickerDefaults.colors(containerColor = SColors.CardAlt),
        ) {
            DateRangePicker(
                state = pickerState,
                title = { Text(s.selectDates, style = SType.CardTitle, modifier = Modifier.padding(start = 24.dp, top = 16.dp)) },
                showModeToggle = false,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = DatePickerDefaults.colors(
                    containerColor = SColors.CardAlt,
                    selectedDayContainerColor = SColors.Accent,
                    dayInSelectionRangeContainerColor = SColors.AccentMuted,
                    todayDateBorderColor = SColors.Accent,
                ),
            )
        }
    }
}

@Composable
fun EventRow(e: SeizureEvent, s: AppStrings, onClick: () -> Unit) {
    InteractiveSurface(
        onClick = onClick,
        shape = SShapes.CardSmall,
        color = SColors.Card,
        border = BorderStroke(1.dp, SColors.Stroke),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(SIcons.Alert, sosTone(e.status).color())
            HSpace(12)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${Format.date(e.detectedAtMs)} · ${Format.shortTime(e.detectedAtMs)}", style = SType.BodyStrong, color = tint(SColors.TextPrimary))
                val parts = listOfNotNull(
                    Format.duration(e.durationSec)?.let { "${s.duration} $it" },
                    e.acknowledgedBy?.name,
                    Format.duration(e.responseTimeSec)?.let { "${s.responseTime} $it" },
                    if (e.escalatedAtMs != null) s.eventEscalated else null,
                    if (e.location != null) s.location else null,
                )
                if (parts.isNotEmpty()) {
                    Text(parts.joinToString(" · "), style = SType.Caption, color = tint(SColors.TextSecondary))
                }
                Text(e.deviceId ?: s.notReported, style = SType.Caption, color = tint(SColors.TextTertiary))
            }
            HSpace(8)
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusPill(s.sosStatus(e.status), sosTone(e.status))
                if (e.isTest) StatusPill(s.testLabel, com.sahayat.app.ui.components.Tone.Warn)
            }
        }
    }
}
