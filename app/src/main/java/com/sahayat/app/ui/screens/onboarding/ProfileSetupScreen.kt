package com.sahayat.app.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.sahayat.app.ble.BandLinkService
import com.sahayat.app.data.service.AppRole
import com.sahayat.app.ui.components.tint
import com.sahayat.app.ui.theme.SColors
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sahayat.app.data.service.CaregiverService
import com.sahayat.app.data.service.LocalProfile
import com.sahayat.app.ui.LocalContainer
import com.sahayat.app.ui.components.HSpace
import com.sahayat.app.ui.components.IconBadge
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.SCard
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.STextField
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.SectionLabel
import com.sahayat.app.ui.components.VSpace
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.theme.SType
import kotlinx.coroutines.launch

/**
 * First-launch profile setup. Asked once, before sign-in, and stored only on this phone.
 * The values pre-fill account creation and the patient profile later.
 * Also reused from Settings → Profile to edit the local profile ([editing] = true).
 */
@Composable
fun ProfileSetupScreen(onDone: () -> Unit, editing: Boolean = false, onBack: (() -> Unit)? = null) {
    val s = LocalStrings.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var loaded by rememberSaveable { mutableStateOf(false) }
    var role by rememberSaveable { mutableStateOf<AppRole?>(null) }
    var cgName by rememberSaveable { mutableStateOf("") }
    var cgPhone by rememberSaveable { mutableStateOf("") }
    var relation by rememberSaveable { mutableStateOf("") }
    var pName by rememberSaveable { mutableStateOf("") }
    var pAge by rememberSaveable { mutableStateOf("") }
    var pBlood by rememberSaveable { mutableStateOf("") }
    var pNotes by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf(false) }
    var saving by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!loaded) {
            val p = container.settings.currentProfile()
            role = p.role
            cgName = p.caregiverName
            cgPhone = p.caregiverPhone
            relation = p.relationship
            pName = p.patientName
            pAge = p.patientAge?.toString().orEmpty()
            pBlood = p.patientBloodGroup
            pNotes = p.emergencyNotes
            loaded = true
        }
    }

    val ageValue = pAge.toIntOrNull()
    val cgNameErr = if (submitted && cgName.isBlank()) s.nameRequired else null
    val phoneErr = if (cgPhone.isNotBlank() && !CaregiverService.isValidPhone(cgPhone)) s.phoneInvalid else null
    val isCaregiver = role == AppRole.CAREGIVER
    val pNameErr = if (submitted && !isCaregiver && pName.isBlank()) s.nameRequired else null
    val ageErr = when {
        isCaregiver -> null
        submitted && pAge.isBlank() -> s.ageRequired
        pAge.isNotBlank() && (ageValue == null || ageValue !in 0..120) -> s.errInvalidInput
        else -> null
    }
    val patientValid = isCaregiver || (pName.isNotBlank() && ageValue != null && ageValue in 0..120)
    val valid = role != null && cgName.isNotBlank() && patientValid && phoneErr == null

    ScreenScaffold(
        title = if (editing) s.profileTitle else s.setupTitle,
        eyebrow = if (editing) s.account else s.setupEyebrow,
        subtitle = s.setupSubtitle,
        onBack = onBack,
        showMenu = editing,
        bottom = {
            SButton(
                if (editing) s.save else s.continueLabel,
                {
                    submitted = true
                    if (valid) {
                        saving = true
                        scope.launch {
                            val previousRole = container.settings.currentProfile().role
                            container.settings.saveProfile(
                                LocalProfile(
                                    completed = true,
                                    role = role,
                                    caregiverName = cgName,
                                    caregiverPhone = cgPhone,
                                    relationship = relation,
                                    patientName = pName,
                                    patientAge = if (isCaregiver) null else ageValue,
                                    patientBloodGroup = pBlood,
                                    emergencyNotes = pNotes,
                                ),
                            )
                            val uid = container.session.state.value.uid
                            val newRole = role
                            if (uid != null && newRole != null) container.patientService.setRole(uid, newRole)
                            if (previousRole == AppRole.PATIENT && newRole == AppRole.CAREGIVER) {
                                BandLinkService.stop(context)
                            }
                            saving = false
                            onDone()
                        }
                    }
                },
                Modifier.fillMaxWidth(),
                loading = saving,
            )
        },
    ) {
        SectionLabel(s.roleSection)
        if (submitted && role == null) Text(s.roleRequired, style = SType.Caption, color = SColors.Danger)
        RoleOption(SIcons.Bluetooth, s.rolePatient, s.rolePatientBody, role == AppRole.PATIENT) { role = AppRole.PATIENT }
        RoleOption(SIcons.Notifications, s.roleCaregiver, s.roleCaregiverBody, role == AppRole.CAREGIVER) { role = AppRole.CAREGIVER }

        SectionLabel(s.aboutYou)
        SCard {
            SectionIntro(SIcons.Person, s.aboutYouBody)
            VSpace(14)
            STextField(cgName, { cgName = it.take(60) }, s.fullName, error = cgNameErr)
            VSpace(10)
            STextField(cgPhone, { cgPhone = it.take(18) }, s.phoneOptional, hint = s.phoneHint, keyboardType = KeyboardType.Phone, error = phoneErr)
            VSpace(10)
            STextField(relation, { relation = it.take(40) }, s.relationshipToPatient, hint = s.relationshipHint)
        }

        if (!isCaregiver) {
        SectionLabel(s.aboutPatient)
        SCard {
            SectionIntro(SIcons.Pulse, s.aboutPatientBody)
            VSpace(14)
            STextField(pName, { pName = it.take(60) }, s.patientName, error = pNameErr)
            VSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                STextField(
                    pAge, { v -> pAge = v.filter { it.isDigit() }.take(3) }, s.age,
                    keyboardType = KeyboardType.Number, error = ageErr, modifier = Modifier.weight(1f),
                )
                STextField(
                    pBlood, { pBlood = it.uppercase().take(4) }, s.bloodGroup,
                    hint = "B+", modifier = Modifier.weight(1f),
                )
            }
            VSpace(10)
            STextField(pNotes, { pNotes = it.take(300) }, s.emergencyNotes, hint = s.emergencyNotesHint, singleLine = false, minLines = 3)
        }
        }
        Text(s.setupPrivacyNote, style = SType.Caption)
    }
}

@Composable
private fun RoleOption(icon: ImageVector, title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    SCard(onClick = onClick, color = if (selected) SColors.CardAlt else SColors.Card) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, if (selected) SColors.Accent else SColors.TextSecondary, size = 44)
            HSpace(14)
            Column(Modifier.weight(1f)) {
                Text(title, style = SType.CardTitle, color = tint(SColors.TextPrimary))
                Text(body, style = SType.Caption, color = tint(SColors.TextSecondary))
            }
            HSpace(8)
            RadioButton(
                selected = selected,
                onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = SColors.Accent, unselectedColor = SColors.TextSecondary),
            )
        }
    }
}

@Composable
private fun SectionIntro(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, size = 44)
        HSpace(14)
        Text(text, style = SType.Body, modifier = Modifier.weight(1f))
    }
}
