package com.sahayat.app.ui.screens.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Outcome
import com.sahayat.app.core.Remote
import com.sahayat.app.data.SessionRepository
import com.sahayat.app.data.model.Caregiver
import com.sahayat.app.data.service.CaregiverService
import com.sahayat.app.ui.components.Banner
import com.sahayat.app.ui.components.SCard
import com.sahayat.app.ui.components.SectionLabel
import com.sahayat.app.ui.components.VSpace
import com.sahayat.app.core.valueOrNull
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
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
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.StatusPill
import com.sahayat.app.ui.components.Tone
import com.sahayat.app.ui.components.tint
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.sahayatViewModel
import com.sahayat.app.ui.theme.SColors
import com.sahayat.app.ui.theme.SShapes
import com.sahayat.app.ui.theme.SType
import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModel(
    private val caregivers: CaregiverService,
    val session: SessionRepository,
) : ViewModel() {

    val list: StateFlow<Remote<List<Caregiver>>> = session.state
        .map { it.patientId to it.patient }
        .distinctUntilChanged()
        .flatMapLatest { (pid, patient) ->
            when {
                pid != null -> caregivers.observeCaregivers(pid)
                patient is Remote.Unavailable -> flowOf<Remote<List<Caregiver>>>(patient)
                else -> flowOf<Remote<List<Caregiver>>>(Remote.Loading)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Remote.Loading)

    private val _error = MutableStateFlow<AppError?>(null)
    val error: StateFlow<AppError?> = _error

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun move(current: List<Caregiver>, index: Int, delta: Int) {
        val pid = session.state.value.patientId ?: return
        val target = index + delta
        if (target !in current.indices || _busy.value) return
        val ids = current.map { it.id }.toMutableList()
        ids.add(target, ids.removeAt(index))
        _busy.value = true
        viewModelScope.launch {
            val r = caregivers.reorder(pid, ids)
            _error.value = (r as? Outcome.Failure)?.error
            _busy.value = false
        }
    }

    fun retry() { _error.value = null }
}

@Composable
fun CaregiverList(
    vm: ContactsViewModel,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
) {
    val s = LocalStrings.current
    val list by vm.list.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val session by vm.session.state.collectAsStateWithLifecycle()

    error?.let { Banner(s.error(it), Tone.Danger) }
    SectionLabel(s.notificationOrder, action = s.addContact to onAdd)
    when (val r = list) {
        Remote.Loading -> LoadingBlock()
        is Remote.Unavailable -> SCard { ErrorState(s.error(r.error)) }
        is Remote.Data -> {
            val items = r.value
            if (items.isEmpty()) {
                SCard { EmptyState(SIcons.People, s.noCaregivers, s.noCaregiversBody, action = s.addCaregiver to onAdd) }
            } else {
                items.forEachIndexed { index, c ->
                    CaregiverRow(
                        c = c,
                        index = index,
                        isLast = index == items.lastIndex,
                        enabled = !busy,
                        onClick = { onEdit(c.id) },
                        onUp = { vm.move(items, index, -1) },
                        onDown = { vm.move(items, index, +1) },
                    )
                }
            }
        }
    }

    // SMS fallback state comes from the patient's saved detection settings — never assumed.
    val detection = session.patient.valueOrNull?.detection
    if (detection != null) {
        SCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(SIcons.ShieldCheck, if (detection.smsFallbackEnabled) SColors.Accent else SColors.Neutral)
                HSpace(16)
                Column(Modifier.weight(1f)) {
                    Text(
                        if (detection.smsFallbackEnabled) s.smsFallbackActive else s.smsFallbackOff,
                        style = SType.CardTitle,
                    )
                    VSpace(3)
                    Text(s.smsFallbackCardBody, style = SType.Body)
                }
            }
        }
    }
}

@Composable
private fun CaregiverRow(
    c: Caregiver,
    index: Int,
    isLast: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
) {
    val s = LocalStrings.current
    val channels = when {
        c.notifyPush && c.notifySms -> s.pushAndSms
        c.notifyPush -> s.pushOnly
        c.notifySms -> s.smsOnly
        else -> s.noChannels
    }
    InteractiveSurface(
        onClick = onClick,
        shape = SShapes.CardSmall,
        color = SColors.Card,
        border = BorderStroke(1.dp, SColors.Stroke),
        modifier = Modifier.widthIn(min = 280.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 18.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(SIcons.People)
            HSpace(18)
            Column(
                Modifier.weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(c.name, style = SType.CardTitle, color = tint(SColors.TextPrimary))
                Text(
                    "${if (index == 0 || c.isPrimary) s.primary else s.backup} · $channels",
                    style = SType.Body,
                    color = tint(SColors.TextSecondary),
                )
            }
            if (index > 0) SmallArrow(SIcons.Up, s.moveUp, enabled, onUp)
            if (!isLast) SmallArrow(SIcons.Down, s.moveDown, enabled, onDown)
            HSpace(16)
            Icon(SIcons.Chevron, null, tint = tint(SColors.TextSecondary))
        }
    }
}

@Composable
private fun SmallArrow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    InteractiveSurface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        enabled = enabled,
        gloss = false,
        modifier = Modifier.size(36.dp),
    ) {
        Icon(icon, label, tint = tint(SColors.TextSecondary), modifier = Modifier.align(Alignment.Center).size(20.dp))
    }
}

@Composable
fun ContactsScreen(onAdd: () -> Unit, onEdit: (String) -> Unit) {
    val s = LocalStrings.current
    val vm = sahayatViewModel { ContactsViewModel(it.caregiverService, it.session) }
    ScreenScaffold(title = s.caregiverContactsTitle, subtitle = s.caregiverContactsSubtitle) {
        CaregiverList(vm, onAdd, onEdit)
    }
}
