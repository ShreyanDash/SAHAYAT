package com.sahayat.app.ui.screens.contacts

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Outcome
import com.sahayat.app.core.Remote
import com.sahayat.app.core.valueOrNull
import com.sahayat.app.data.SessionRepository
import com.sahayat.app.data.model.Caregiver
import com.sahayat.app.data.service.CaregiverInput
import com.sahayat.app.data.service.CaregiverService
import com.sahayat.app.ui.components.Banner
import com.sahayat.app.ui.components.ButtonKind
import com.sahayat.app.ui.components.ConfirmDialog
import com.sahayat.app.ui.components.ErrorState
import com.sahayat.app.ui.components.LoadingBlock
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.SSwitchRow
import com.sahayat.app.ui.components.STextField
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.Tone
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.sahayatViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch

data class ContactEditState(
    val existing: Remote<Caregiver?> = Remote.Loading,
    val busy: Boolean = false,
    val error: AppError? = null,
    val done: Boolean = false,
)

class ContactEditViewModel(
    private val caregiverId: String?,
    private val caregivers: CaregiverService,
    private val session: SessionRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ContactEditState())
    val state: StateFlow<ContactEditState> = _state

    init { load() }

    fun load() {
        if (caregiverId == null) {
            _state.update { it.copy(existing = Remote.Data(null)) }
            return
        }
        val pid = session.state.value.patientId
        if (pid == null) {
            _state.update { it.copy(existing = Remote.Unavailable(AppError.NoPatient)) }
            return
        }
        _state.update { it.copy(existing = Remote.Loading) }
        viewModelScope.launch {
            val r = caregivers.get(pid, caregiverId)
            _state.update {
                it.copy(existing = when (r) {
                    is Outcome.Success -> Remote.Data(r.value)
                    is Outcome.Failure -> Remote.Unavailable(r.error)
                })
            }
        }
    }

    fun save(input: CaregiverInput) {
        val pid = session.state.value.patientId
        if (pid == null) {
            _state.update { it.copy(error = AppError.NoPatient) }
            return
        }
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val r: Outcome<Any> = if (caregiverId == null) {
                // New contacts go to the end of the escalation order.
                val count = caregivers.currentCount(pid)
                caregivers.add(pid, input, priority = count)
            } else {
                caregivers.update(pid, caregiverId, input)
            }
            _state.update {
                when (r) {
                    is Outcome.Failure -> it.copy(busy = false, error = r.error)
                    is Outcome.Success -> it.copy(busy = false, done = true)
                }
            }
        }
    }

    fun delete() {
        val pid = session.state.value.patientId ?: return
        val id = caregiverId ?: return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            when (val r = caregivers.delete(pid, id)) {
                is Outcome.Failure -> _state.update { it.copy(busy = false, error = r.error) }
                is Outcome.Success -> _state.update { it.copy(busy = false, done = true) }
            }
        }
    }
}

/** Current number of caregivers, used to append a new one at the end. */
private suspend fun CaregiverService.currentCount(patientId: String): Int {
    val first = withTimeoutOrNull(5_000) {
        observeCaregivers(patientId).first { it !is Remote.Loading }
    }
    return first?.valueOrNull?.size ?: 999
}

@Composable
fun ContactEditScreen(caregiverId: String?, onDone: () -> Unit) {
    val s = LocalStrings.current
    val vm = sahayatViewModel(key = "contact-$caregiverId") {
        ContactEditViewModel(caregiverId, it.caregiverService, it.session)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }

    ScreenScaffold(title = if (caregiverId == null) s.addContact else s.editContact, onBack = onDone) {
        when (val e = state.existing) {
            Remote.Loading -> LoadingBlock()
            is Remote.Unavailable -> ErrorState(s.error(e.error), onRetry = vm::load)
            is Remote.Data -> ContactForm(e.value, state, onSave = vm::save, onDelete = vm::delete)
        }
    }
}

@Composable
private fun ContactForm(
    existing: Caregiver?,
    state: ContactEditState,
    onSave: (CaregiverInput) -> Unit,
    onDelete: () -> Unit,
) {
    val s = LocalStrings.current
    var name by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var phone by rememberSaveable { mutableStateOf(existing?.phone.orEmpty()) }
    var relationship by rememberSaveable { mutableStateOf(existing?.relationship.orEmpty()) }
    var primary by rememberSaveable { mutableStateOf(existing?.isPrimary ?: false) }
    var push by rememberSaveable { mutableStateOf(existing?.notifyPush ?: true) }
    var sms by rememberSaveable { mutableStateOf(existing?.notifySms ?: true) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    val nameErr = if (submitted && name.isBlank()) s.nameRequired else null
    val phoneErr = if (submitted && !CaregiverService.isValidPhone(phone)) s.phoneInvalid else null

    state.error?.let { Banner(s.error(it), Tone.Danger) }
    STextField(name, { name = it.take(60) }, s.fullName, error = nameErr)
    STextField(phone, { phone = it.take(18) }, s.phone, hint = s.phoneHint, keyboardType = KeyboardType.Phone, error = phoneErr)
    STextField(relationship, { relationship = it.take(40) }, s.relationship)
    SSwitchRow(s.primaryCaregiver, primary, { primary = it }, subtitle = s.primaryCaregiverBody, icon = SIcons.Star)
    SSwitchRow(s.notifyPush, push, { push = it }, icon = SIcons.Notifications)
    SSwitchRow(s.notifySms, sms, { sms = it }, subtitle = s.notifySmsBody, icon = SIcons.Sms)
    SButton(
        s.save,
        {
            submitted = true
            if (name.isNotBlank() && CaregiverService.isValidPhone(phone)) {
                onSave(CaregiverInput(name, phone, relationship, primary, push, sms))
            }
        },
        Modifier.fillMaxWidth(),
        loading = state.busy,
    )
    if (existing != null) {
        SButton(s.deleteContact, { confirmDelete = true }, Modifier.fillMaxWidth(), kind = ButtonKind.Danger, icon = SIcons.Delete, enabled = !state.busy)
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = s.deleteContact,
            text = s.deleteConfirm,
            confirmLabel = s.delete,
            danger = true,
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}
