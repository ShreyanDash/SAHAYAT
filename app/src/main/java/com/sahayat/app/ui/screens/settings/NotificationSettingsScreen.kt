package com.sahayat.app.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahayat.app.BuildConfig
import com.sahayat.app.core.Outcome
import com.sahayat.app.data.service.LocalSettings
import com.sahayat.app.emergency.EmergencyNotifier
import com.sahayat.app.ui.LocalContainer
import com.sahayat.app.ui.components.ActionState
import com.sahayat.app.ui.components.ActionStateBanner
import com.sahayat.app.ui.components.Banner
import com.sahayat.app.ui.components.ButtonKind
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.SListRow
import com.sahayat.app.ui.components.SSwitchRow
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.SectionLabel
import com.sahayat.app.ui.components.Tone
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.theme.SColors
import com.sahayat.app.ui.theme.SType
import kotlinx.coroutines.launch

private fun Context.open(intent: Intent) {
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
}

@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val container = LocalContainer.current
    val scope = rememberCoroutineScope()
    val readiness by container.notificationService.readiness.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(LocalSettings())
    val session by container.session.state.collectAsStateWithLifecycle()
    var register by remember { mutableStateOf<ActionState>(ActionState.Idle) }
    var dndBefore by remember { mutableStateOf(readiness.dndAccessGranted) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        container.notificationService.refreshReadiness()
    }

    // Returning from system settings: re-check everything, and rebuild channels if DND access was just granted.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        container.notificationService.refreshReadiness()
    }
    LaunchedEffect(readiness.dndAccessGranted) {
        if (readiness.dndAccessGranted && !dndBefore) {
            EmergencyNotifier.recreateEmergencyChannels(context)
            container.notificationService.refreshReadiness()
        }
        dndBefore = readiness.dndAccessGranted
    }

    val appSettings = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    ScreenScaffold(title = s.notifTitle, onBack = onBack) {
        ActionStateBanner(register, s.registered, onDismiss = { register = ActionState.Idle })

        SectionLabel(s.alertReadiness)
        SListRow(
            s.notificationPermission,
            icon = SIcons.Notifications,
            iconColor = if (readiness.notificationsAllowed) SColors.Accent else SColors.Danger,
            value = if (readiness.notificationsAllowed) s.allowed else s.notAllowed,
            onClick = {
                if (!readiness.notificationsAllowed && Build.VERSION.SDK_INT >= 33) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.open(appSettings)
                }
            },
        )
        SListRow(
            s.emergencyChannel,
            icon = SIcons.Alert,
            iconColor = if (readiness.emergencyChannelEnabled) SColors.Accent else SColors.Danger,
            value = if (readiness.emergencyChannelEnabled) s.on else s.off,
            onClick = {
                context.open(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, EmergencyNotifier.CHANNEL_SOS),
                )
            },
        )
        SListRow(
            s.fullScreenAlerts,
            subtitle = s.fullScreenBody,
            icon = SIcons.Lock,
            iconColor = if (readiness.fullScreenIntentAllowed) SColors.Accent else SColors.Warning,
            value = if (readiness.fullScreenIntentAllowed) s.allowed else s.notAllowed,
            onClick = {
                if (Build.VERSION.SDK_INT >= 34) {
                    context.open(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
                } else {
                    context.open(appSettings)
                }
            },
        )
        SListRow(
            s.dndAccess,
            subtitle = s.dndBody,
            icon = SIcons.Shield,
            iconColor = if (readiness.dndAccessGranted) SColors.Accent else SColors.TextSecondary,
            value = if (readiness.dndAccessGranted) s.allowed else s.notAllowed,
            onClick = { context.open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
        )
        SListRow(
            s.pushRegistration,
            icon = SIcons.Sync,
            iconColor = if (readiness.tokenRegistered) SColors.Accent else SColors.Warning,
            value = when {
                !readiness.backendConfigured -> s.notConfigured
                !readiness.playServicesAvailable -> s.unavailable
                readiness.tokenRegistered -> s.registered
                else -> s.notRegistered
            },
            subtitle = readiness.lastTokenError?.let { s.error(it) },
        )
        if (!readiness.tokenRegistered && session.uid != null) {
            SButton(
                s.reRegister,
                {
                    val uid = session.uid
                    if (uid != null) {
                        register = ActionState.Busy
                        scope.launch {
                            register = when (val r = container.notificationService.registerCurrentToken(uid)) {
                                is Outcome.Success -> ActionState.Done
                                is Outcome.Failure -> ActionState.Failed(r.error)
                            }
                        }
                    }
                },
                Modifier.fillMaxWidth(),
                kind = ButtonKind.Secondary,
                loading = register is ActionState.Busy,
            )
        }

        SectionLabel(s.emergencySound)
        SSwitchRow(s.emergencySound, settings.emergencySound, { v -> scope.launch { container.settings.setEmergencySound(v) } }, subtitle = s.soundBody, icon = SIcons.Sound)
        SSwitchRow(s.emergencyVibration, settings.emergencyVibration, { v -> scope.launch { container.settings.setEmergencyVibration(v) } }, subtitle = s.vibrationBody, icon = SIcons.Vibration)
        SListRow(
            s.generalUpdates,
            icon = SIcons.Info,
            onClick = {
                context.open(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, EmergencyNotifier.CHANNEL_GENERAL),
                )
            },
        )
        Banner(s.osLimitsNote, Tone.Neutral)
        if (BuildConfig.ENABLE_TEST_ALERTS) {
            Text("${s.developer}: ${s.testAlertDesc}", style = SType.Caption)
        }
    }
}
