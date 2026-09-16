package com.sahayat.app.ui.screens.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Format
import com.sahayat.app.core.Outcome
import com.sahayat.app.core.Remote
import com.sahayat.app.data.model.SeizureEvent
import com.sahayat.app.data.service.HistoryService
import com.sahayat.app.ui.components.ErrorState
import com.sahayat.app.ui.components.HSpace
import com.sahayat.app.ui.components.InfoLine
import com.sahayat.app.ui.components.LoadingBlock
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.SCard
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.StatusPill
import com.sahayat.app.ui.components.VSpace
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.sahayatViewModel
import com.sahayat.app.ui.screens.sos.sosTone
import com.sahayat.app.ui.theme.SColors
import com.sahayat.app.ui.theme.SType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class HistoryDetailViewModel(private val eventId: String, private val history: HistoryService) : ViewModel() {
    private val _event = MutableStateFlow<Remote<SeizureEvent>>(Remote.Loading)
    val event: StateFlow<Remote<SeizureEvent>> = _event

    init { load() }

    fun load() {
        if (eventId.isBlank()) {
            _event.value = Remote.Unavailable(AppError.NotFound)
            return
        }
        _event.value = Remote.Loading
        viewModelScope.launch {
            _event.value = when (val r = history.fetchEvent(eventId)) {
                is Outcome.Success -> Remote.Data(r.value)
                is Outcome.Failure -> Remote.Unavailable(r.error)
            }
        }
    }
}

@Composable
fun HistoryDetailScreen(eventId: String, onBack: () -> Unit, onOpenSos: (String) -> Unit) {
    val s = LocalStrings.current
    val vm = sahayatViewModel(key = "history-$eventId") { HistoryDetailViewModel(eventId, it.historyService) }
    val event by vm.event.collectAsStateWithLifecycle()

    ScreenScaffold(title = s.eventDetails, onBack = onBack) {
        when (val r = event) {
            Remote.Loading -> LoadingBlock()
            is Remote.Unavailable -> ErrorState(s.error(r.error), onRetry = vm::load)
            is Remote.Data -> {
                val e = r.value
                SCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(Format.date(e.detectedAtMs), style = SType.CardTitle)
                            Text(Format.time(e.detectedAtMs), style = SType.Body)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            StatusPill(s.sosStatus(e.status), sosTone(e.status))
                            if (e.isTest) StatusPill(s.testLabel, com.sahayat.app.ui.components.Tone.Warn)
                        }
                    }
                    VSpace(8)
                    InfoLine(s.patient, e.patientName)
                    InfoLine(s.duration, Format.duration(e.durationSec))
                    InfoLine(s.responder, e.acknowledgedBy?.name)
                    InfoLine(s.responseTime, Format.duration(e.responseTimeSec))
                    InfoLine(s.escalation, if (e.escalatedAtMs != null) Format.dateTime(e.escalatedAtMs) else s.notEscalated)
                    if (e.escalatedAtMs != null) InfoLine(s.smsFallbackStatus, s.sms(e.smsStatus))
                    InfoLine(s.device, e.deviceId)
                    InfoLine(s.batteryAtAlert, e.batteryPercent?.let { "$it%" })
                    e.notes?.let { InfoLine(s.notes, it) }
                    InfoLine(s.eventIdLabel, e.id)
                }

                SCard {
                    Text(s.timeline, style = SType.CardTitle)
                    VSpace(8)
                    TimelineItem(s.eventDetected, e.detectedAtMs, SColors.Danger)
                    e.acknowledgedAtMs?.let { TimelineItem("${s.eventAcknowledged} · ${e.acknowledgedBy?.name ?: ""}", it, SColors.Warning) }
                    e.escalatedAtMs?.let { TimelineItem(s.eventEscalated, it, SColors.Warning) }
                    e.smsUpdatedAtMs?.let { TimelineItem("${s.smsFallbackStatus}: ${s.sms(e.smsStatus)}", it, SColors.Neutral) }
                    if (e.cancelledBy != null) {
                        TimelineItem("${s.eventCancelled} · ${e.cancelledBy.name}", e.resolvedAtMs ?: e.endedAtMs, SColors.Neutral)
                    }
                    e.resolvedAtMs?.takeIf { e.cancelledBy == null }?.let { TimelineItem(s.eventResolved, it, SColors.Accent) }
                }

                com.sahayat.app.ui.components.EventLocationCard(e.location, s, e.id, e.patientName)

                if (e.status.isOpen) {
                    SButton(s.openAlert, { onOpenSos(e.id) }, Modifier.fillMaxWidth(), icon = SIcons.Alert)
                }
            }
        }
    }
}

@Composable
private fun TimelineItem(label: String, atMs: Long?, color: Color) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        HSpace(12)
        Text(label, style = SType.BodyStrong, modifier = Modifier.weight(1f))
        Text(atMs?.let(Format::shortTime) ?: "—", style = SType.Caption)
    }
}
