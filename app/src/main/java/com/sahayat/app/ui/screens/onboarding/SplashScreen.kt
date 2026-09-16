package com.sahayat.app.ui.screens.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.sahayat.app.R
import com.sahayat.app.data.SessionPhase
import com.sahayat.app.data.service.AppRole
import com.sahayat.app.ui.LocalContainer
import com.sahayat.app.ui.components.GradientBackground
import com.sahayat.app.ui.components.VSpace
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.navigation.Routes
import com.sahayat.app.ui.theme.SType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun SplashScreen(onRoute: (String) -> Unit) {
    val container = LocalContainer.current
    val s = LocalStrings.current

    LaunchedEffect(Unit) {
        val minShow = System.currentTimeMillis() + 700
        // Wait for auth/profile to resolve, but never block the app on a slow network.
        val state = withTimeoutOrNull(3_500) {
            container.session.state.first { it.phase != SessionPhase.STARTING }
        } ?: container.session.state.value
        val settings = container.settings.current()
        val profile = container.settings.currentProfile()
        delay((minShow - System.currentTimeMillis()).coerceAtLeast(0))
        val caregiver = profile.role == AppRole.CAREGIVER
        val dest = if (!profile.completed || profile.role == null) Routes.profileSetup(edit = false) else when (state.phase) {
            SessionPhase.SIGNED_OUT -> Routes.WELCOME
            SessionPhase.PREVIEW -> Routes.MAIN
            SessionPhase.NEEDS_PATIENT -> if (caregiver) Routes.joinPatient(onboarding = true) else Routes.PATIENT_SETUP
            SessionPhase.READY -> when {
                settings.onboardingComplete || state.deviceId != null || caregiver -> Routes.MAIN
                else -> Routes.CAREGIVER_SETUP
            }
            SessionPhase.STARTING -> if (state.uid != null) Routes.MAIN else Routes.WELCOME
        }
        onRoute(dest)
    }

    GradientBackground {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(120.dp))
            VSpace(8)
            Text(s.appName, style = SType.TitleLarge)
            VSpace(4)
            Text(s.tagline, style = SType.Body)
        }
    }
}
