package com.sahayat.app.ui.screens.onboarding

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
import com.sahayat.app.data.SessionRepository
import com.sahayat.app.data.model.Patient
import com.sahayat.app.data.service.LocalProfile
import com.sahayat.app.data.service.PatientInput
import com.sahayat.app.data.service.PatientService
import com.sahayat.app.ui.components.Banner
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.STextField
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.Tone
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.sahayatViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class SaveState(val busy: Boolean = false, val error: AppError? = null, val done: Boolean = false)

class PatientFormViewModel(
    private val patients: PatientService,
    private val session: SessionRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SaveState())
    val state: StateFlow<SaveState> = _state

    fun save(existing: Patient?, input: PatientInput) {
        if (_state.value.busy) return
        val uid = session.state.value.uid
        if (uid == null) {
            _state.value = SaveState(error = AppError.NotAuthenticated)
            return
        }
        _state.value = SaveState(busy = true)
        viewModelScope.launch {
            val r = if (existing == null) patients.createPatient(uid, input) else patients.updatePatient(existing.id, input)
            _state.value = when (r) {
                is Outcome.Failure -> SaveState(error = r.error)
                is Outcome.Success -> SaveState(done = true)
            }
        }
    }

    fun consumed() { _state.value = SaveState() }
}

/** Shared patient form, used by onboarding and by Profile. */
@Composable
fun PatientForm(
    existing: Patient?,
    state: SaveState,
    onSave: (PatientInput) -> Unit,
    saveLabel: String,
    draft: LocalProfile? = null,
) {
    val s = LocalStrings.current
    val key = existing?.id ?: draft?.patientName
    var name by rememberSaveable(key) { mutableStateOf(existing?.name ?: draft?.patientName.orEmpty()) }
    var age by rememberSaveable(key) { mutableStateOf(existing?.age?.toString() ?: draft?.patientAge?.toString().orEmpty()) }
    var blood by rememberSaveable(key) { mutableStateOf(existing?.bloodGroup ?: draft?.patientBloodGroup.orEmpty()) }
    var notes by rememberSaveable(key) { mutableStateOf(existing?.emergencyNotes ?: draft?.emergencyNotes.orEmpty()) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    val ageValue = age.toIntOrNull()
    val ageErr = if (age.isNotBlank() && (ageValue == null || ageValue !in 0..130)) s.errInvalidInput else null

    state.error?.let { Banner(s.error(it), Tone.Danger) }
    STextField(name, { name = it }, s.patientName, error = if (submitted && name.isBlank()) s.nameRequired else null)
    STextField(age, { v -> age = v.filter { it.isDigit() }.take(3) }, s.age, keyboardType = KeyboardType.Number, error = ageErr)
    STextField(blood, { blood = it.take(4).uppercase() }, s.bloodGroup)
    STextField(notes, { notes = it.take(300) }, s.emergencyNotes, hint = s.emergencyNotesHint, singleLine = false, minLines = 3)
    SButton(
        saveLabel,
        {
            submitted = true
            if (name.isNotBlank() && ageErr == null) {
                onSave(PatientInput(name, ageValue, blood, notes))
            }
        },
        Modifier.fillMaxWidth(),
        loading = state.busy,
    )
}

@Composable
fun PatientSetupScreen(onDone: () -> Unit) {
    val s = LocalStrings.current
    val vm = sahayatViewModel { PatientFormViewModel(it.patientService, it.session) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) {
        if (state.done) {
            vm.consumed()
            onDone()
        }
    }
    val container = com.sahayat.app.ui.LocalContainer.current
    val draft by container.settings.profile.collectAsStateWithLifecycle(null)
    ScreenScaffold(title = s.patientProfileTitle, subtitle = s.patientProfileSubtitle, eyebrow = s.setupEyebrow, showMenu = false) {
        PatientForm(existing = null, state = state, onSave = { vm.save(null, it) }, saveLabel = s.saveContinue, draft = draft)
    }
}
