package com.sahayat.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Outcome
import com.sahayat.app.core.Remote
import com.sahayat.app.core.valueOrNull
import com.sahayat.app.data.SessionRepository
import com.sahayat.app.data.SessionState
import com.sahayat.app.data.model.DeviceStatus
import com.sahayat.app.data.model.SeizureEvent
import com.sahayat.app.data.service.AlertReadiness
import com.sahayat.app.data.service.AlertService
import com.sahayat.app.data.service.DeviceService
import com.sahayat.app.data.service.NotificationService
import com.sahayat.app.ui.components.ActionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the dashboard should say about the band, derived only from reported data. */
sealed interface DeviceView {
    data object Loading : DeviceView
    /** No account / patient / backend — nothing to show. */
    data class Unavailable(val error: AppError) : DeviceView
    data object NotPaired : DeviceView
    /** Paired, but no recent heartbeat (or the device document can't be read). */
    data class Disconnected(val deviceId: String, val last: DeviceStatus?, val error: AppError?) : DeviceView
    data class Connected(val status: DeviceStatus) : DeviceView
}

data class DashboardState(
    val session: SessionState = SessionState(),
    val device: DeviceView = DeviceView.Loading,
    val openEvents: List<SeizureEvent> = emptyList(),
    val readiness: AlertReadiness? = null,
    val now: Long = System.currentTimeMillis(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceStatusViewModel(
    private val session: SessionRepository,
    private val devices: DeviceService,
    private val alerts: AlertService,
    private val notifications: NotificationService,
) : ViewModel() {

    /** Ticks so a heartbeat going stale flips the UI to Disconnected without new data arriving. */
    private val clock: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(5_000)
        }
    }

    private val deviceRemote: Flow<Pair<String?, Remote<DeviceStatus>>> = session.state
        .map { it.deviceId }
        .distinctUntilChanged()
        .flatMapLatest { id ->
            if (id == null) flowOf<Pair<String?, Remote<DeviceStatus>>>(null to Remote.Loading)
            else devices.observeDevice(id).map { Pair<String?, Remote<DeviceStatus>>(id, it) }
        }

    private val openEvents: Flow<List<SeizureEvent>> = session.state
        .map { it.patientId }
        .distinctUntilChanged()
        .flatMapLatest { pid ->
            if (pid == null) flowOf(emptyList<SeizureEvent>())
            else alerts.observeOpenEvents(pid).map { it.valueOrNull ?: emptyList() }
        }

    val state: StateFlow<DashboardState> = combine(
        session.state, deviceRemote, openEvents, notifications.readiness, clock,
    ) { s, (id, remote), events, readiness, now ->
        DashboardState(
            session = s,
            device = deviceView(s, id, remote, now),
            openEvents = events,
            readiness = readiness,
            now = now,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState(session = session.state.value))

    private fun deviceView(s: SessionState, id: String?, remote: Remote<DeviceStatus>, now: Long): DeviceView {
        val patient = s.patient
        return when {
            patient is Remote.Unavailable -> DeviceView.Unavailable(patient.error)
            patient is Remote.Loading -> DeviceView.Loading
            id == null -> DeviceView.NotPaired
            remote is Remote.Loading -> DeviceView.Loading
            remote is Remote.Unavailable -> DeviceView.Disconnected(id, null, remote.error)
            remote is Remote.Data -> {
                val st = remote.value
                if (st.patientId != null && st.patientId != patient.valueOrNullId()) {
                    // Device no longer belongs to this patient.
                    DeviceView.Disconnected(id, null, AppError.PermissionDenied)
                } else if (st.isConnected(now)) DeviceView.Connected(st)
                else DeviceView.Disconnected(id, st, null)
            }
            else -> DeviceView.Loading
        }
    }

    private fun Remote<com.sahayat.app.data.model.Patient>.valueOrNullId(): String? =
        this.valueOrNull?.id

    private val _action = MutableStateFlow<ActionState>(ActionState.Idle)
    val action: StateFlow<ActionState> = _action

    fun reconnect() {
        val id = session.state.value.deviceId ?: return
        if (_action.value is ActionState.Busy) return
        _action.value = ActionState.Busy
        viewModelScope.launch {
            _action.value = when (val r = devices.requestSync(id)) {
                is Outcome.Success -> ActionState.Done
                is Outcome.Failure -> ActionState.Failed(r.error)
            }
        }
    }

    fun clearAction() { _action.value = ActionState.Idle }

    fun refreshReadiness() = notifications.refreshReadiness()
}
