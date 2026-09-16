package com.sahayat.app.ui.screens.care

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sahayat.app.AppContainer
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Format
import com.sahayat.app.core.Outcome
import com.sahayat.app.data.service.AppRole
import com.sahayat.app.data.service.Invite
import com.sahayat.app.data.service.RelayService
import com.sahayat.app.emergency.AlertWatchService
import com.sahayat.app.ui.components.Banner
import com.sahayat.app.ui.components.ButtonKind
import com.sahayat.app.ui.components.LoadingBlock
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.SCard
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.STextField
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.Tone
import com.sahayat.app.ui.components.VSpace
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.sahayatViewModel
import com.sahayat.app.ui.theme.SColors
import com.sahayat.app.ui.theme.SType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ invite

data class InviteState(val busy: Boolean = false, val invite: Invite? = null, val error: AppError? = null)

class InviteViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(InviteState())
    val state: StateFlow<InviteState> = _state

    fun create() {
        val pid = c.session.state.value.patientId
        if (pid == null) {
            _state.value = InviteState(error = AppError.NoPatient)
            return
        }
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            _state.value = when (val r = c.relayService.createInvite(pid)) {
                is Outcome.Success -> InviteState(invite = r.value)
                is Outcome.Failure -> InviteState(error = r.error)
            }
        }
    }
}

@Composable
fun InviteCaregiverScreen(onBack: () -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val vm = sahayatViewModel { InviteViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { if (state.invite == null && !state.busy) vm.create() }

    ScreenScaffold(
        title = s.inviteCaregiver,
        eyebrow = s.caregivers,
        subtitle = s.inviteSubtitle,
        onBack = onBack,
        bottom = {
            val inv = state.invite
            if (inv != null) {
                SButton(
                    s.share,
                    {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, s.inviteShareText(RelayService.formatCode(inv.code)))
                        context.startActivity(Intent.createChooser(send, s.inviteCaregiver))
                    },
                    Modifier.fillMaxWidth(),
                    icon = SIcons.Share,
                )
            }
            SButton(s.newCode, vm::create, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, loading = state.busy)
        },
    ) {
        state.error?.let { Banner(s.error(it), Tone.Danger) }
        SCard {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.inviteCode, style = SType.Eyebrow)
                VSpace(10)
                val inv = state.invite
                when {
                    inv != null -> {
                        Text(
                            RelayService.formatCode(inv.code),
                            color = SColors.TextPrimary,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp,
                            textAlign = TextAlign.Center,
                        )
                        VSpace(6)
                        Text("${s.inviteValid} (${Format.dateTime(inv.expiresAtMs)})", style = SType.Caption, textAlign = TextAlign.Center)
                    }
                    state.busy -> LoadingBlock()
                    else -> Text(s.unavailable, style = SType.Body)
                }
            }
        }
        SCard { Text(s.inviteBody, style = SType.Body) }
    }
}

// ------------------------------------------------------------------ join

data class JoinState(val busy: Boolean = false, val error: AppError? = null, val joinedName: String? = null, val joined: Boolean = false)

class JoinViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(JoinState())
    val state: StateFlow<JoinState> = _state

    fun join(context: android.content.Context, code: String) {
        if (_state.value.busy) return
        _state.value = JoinState(busy = true)
        viewModelScope.launch {
            when (val r = c.relayService.joinPatient(code)) {
                is Outcome.Failure -> _state.value = JoinState(error = r.error)
                is Outcome.Success -> {
                    c.secureStore.patientId = r.value.patientId
                    c.settings.setOnboardingComplete(true)
                    val profile = c.settings.currentProfile()
                    if (profile.role != AppRole.CAREGIVER) c.settings.saveProfile(profile.copy(role = AppRole.CAREGIVER))
                    AlertWatchService.start(context.applicationContext)
                    _state.value = JoinState(joined = true, joinedName = r.value.patientName)
                }
            }
        }
    }
}

@Composable
fun JoinPatientScreen(onBack: (() -> Unit)?, onJoined: () -> Unit, onCreatePatient: () -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val vm = sahayatViewModel { JoinViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }

    ScreenScaffold(
        title = s.joinPatient,
        eyebrow = s.roleCaregiver,
        subtitle = s.joinSubtitle,
        onBack = onBack,
        showMenu = onBack != null,
        bottom = {
            if (state.joined) {
                SButton(s.continueLabel, onJoined, Modifier.fillMaxWidth())
            } else {
                SButton(s.join, { vm.join(context, code) }, Modifier.fillMaxWidth(), loading = state.busy, enabled = code.length == RelayService.CODE_LEN)
                SButton(s.createPatientInstead, onCreatePatient, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
            }
        },
    ) {
        state.error?.let { Banner(s.error(it), Tone.Danger) }
        if (state.joined) {
            Banner(s.joined(state.joinedName), Tone.Ok, icon = SIcons.Check)
        } else {
            STextField(
                code,
                { v -> code = RelayService.normalizeCode(v) },
                s.inviteCode,
                hint = "ABCD-2345",
                keyboardType = KeyboardType.Ascii,
            )
            SCard { Text(s.pairOnPatientPhoneBody, style = SType.Body) }
        }
    }
}
