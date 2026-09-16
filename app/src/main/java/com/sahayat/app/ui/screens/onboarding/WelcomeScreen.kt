package com.sahayat.app.ui.screens.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.sahayat.app.R
import com.sahayat.app.ui.LocalContainer
import com.sahayat.app.ui.components.Banner
import com.sahayat.app.ui.components.ButtonKind
import com.sahayat.app.ui.components.GradientBackground
import com.sahayat.app.ui.components.HSpace
import com.sahayat.app.ui.components.IconBadge
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.Tone
import com.sahayat.app.ui.components.VSpace
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.theme.SDimens
import com.sahayat.app.ui.theme.SType
import kotlinx.coroutines.launch

@Composable
fun WelcomeScreen(onCreateAccount: () -> Unit, onSignIn: () -> Unit, onPreview: () -> Unit) {
    val s = LocalStrings.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()

    GradientBackground {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .navigationBarsPadding()
                .padding(SDimens.ScreenPadding),
        ) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                VSpace(24)
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(84.dp))
                VSpace(20)
                Text(s.welcomeTitle, style = SType.TitleLarge)
                VSpace(10)
                Text(s.welcomeBody, style = SType.Body)
                VSpace(28)
                Point(SIcons.Sensor, s.welcomePoint1)
                Point(SIcons.Alert, s.welcomePoint2)
                Point(SIcons.Sms, s.welcomePoint3)
                if (!container.backend.isConfigured) {
                    VSpace(16)
                    Banner(s.backendBanner, Tone.Warn, icon = SIcons.CloudOff)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SButton(s.getStarted, onCreateAccount, Modifier.fillMaxWidth(), enabled = container.backend.isConfigured)
                SButton(s.signIn, onSignIn, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, enabled = container.backend.isConfigured)
                SButton(
                    s.previewMode,
                    {
                        scope.launch {
                            container.settings.setPreviewMode(true)
                            onPreview()
                        }
                    },
                    Modifier.fillMaxWidth(),
                    kind = ButtonKind.Ghost,
                )
                Text(s.previewModeNote, style = SType.Caption, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
    }
}

@Composable
private fun Point(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon)
        HSpace(14)
        Text(text, style = SType.BodyStrong)
    }
}
