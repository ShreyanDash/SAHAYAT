package com.sahayat.app.ui.screens.sos

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahayat.app.MainActivity
import com.sahayat.app.core.Format
import com.sahayat.app.core.Remote
import com.sahayat.app.core.valueOrNull
import com.sahayat.app.data.model.EventLocation
import com.sahayat.app.ui.components.EventLocationCard
import com.sahayat.app.data.model.SeizureEvent
import com.sahayat.app.data.model.SmsStatus
import com.sahayat.app.data.model.SosStatus
import com.sahayat.app.ui.components.Banner
import com.sahayat.app.ui.components.ButtonKind
import com.sahayat.app.ui.components.EmptyState
import com.sahayat.app.ui.components.InfoLine
import com.sahayat.app.ui.components.LoadingBlock
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.SCard
import com.sahayat.app.ui.components.SIconButton
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.STextField
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.StatusPill
import com.sahayat.app.ui.components.Tone
import com.sahayat.app.ui.components.VSpace
import com.sahayat.app.ui.i18n.AppStrings
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.sahayatViewModel
import com.sahayat.app.ui.theme.SColors
import com.sahayat.app.ui.theme.SShapes
import com.sahayat.app.ui.theme.SType
import java.util.Locale

fun sosTone(status: SosStatus): Tone = when (status) {
    SosStatus.ALERT_RECEIVED, SosStatus.ACTIVE, SosStatus.ESCALATED -> Tone.Danger
    SosStatus.ACKNOWLEDGED -> Tone.Warn
    SosStatus.RESOLVED -> Tone.Ok
    SosStatus.CANCELLED, SosStatus.IDLE -> Tone.Neutral
}

@Composable
fun SosScreen(eventId: String, onClose: () -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm = sahayatViewModel(key = "sos-$eventId") {
        SosViewModel(app, eventId, it.alertService, it.testAlerts, it.sosInbox)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val status = state.status
    val event = state.event.valueOrNull

    // While this screen is shown it may appear over the lock screen; nothing else in the app can.
    DisposableEffect(Unit) {
        val activity = context as? MainActivity
        activity?.setSosLockScreenMode(true)
        onDispose { activity?.setSosLockScreenMode(false) }
    }

    // Keep the display on while an alert is open.
    val view = LocalView.current
    DisposableEffect(status.isOpen) {
        view.keepScreenOn = status.isOpen
        onDispose { view.keepScreenOn = false }
    }

    val close = {
        vm.closeTest()
        onClose()
    }
    BackHandler(onBack = close)

    ScreenScaffold(
        title = s.sosTitle,
        actions = { SIconButton(SIcons.Close, s.close, close) },
        bottom = {
            if (status.isOpen) {
                SButton(
                    s.acknowledge, { vm.perform(SosAction.ACKNOWLEDGE) }, Modifier.fillMaxWidth(),
                    icon = SIcons.Check, loading = state.pending == SosAction.ACKNOWLEDGE,
                    enabled = state.pending == null && (event != null || state.isTest),
                )
                SButton(
                    s.cancelFalseAlarm, { vm.perform(SosAction.CANCEL) }, Modifier.fillMaxWidth(),
                    kind = ButtonKind.Secondary, loading = state.pending == SosAction.CANCEL,
                    enabled = state.pending == null && (event != null || state.isTest),
                )
            }
        },
    ) {
        val labelledTest = state.isTest || event?.isTest == true ||
            state.payload?.alertType == com.sahayat.app.data.model.TEST_ALERT_TYPE
        if (state.isTest) {
            Banner("${s.testAlertBanner} — ${s.testAlertBody}", Tone.Warn, icon = SIcons.Test)
        } else if (labelledTest) {
            Banner("${s.testAlertBanner} — ${s.deviceTestBody}", Tone.Warn, icon = SIcons.Test)
        }
        state.actionError?.let {
            Banner("${s.actionFailed} ${s.error(it)}", Tone.Danger, action = s.close to vm::dismissError)
        }

        when (val r = state.event) {
            Remote.Loading -> {
                if (state.payload == null) LoadingBlock()
            }
            is Remote.Unavailable -> {
                if (state.payload == null) {
                    SCard {
                        EmptyState(SIcons.Alert, s.eventNotFound, s.error(r.error), action = s.backToHome to close)
                    }
                }
            }
            is Remote.Data -> Unit
        }

        if (event != null || state.payload != null) {
            if (event == null) Banner(s.showingPayload, Tone.Neutral)
            AlertHeader(state, s, labelledTest)
            DetailsCard(state, event, s)
            if (event != null) {
                EventLocationCard(event.location, s, event.id, event.patientName)
                EscalationCard(event, state.now, s)
                ResponseCard(event, s, pending = state.pending, onResolve = { vm.perform(SosAction.RESOLVE, it) })
                CallCaregiversCard(event.patientId, s)
            } else {
                EventLocationCard(state.payload?.location, s, state.payload?.eventId, state.payload?.patientName)
            }
        }
    }
}

@Composable
private fun AlertHeader(state: SosUiState, s: AppStrings, labelledTest: Boolean) {
    val status = state.status
    val open = status.isOpen
    val color = if (open) SColors.Danger else SColors.Card
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (open) SColors.DangerMuted else color, SShapes.Hero)
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(72.dp).background(if (open) SColors.Danger else SColors.CardAlt, SShapes.Pill),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (open) SIcons.Alert else SIcons.Check, null, tint = SColors.TextPrimary, modifier = Modifier.size(36.dp))
        }
        VSpace(14)
        Text(
            if (labelledTest) s.testAlertBanner else s.possibleSeizure,
            style = SType.Title, textAlign = TextAlign.Center,
        )
        VSpace(8)
        StatusPill(s.sosStatus(status), sosTone(status))
        val started = state.event.valueOrNull?.detectedAtMs ?: state.payload?.timestampMs
        if (started != null && open) {
            VSpace(10)
            Text("${s.elapsed}: ${Format.duration((state.now - started) / 1000) ?: "0s"}", style = SType.BodyStrong)
        }
        if (state.ringingHere) {
            VSpace(6)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SIcons.Sound, null, tint = SColors.TextSecondary, modifier = Modifier.size(16.dp))
                Text(" ${s.alarmSounding}", style = SType.Caption)
            }
        }
    }
}

