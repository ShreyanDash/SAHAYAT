package com.sahayat.app.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sahayat.app.BuildConfig
import com.sahayat.app.core.valueOrNull
import com.sahayat.app.data.model.Sensitivity
import com.sahayat.app.data.service.LocalSettings
import com.sahayat.app.ble.BandLinkPhase
import com.sahayat.app.ble.BandLinkService
import com.sahayat.app.data.service.AppRole
import com.sahayat.app.emergency.AlertWatchService
import com.sahayat.app.emergency.EmergencyAlertService
import com.sahayat.app.ui.i18n.AppStrings
import com.sahayat.app.ui.LocalContainer
import com.sahayat.app.ui.components.ConfirmDialog
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.SListRow
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.SectionLabel
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.navigation.MainTab
import com.sahayat.app.ui.navigation.Routes
import com.sahayat.app.ui.sahayatViewModel
import com.sahayat.app.ui.screens.home.DeviceStatusViewModel
import com.sahayat.app.ui.screens.home.DeviceView
import com.sahayat.app.ui.theme.SColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(navigate: (String) -> Unit, onOpenTab: (MainTab) -> Unit) {
    val s = LocalStrings.current
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settings.settings.collectAsStateWithLifecycle(LocalSettings())
    val deviceVm = sahayatViewModel { DeviceStatusViewModel(it.session, it.deviceService, it.alertService, it.notificationService) }
    val dashboard by deviceVm.state.collectAsStateWithLifecycle()
    val session = dashboard.session
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    val patient = session.patient.valueOrNull

    // Device subtitle is built only from what the band actually reported.
    val bandSubtitle = when (val d = dashboard.device) {
        is DeviceView.Connected -> listOfNotNull(
            d.status.firmware?.let { "${s.firmwareShort} $it" },
            d.status.batteryPercent?.let { "${s.battery} $it%" },
        ).joinToString(" · ").ifBlank { s.connected }
        is DeviceView.Disconnected -> s.deviceNotConnected
        DeviceView.NotPaired -> s.noDevicePaired
        is DeviceView.Unavailable -> s.unavailable
        DeviceView.Loading -> s.loading
    }

    val alertSoundSubtitle = when {
        settings.emergencySound && settings.emergencyVibration -> s.soundAndVibrationOn
        settings.emergencySound -> s.soundOnly
        settings.emergencyVibration -> s.vibrationOnly
        else -> s.alertsSilent
    }

    val profile by container.settings.profile.collectAsStateWithLifecycle(null)
    val link by container.bandLink.state.collectAsStateWithLifecycle()
    val role = profile?.role
    val linkSubtitle = bandLinkLabel(s, link.phase)

    ScreenScaffold(title = s.settingsTitle, eyebrow = s.deviceAndAccount) {
        SectionLabel(s.monitoringSection)
        SListRow(
            s.detectionSensitivity,
            icon = SIcons.Pulse,
            subtitle = when (patient?.detection?.sensitivity) {
                Sensitivity.LOW -> s.sensLowShort
                Sensitivity.MEDIUM -> s.sensMediumShort
                Sensitivity.HIGH -> s.sensHighShort
                null -> s.unavailable
            },
            onClick = { navigate(Routes.DETECTION) },
        )
        SListRow(
            s.alertSound,
            icon = SIcons.Sound,
            subtitle = alertSoundSubtitle,
            onClick = { navigate(Routes.NOTIFICATION_SETTINGS) },
        )

        SectionLabel(s.deviceTitle)
        SListRow(s.bandName, icon = SIcons.Band, subtitle = bandSubtitle, onClick = { navigate(Routes.DEVICE) })
        if (role == AppRole.PATIENT) {
            SListRow(
                s.bandLink,
                icon = if (link.connected) SIcons.BluetoothConnected else SIcons.Bluetooth,
                subtitle = listOfNotNull(link.name, linkSubtitle).joinToString(" · "),
                onClick = { navigate(Routes.pairing(onboarding = false)) },
            )
        }
        SListRow(s.connectionDiagnostics, icon = SIcons.Signal, subtitle = s.wifiAndCellular, onClick = { navigate(Routes.DIAGNOSTICS) })

        SectionLabel(s.caregivers)
        SListRow(s.inviteCaregiver, icon = SIcons.PersonAdd, subtitle = s.inviteSubtitle, onClick = { navigate(Routes.INVITE) })
        if (role != AppRole.PATIENT) {
            SListRow(s.joinPatient, icon = SIcons.Link, subtitle = s.joinSubtitle, onClick = { navigate(Routes.joinPatient(onboarding = false)) })
        }

        SectionLabel(s.preferences)
        SListRow(s.notifications, icon = SIcons.Notifications, subtitle = s.notificationsSubtitle, onClick = { navigate(Routes.NOTIFICATION_SETTINGS) })
        SListRow(s.language, icon = SIcons.Language, subtitle = s.languageName, onClick = { navigate(Routes.LANGUAGE) })
        SListRow(s.exportLogs, icon = SIcons.Pdf, subtitle = s.exportSubtitle, onClick = { navigate(Routes.EXPORT) })
        SListRow(s.privacy, icon = SIcons.Shield, subtitle = s.privacySubtitle, onClick = { navigate(Routes.PRIVACY) })
        SListRow(s.helpAbout, icon = SIcons.Info, subtitle = s.helpSubtitle, onClick = { navigate(Routes.HELP) })

        SectionLabel(s.account)
        SListRow(
            s.profile,
            icon = SIcons.Person,
            subtitle = session.profile?.displayName?.ifBlank { null } ?: s.profileSubtitle,
            onClick = { navigate(Routes.PROFILE) },
        )
        SListRow(
            s.phoneRole,
            icon = SIcons.Phone,
            subtitle = when (role) {
                AppRole.PATIENT -> s.rolePatient
                AppRole.CAREGIVER -> s.roleCaregiver
                null -> s.roleRequired
            },
            onClick = { navigate(Routes.profileSetup(edit = true)) },
        )
        SListRow(s.caregivers, icon = SIcons.People, subtitle = s.caregiversSubtitle, onClick = { onOpenTab(MainTab.CONTACTS) })
        if (session.isPreview) {
            SListRow(s.exitPreview, icon = SIcons.Logout, onClick = { scope.launch { container.settings.setPreviewMode(false) } })
        } else if (session.uid != null) {
            SListRow(s.signOut, icon = SIcons.Logout, iconColor = SColors.Danger, onClick = { confirmSignOut = true })
        }

        if (BuildConfig.ENABLE_TEST_ALERTS) {
            SectionLabel(s.developer)
            SListRow(
                s.testAlert, icon = SIcons.Test, iconColor = SColors.Warning, subtitle = s.testAlertDesc,
                onClick = {
                    val e = container.testAlerts.create(patient?.id, patient?.name, session.deviceId)
                    EmergencyAlertService.start(context, e.id, e.patientId, e.patientName, isTest = true)
                    navigate(Routes.sos(e.id))
                },
            )
        }
    }

    if (confirmSignOut) {
        ConfirmDialog(
            title = s.signOut,
            text = s.signOutConfirm,
            confirmLabel = s.signOut,
            danger = true,
            onConfirm = {
                confirmSignOut = false
                BandLinkService.stop(context)
                AlertWatchService.stop(context)
                container.bandLink.onUnpaired()
                container.session.signOut()
            },
            onDismiss = { confirmSignOut = false },
        )
    }
}

fun bandLinkLabel(s: AppStrings, phase: BandLinkPhase): String = when (phase) {
    BandLinkPhase.NOT_PAIRED -> s.bandLinkNotPaired
    BandLinkPhase.BLUETOOTH_OFF -> s.bandLinkOff
    BandLinkPhase.NO_PERMISSION -> s.bandLinkNoPermission
    BandLinkPhase.STOPPED -> s.bandLinkStopped
    BandLinkPhase.CONNECTING -> s.bandLinkConnecting
    BandLinkPhase.CONNECTED -> s.bandLinkConnected
    BandLinkPhase.DISCONNECTED -> s.bandLinkDisconnected
    BandLinkPhase.INCOMPATIBLE -> s.bandIncompatible
}
