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
import com.sahayat.app.data.SessionPhase
import com.sahayat.app.data.SessionRepository
import com.sahayat.app.data.service.AppRole
import com.sahayat.app.data.service.AuthService
import com.sahayat.app.data.service.PatientInput
import com.sahayat.app.data.service.PatientService
import com.sahayat.app.data.service.SettingsRepository
import com.sahayat.app.ui.components.Banner
import com.sahayat.app.ui.components.ButtonKind
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.STextField
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.Tone
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.navigation.Routes
import com.sahayat.app.ui.sahayatViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class AuthUiState(
    val busy: Boolean = false,
    val error: AppError? = null,
    val info: Boolean = false,
    /** Destination once sign-in has resolved the session. */
    val route: String? = null,
)

class AuthViewModel(
    private val auth: AuthService,
    private val session: SessionRepository,
    private val settings: SettingsRepository,
    private val patients: PatientService,
) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state

    fun submit(create: Boolean, name: String, email: String, password: String) {
        if (_state.value.busy) return
        _state.value = AuthUiState(busy = true)
        viewModelScope.launch {
            val r = if (create) auth.register(name, email, password) else auth.signIn(email, password)
            when (r) {
                is Outcome.Failure -> _state.value = AuthUiState(error = r.error)
                is Outcome.Success -> {
                    settings.setPreviewMode(false)
                    val resolved = withTimeoutOrNull(8_000) {
                        session.state.first { it.uid != null && it.phase != SessionPhase.STARTING }
                    } ?: session.state.value
                    val onboarded = settings.current().onboardingComplete
                    val local = settings.currentProfile()
                    val uid = resolved.uid
                    val caregiver = local.role == AppRole.CAREGIVER
                    if (uid != null && local.role != null) patients.setRole(uid, local.role)
                    if (uid != null && local.completed) {
                        // Carry the first-launch profile into the account (best effort; editable later).
                        patients.updateProfile(
                            uid,
                            resolved.profile?.displayName?.ifBlank { null } ?: local.caregiverName.ifBlank { name },
                            local.caregiverPhone.ifBlank { null },
                        )
                    }
                    val route = when (resolved.phase) {
                        SessionPhase.NEEDS_PATIENT -> if (caregiver) Routes.joinPatient(onboarding = true) else {
                            val created = if (uid != null && local.completed && local.patientName.isNotBlank()) {
                                patients.createPatient(
                                    uid,
                                    PatientInput(local.patientName, local.patientAge, local.patientBloodGroup, local.emergencyNotes),
                                ) is Outcome.Success
                            } else false
                            if (created) Routes.CAREGIVER_SETUP else Routes.PATIENT_SETUP
                        }
                        SessionPhase.READY -> {
                            if (!create && resolved.patientId != null) settings.setOnboardingComplete(true)
                            if (caregiver) {
                                settings.setOnboardingComplete(true)
                                Routes.MAIN
                            } else if (create && !onboarded) Routes.CAREGIVER_SETUP else Routes.MAIN
                        }
                        else -> Routes.MAIN
                    }
                    _state.value = AuthUiState(route = route)
                }
            }
        }
    }

    fun resetPassword(email: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, info = false) }
            when (val r = auth.sendPasswordReset(email)) {
                is Outcome.Failure -> _state.update { it.copy(busy = false, error = r.error) }
                is Outcome.Success -> _state.update { it.copy(busy = false, info = true) }
            }
        }
    }
}

@Composable
fun AuthScreen(startInCreateMode: Boolean, onBack: () -> Unit, onRoute: (String) -> Unit) {
    val s = LocalStrings.current
    val vm = sahayatViewModel { AuthViewModel(it.authService, it.session, it.settings, it.patientService) }
    val container = com.sahayat.app.ui.LocalContainer.current
    val state by vm.state.collectAsStateWithLifecycle()

    var create by rememberSaveable { mutableStateOf(startInCreateMode) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.route) { state.route?.let(onRoute) }
    LaunchedEffect(Unit) {
        if (name.isBlank()) name = container.settings.currentProfile().caregiverName
    }

    val nameErr = if (submitted && create && name.isBlank()) s.nameRequired else null
    val passErr = if (submitted && create && password.length < 8) s.passwordHint else null

    ScreenScaffold(
        title = if (create) s.createTitle else s.signInTitle,
        onBack = onBack,
        bottom = {
            SButton(
                if (create) s.createAccount else s.signIn,
                {
                    submitted = true
                    vm.submit(create, name, email, password)
                },
                Modifier.fillMaxWidth(),
                loading = state.busy,
            )
            SButton(
                if (create) s.haveAccount else s.noAccount,
                { create = !create; submitted = false },
                Modifier.fillMaxWidth(),
                kind = ButtonKind.Ghost,
            )
        },
    ) {
        state.error?.let { Banner(s.error(it), Tone.Danger) }
        if (state.info) Banner(s.resetSent, Tone.Ok)
        if (create) {
            STextField(name, { name = it }, s.fullName, error = nameErr)
        }
        STextField(email, { email = it.trim() }, s.email, keyboardType = KeyboardType.Email)
        STextField(password, { password = it }, s.password, password = true, hint = if (create) s.passwordHint else null, error = passErr)
        if (!create) {
            SButton(s.forgotPassword, { vm.resetPassword(email) }, kind = ButtonKind.Ghost, enabled = !state.busy)
        }
    }
}