@Composable
private fun DetailsCard(state: SosUiState, event: SeizureEvent?, s: AppStrings) {
    val p = state.payload
    SCard {
        Text(s.eventDetails, style = SType.CardTitle)
        VSpace(4)
        InfoLine(s.patient, event?.patientName ?: p?.patientName)
        InfoLine(s.detectedAt, (event?.detectedAtMs ?: p?.timestampMs)?.let(Format::dateTime))
        InfoLine(s.device, event?.deviceId ?: p?.deviceId)
        InfoLine(s.batteryAtAlert, (event?.batteryPercent ?: p?.battery)?.let { "$it%" })
        event?.confidence?.let { InfoLine(s.confidence, String.format(Locale.US, "%.0f%%", it * 100)) }
        InfoLine(s.eventIdLabel, event?.id ?: p?.eventId)
    }
}

@Composable
private fun LocationCard(location: EventLocation?, s: AppStrings) {
    val context = LocalContext.current
    SCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (location != null) SIcons.Location else SIcons.LocationOff, null, tint = if (location != null) SColors.Accent else SColors.TextTertiary)
            Text("  ${s.location}", style = SType.CardTitle, modifier = Modifier.weight(1f))
        }
        VSpace(6)
        if (location == null) {
            Text(s.locationUnavailable, style = SType.Body)
        } else {
            Text(
                String.format(Locale.US, "%.5f, %.5f", location.lat, location.lng) +
                    (location.accuracyM?.let { " · ±${it.toInt()} m" } ?: ""),
                style = SType.BodyStrong,
            )
            location.source?.let { Text(it, style = SType.Caption) }
            VSpace(10)
            SButton(
                s.openInMaps,
                {
                    val uri = Uri.parse("geo:${location.lat},${location.lng}?q=${location.lat},${location.lng}")
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
                Modifier.fillMaxWidth(),
                kind = ButtonKind.Secondary,
                icon = SIcons.Location,
            )
        }
    }
}

