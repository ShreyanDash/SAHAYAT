package com.sahayat.app.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahayat.app.BuildConfig
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Outcome
import com.sahayat.app.core.Remote
import com.sahayat.app.ui.LocalContainer
import com.sahayat.app.ui.components.ActionState
import com.sahayat.app.ui.components.ActionStateBanner
import com.sahayat.app.ui.components.Banner
import com.sahayat.app.ui.components.ButtonKind
import com.sahayat.app.ui.components.EmptyState
import com.sahayat.app.ui.components.ErrorState
import com.sahayat.app.ui.components.InfoLine
import com.sahayat.app.ui.components.LoadingBlock
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.SCard
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.SListRow
import com.sahayat.app.ui.components.STextField
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.SectionLabel
import com.sahayat.app.ui.components.Tone
import com.sahayat.app.ui.components.VSpace
import com.sahayat.app.ui.components.tint
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.i18n.SupportedLanguages
import com.sahayat.app.ui.sahayatViewModel
import com.sahayat.app.ui.screens.onboarding.PatientForm
import com.sahayat.app.ui.screens.onboarding.PatientFormViewModel
import com.sahayat.app.ui.theme.SColors
import com.sahayat.app.ui.theme.SType
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun LanguageScreen(onBack: () -> Unit) {
    val s = LocalStrings.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    ScreenScaffold(title = s.languageTitle, onBack = onBack) {
        SupportedLanguages.forEach { lang ->
            val selected = lang.code == s.code
            SListRow(
                title = lang.languageName,
                icon = SIcons.Language,
                iconColor = if (selected) SColors.Accent else SColors.TextSecondary,
                onClick = { scope.launch { container.settings.setLanguage(lang.code) } },
                showChevron = false,
                trailing = { if (selected) Icon(SIcons.Check, null, tint = tint(SColors.Accent)) },
            )
        }
        Text(s.languageNote, style = SType.Caption)
    }
}

@Composable
fun ProfileScreen(onBack: () -> Unit, onCreatePatient: () -> Unit) {
    val s = LocalStrings.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val session by container.session.state.collectAsStateWithLifecycle()
    val patientVm = sahayatViewModel { PatientFormViewModel(it.patientService, it.session) }
    val patientSave by patientVm.state.collectAsStateWithLifecycle()
    var accountSave by remember { mutableStateOf<ActionState>(ActionState.Idle) }

    val navigate = com.sahayat.app.ui.components.LocalNavigator.current
    val local by container.settings.profile.collectAsStateWithLifecycle(null)
    ScreenScaffold(title = s.profileTitle, eyebrow = s.account, onBack = onBack) {
        SectionLabel(s.setupProfileSection)
        SListRow(
            s.editSetupProfile,
            icon = SIcons.Edit,
            subtitle = local?.let { p ->
                listOf(p.caregiverName, p.patientName.takeIf { it.isNotBlank() }?.let { "${s.patient}: $it" })
                    .filterNotNull().filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
            },
            onClick = { navigate(com.sahayat.app.ui.navigation.Routes.profileSetup(edit = true)) },
        )
        SectionLabel(s.yourAccount)
        val uid = session.uid
        if (uid == null) {
            SCard { ErrorState(s.error(if (session.backendConfigured) AppError.NotAuthenticated else AppError.BackendNotConfigured)) }
        } else {
            val profile = session.profile
            var name by rememberSaveable(profile?.displayName) { mutableStateOf(profile?.displayName.orEmpty()) }
            var phone by rememberSaveable(profile?.phone) { mutableStateOf(profile?.phone.orEmpty()) }
            ActionStateBanner(accountSave, s.saved) { accountSave = ActionState.Idle }
            SCard {
                InfoLine(s.email, profile?.email ?: container.authService.currentEmail)
            }
            STextField(name, { name = it.take(60) }, s.displayName)
            STextField(phone, { phone = it.take(18) }, s.phoneOptional, keyboardType = KeyboardType.Phone)
            SButton(
                s.save,
                {
                    accountSave = ActionState.Busy
                    scope.launch {
                        accountSave = when (val r = container.patientService.updateProfile(uid, name, phone)) {
                            is Outcome.Success -> ActionState.Done
                            is Outcome.Failure -> ActionState.Failed(r.error)
                        }
                    }
                },
                Modifier.fillMaxWidth(),
                kind = ButtonKind.Secondary,
                loading = accountSave is ActionState.Busy,
            )

            SectionLabel(s.patientDetails)
            when (val p = session.patient) {
                Remote.Loading -> LoadingBlock()
                is Remote.Unavailable -> {
                    if (p.error == AppError.NoPatient) {
                        EmptyState(SIcons.Person, s.errNoPatient, s.patientProfileSubtitle, action = s.patientProfileTitle to onCreatePatient)
                    } else {
                        ErrorState(s.error(p.error))
                    }
                }
                is Remote.Data -> {
                    if (patientSave.done) Banner(s.saved, Tone.Ok)
                    PatientForm(existing = p.value, state = patientSave, onSave = { patientVm.save(p.value, it) }, saveLabel = s.save)
                }
            }
        }
    }
}

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current
    var cleared by remember { mutableStateOf(false) }
    ScreenScaffold(title = s.privacyTitle, onBack = onBack) {
        SCard {
            listOf(s.privacyPoint1, s.privacyPoint2, s.privacyPoint3, s.privacyPoint4).forEach {
                Text("•  $it", style = SType.Body)
                VSpace(8)
            }
        }
        if (cleared) Banner(s.exportsCleared, Tone.Ok)
        SListRow(
            s.clearExports,
            icon = SIcons.Delete,
            onClick = {
                val dir = File(context.cacheDir, "exports")
                cleared = dir.listFiles()?.all { it.delete() } ?: true
            },
        )
    }
}

@Composable
fun HelpAboutScreen(onBack: () -> Unit) {
    val s = LocalStrings.current
    ScreenScaffold(title = s.helpTitle, onBack = onBack) {
        SCard {
            Text(s.howItWorks, style = SType.CardTitle)
            VSpace(6)
            Text(s.howItWorksBody, style = SType.Body)
        }
        listOf(
            s.faqNoAlertQ to s.faqNoAlertA,
            s.faqOfflineQ to s.faqOfflineA,
            s.faqFalseQ to s.faqFalseA,
        ).forEach { (q, a) -> FaqItem(q, a) }
        Banner(s.disclaimer, Tone.Warn)
        SCard {
            Text(s.about, style = SType.CardTitle)
            VSpace(4)
            InfoLine(s.appName, s.tagline)
            InfoLine(s.version, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})${if (BuildConfig.DEBUG) " · debug" else ""}")
        }
    }
}

@Composable
private fun FaqItem(q: String, a: String) {
    var open by rememberSaveable(q) { mutableStateOf(false) }
    SCard(onClick = { open = !open }) {
        androidx.compose.foundation.layout.Row {
            Text(q, style = SType.BodyStrong, color = tint(SColors.TextPrimary), modifier = Modifier.weight(1f))
            Icon(if (open) SIcons.Up else SIcons.Down, null, tint = tint(SColors.TextSecondary))
        }
        if (open) {
            VSpace(8)
            Text(a, style = SType.Body, color = tint(SColors.TextSecondary))
        }
    }
}
