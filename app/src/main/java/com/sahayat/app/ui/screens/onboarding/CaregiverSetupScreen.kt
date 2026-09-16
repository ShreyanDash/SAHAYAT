package com.sahayat.app.ui.screens.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahayat.app.core.Remote
import com.sahayat.app.core.valueOrNull
import com.sahayat.app.ui.components.ButtonKind
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.sahayatViewModel
import com.sahayat.app.ui.screens.contacts.CaregiverList
import com.sahayat.app.ui.screens.contacts.ContactsViewModel

@Composable
fun CaregiverSetupScreen(onAddCaregiver: () -> Unit, onEditCaregiver: (String) -> Unit, onContinue: () -> Unit) {
    val s = LocalStrings.current
    val vm = sahayatViewModel { ContactsViewModel(it.caregiverService, it.session) }
    val list by vm.list.collectAsStateWithLifecycle()
    val hasAny = list.valueOrNull?.isNotEmpty() == true

    ScreenScaffold(
        title = s.caregiverSetupTitle,
        subtitle = s.caregiverSetupSubtitle,
        bottom = {
            SButton(s.continueLabel, onContinue, Modifier.fillMaxWidth(), enabled = hasAny)
            if (!hasAny) SButton(s.skip, onContinue, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
        },
    ) {
        CaregiverList(vm, onAddCaregiver, onEditCaregiver)
    }
}