@Composable
private fun EscalationCard(e: SeizureEvent, now: Long, s: AppStrings) {
    SCard {
        Text(s.escalation, style = SType.CardTitle)
        VSpace(4)
        when {
            e.escalatedAtMs != null -> InfoLine(s.escalatedAt, Format.dateTime(e.escalatedAtMs), SColors.Warning)
            e.status.isOpen && e.escalateAtMs != null -> {
                val remaining = ((e.escalateAtMs - now) / 1000).coerceAtLeast(0)
                InfoLine(s.escalatesIn, Format.duration(remaining), SColors.Warning)
            }
            else -> InfoLine(s.escalation, s.notEscalated)
        }
        // SMS status is shown exactly as confirmed by the device/backend.
        if (e.escalatedAtMs != null || e.smsStatus != SmsStatus.NOT_REQUIRED) {
            val tone = when (e.smsStatus) {
                SmsStatus.SENT -> SColors.Accent
                SmsStatus.FAILED, SmsStatus.UNAVAILABLE -> SColors.Danger
                else -> SColors.TextPrimary
            }
            InfoLine(s.smsFallbackStatus, s.sms(e.smsStatus), tone)
            e.smsDetail?.let { Text(it, style = SType.Caption) }
            e.smsUpdatedAtMs?.let { Text(Format.dateTime(it), style = SType.Caption) }
        }
    }
}

@Composable
private fun ResponseCard(e: SeizureEvent, s: AppStrings, pending: SosAction?, onResolve: (String?) -> Unit) {
    if (e.acknowledgedBy == null && e.cancelledBy == null && e.status != SosStatus.ACKNOWLEDGED && e.status != SosStatus.RESOLVED) return
    var notes by rememberSaveable { mutableStateOf("") }
    SCard {
        Text(s.responder, style = SType.CardTitle)
        VSpace(4)
        e.acknowledgedBy?.let { InfoLine(s.acknowledgedBy, it.name) }
        e.acknowledgedAtMs?.let { InfoLine(s.eventAcknowledged, Format.dateTime(it)) }
        e.responseTimeSec?.let { InfoLine(s.responseTime, Format.duration(it)) }
        e.cancelledBy?.let { InfoLine(s.cancelledBy, it.name) }
        e.resolvedAtMs?.let { InfoLine(s.eventResolved, Format.dateTime(it)) }
        e.notes?.let { InfoLine(s.notes, it) }
        if (e.status == SosStatus.ACKNOWLEDGED) {
            VSpace(8)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                STextField(notes, { notes = it.take(500) }, s.resolveNotes, singleLine = false, minLines = 2)
                SButton(
                    s.markResolved, { onResolve(notes.ifBlank { null }) }, Modifier.fillMaxWidth(),
                    loading = pending == SosAction.RESOLVE, enabled = pending == null,
                )
            }
        }
    }
}

/** Quick-dial list of the patient's caregivers (opens the phone dialer; no call permission needed). */
@Composable
private fun CallCaregiversCard(patientId: String, s: AppStrings) {
    val container = com.sahayat.app.ui.LocalContainer.current
    val context = LocalContext.current
    val flow = androidx.compose.runtime.remember(patientId) { container.caregiverService.observeCaregivers(patientId) }
    val list by flow.collectAsStateWithLifecycle(Remote.Loading)
    val caregivers = list.valueOrNull.orEmpty().filter { it.phone.isNotBlank() }
    if (caregivers.isEmpty()) return
    SCard {
        Text(s.callCaregivers, style = SType.CardTitle)
        VSpace(4)
        Text(s.callCaregiversBody, style = SType.Body)
        VSpace(10)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            caregivers.forEach { c ->
                SButton(
                    "${s.call} ${c.name}",
                    {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.phone}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    Modifier.fillMaxWidth(),
                    kind = ButtonKind.Secondary,
                    icon = SIcons.Phone,
                )
            }
        }
    }
}
