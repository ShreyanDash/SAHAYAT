package com.sahayat.app.ui.screens.sos

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Outcome
import com.sahayat.app.core.Remote
import com.sahayat.app.data.TestAlertRepository
import com.sahayat.app.data.model.AlertPayload
import com.sahayat.app.data.model.SeizureEvent
import com.sahayat.app.data.model.SosStatus
import com.sahayat.app.data.service.AlertService
import com.sahayat.app.emergency.EmergencyAlertService
import com.sahayat.app.emergency.SosInbox
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SosAction { ACKNOWLEDGE, CANCEL, RESOLVE }

data class SosUiState(
    val event: Remote<SeizureEvent> = Remote.Loading,
    /** Data from the FCM payload, shown only until the authoritative event loads. */
    val payload: AlertPayload? = null,
    val isTest: Boolean = false,
    val pending: SosAction? = null,
    val actionError: AppError? = null,
    val ringingHere: Boolean = false,
    val now: Long = System.currentTimeMillis(),
) {
    /** The state machine value the screen renders. */
    val status: SosStatus
        get() = when (val e = event) {
            is Remote.Data -> e.value.status
            else -> payload?.status ?: SosStatus.IDLE
        }
}

/**
 * Drives the SOS screen. Transitions are requested from the backend (or the local
 * TEST ALERT store) and the screen only moves once the new status is confirmed.
 */
class SosViewModel(
    private val app: Application,
    private val eventId: String,
    private val alerts: AlertService,
    private val testAlerts: TestAlertRepository,
    inbox: SosInbox,
) : ViewModel() {

    private val isTest = TestAlertRepository.isTestId(eventId)
    private val payload = inbox.payloadFor(eventId)

    private val eventFlow: Flow<Remote<SeizureEvent>> =
        if (isTest) testAlerts.event.map { e ->
            if (e != null && e.id == eventId) Remote.Data(e) else Remote.Unavailable(AppError.NotFound)
        } else alerts.observeEvent(eventId)

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    private val local = MutableStateFlow(SosUiState(payload = payload, isTest = isTest))

    val state: StateFlow<SosUiState> = combine(
        eventFlow.onEach { r ->
            // Backend says the alert is handled (possibly by another caregiver) → silence this phone.
            if (r is Remote.Data && !r.value.status.isOpen) EmergencyAlertService.stop(app, eventId)
        },
        local,
        EmergencyAlertService.ringingEventId,
        ticker,
    ) { event, l, ringing, now ->
        l.copy(event = event, ringingHere = ringing == eventId, now = now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    fun perform(action: SosAction, notes: String? = null) {
        if (local.value.pending != null) return
        local.value = local.value.copy(pending = action, actionError = null)
        viewModelScope.launch {
            if (isTest) {
                testAlerts.transition(
                    eventId,
                    when (action) {
                        SosAction.ACKNOWLEDGE -> SosStatus.ACKNOWLEDGED
                        SosAction.CANCEL -> SosStatus.CANCELLED
                        SosAction.RESOLVE -> SosStatus.RESOLVED
                    },
                )
                if (action != SosAction.RESOLVE) EmergencyAlertService.stop(app, eventId)
                local.value = local.value.copy(pending = null)
                return@launch
            }
            val result = when (action) {
                SosAction.ACKNOWLEDGE -> alerts.acknowledge(eventId)
                SosAction.CANCEL -> alerts.cancelFalseAlarm(eventId)
                SosAction.RESOLVE -> alerts.resolve(eventId, notes)
            }
            when (result) {
                is Outcome.Success -> {
                    // Stop sound/vibration only after the backend accepted the transition.
                    EmergencyAlertService.stop(app, eventId)
                    local.value = local.value.copy(pending = null)
                }
                is Outcome.Failure -> local.value = local.value.copy(pending = null, actionError = result.error)
            }
        }
    }

    fun dismissError() { local.value = local.value.copy(actionError = null) }

    fun closeTest() {
        if (isTest) {
            EmergencyAlertService.stop(app, eventId)
            testAlerts.clear()
        }
    }
}
