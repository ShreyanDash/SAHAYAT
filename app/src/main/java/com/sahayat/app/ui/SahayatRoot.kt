package com.sahayat.app.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.sahayat.app.AppContainer
import com.sahayat.app.BuildConfig
import com.sahayat.app.MainActivity
import androidx.compose.ui.platform.LocalContext
import com.sahayat.app.data.SessionPhase
import com.sahayat.app.data.TestAlertRepository
import com.sahayat.app.data.service.LocalSettings
import com.sahayat.app.ui.components.GlobalStatus
import com.sahayat.app.ui.components.LocalGlobalStatus
import com.sahayat.app.ui.components.LocalNavigator
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.i18n.stringsFor
import com.sahayat.app.ui.navigation.Routes
import com.sahayat.app.ui.screens.contacts.ContactEditScreen
import com.sahayat.app.ui.screens.device.DeviceScreen
import com.sahayat.app.ui.screens.device.DiagnosticsScreen
import com.sahayat.app.ui.screens.history.HistoryDetailScreen
import com.sahayat.app.ui.screens.main.MainScreen
import com.sahayat.app.ui.screens.onboarding.AuthScreen
import com.sahayat.app.ui.screens.onboarding.CaregiverSetupScreen
import com.sahayat.app.ui.screens.onboarding.PatientSetupScreen
import com.sahayat.app.ui.screens.onboarding.ProfileSetupScreen
import com.sahayat.app.ui.screens.onboarding.SplashScreen
import com.sahayat.app.ui.screens.onboarding.WelcomeScreen
import com.sahayat.app.ui.screens.care.InviteCaregiverScreen
import com.sahayat.app.ui.screens.map.MapScreen
import com.sahayat.app.ui.screens.care.JoinPatientScreen
import com.sahayat.app.ui.screens.pairing.BandPairingScreen
import com.sahayat.app.ui.screens.settings.DetectionSettingsScreen
import com.sahayat.app.ui.screens.settings.ExportScreen
import com.sahayat.app.ui.screens.settings.HelpAboutScreen
import com.sahayat.app.ui.screens.settings.LanguageScreen
import com.sahayat.app.ui.screens.settings.NotificationSettingsScreen
import com.sahayat.app.ui.screens.settings.PrivacyScreen
import com.sahayat.app.ui.screens.settings.ProfileScreen
import com.sahayat.app.ui.screens.sos.SosScreen
import com.sahayat.app.ui.theme.SahayatTheme

@Composable
fun SahayatRoot(container: AppContainer) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(LocalSettings())
    val session by container.session.state.collectAsStateWithLifecycle()
    val online by container.connectivity.isOnline.collectAsStateWithLifecycle()
    val strings = remember(settings.languageCode) { stringsFor(settings.languageCode) }
    val nav = rememberNavController()
    val activity = LocalContext.current as? MainActivity

    SahayatTheme {
        CompositionLocalProvider(
            LocalContainer provides container,
            LocalStrings provides strings,
            LocalGlobalStatus provides GlobalStatus(
                online = online,
                preview = session.isPreview,
                backendConfigured = session.backendConfigured,
            ),
        ) {
            CompositionLocalProvider(LocalNavigator provides { route: String -> nav.navigate(route) { launchSingleTop = true } }) {
                SahayatNavHost(nav)
            }
            val backStack by nav.currentBackStackEntryAsState()
            val route = backStack?.destination?.route

            // Emergency hand-off: foreground FCM alerts and notification taps open the SOS screen.
            val readyForSos = route != null && route != Routes.SPLASH
            LaunchedEffect(readyForSos) {
                if (!readyForSos) return@LaunchedEffect
                container.sosInbox.openRequests.collect { eventId ->
                    val isTest = TestAlertRepository.isTestId(eventId)
                    val allowed = if (isTest) BuildConfig.ENABLE_TEST_ALERTS else container.session.state.value.uid != null
                    if (allowed) {
                        nav.navigate(Routes.sos(eventId)) { launchSingleTop = true }
                    } else {
                        activity?.setSosLockScreenMode(false)
                    }
                    container.sosInbox.consumeOpenRequest()
                }
            }

            // Signing out (or losing auth) returns to Welcome from anywhere.
            LaunchedEffect(session.phase, route) {
                if (session.phase == SessionPhase.SIGNED_OUT && route != null && route !in Routes.PUBLIC) {
                    nav.navigate(Routes.WELCOME) { popUpTo(0) { inclusive = true } }
                }
            }
        }
    }
}

@Composable
private fun SahayatNavHost(nav: NavHostController) {
    NavHost(
        navController = nav,
        startDestination = Routes.SPLASH,
        enterTransition = { fadeIn(tween(180)) },
        exitTransition = { fadeOut(tween(140)) },
        popEnterTransition = { fadeIn(tween(180)) },
        popExitTransition = { fadeOut(tween(140)) },
    ) {
        val back: () -> Unit = { nav.popBackStack() }

        composable(Routes.SPLASH) {
            SplashScreen(onRoute = { dest -> nav.navigate(dest) { popUpTo(Routes.SPLASH) { inclusive = true } } })
        }
        composable(
            Routes.PROFILE_SETUP,
            arguments = listOf(navArgument("edit") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            val editing = entry.arguments?.getBoolean("edit") ?: false
            ProfileSetupScreen(
                editing = editing,
                onBack = if (editing) back else null,
                onDone = {
                    if (editing) nav.popBackStack()
                    else nav.navigate(Routes.SPLASH) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onCreateAccount = { nav.navigate(Routes.auth(create = true)) },
                onSignIn = { nav.navigate(Routes.auth(create = false)) },
                onPreview = { nav.navigate(Routes.MAIN) { popUpTo(0) { inclusive = true } } },
            )
        }
        composable(Routes.AUTH, arguments = listOf(navArgument("mode") { type = NavType.StringType })) { entry ->
            AuthScreen(
                startInCreateMode = entry.arguments?.getString("mode") == "create",
                onBack = back,
                onRoute = { dest -> nav.navigate(dest) { popUpTo(0) { inclusive = true } } },
            )
        }
        composable(Routes.PATIENT_SETUP) {
            PatientSetupScreen(onDone = { nav.navigate(Routes.CAREGIVER_SETUP) { popUpTo(0) { inclusive = true } } })
        }
        composable(Routes.CAREGIVER_SETUP) {
            CaregiverSetupScreen(
                onAddCaregiver = { nav.navigate(Routes.contactEdit(null)) },
                onEditCaregiver = { nav.navigate(Routes.contactEdit(it)) },
                onContinue = { nav.navigate(Routes.pairing(onboarding = true)) },
            )
        }
        composable(
            Routes.PAIRING,
            arguments = listOf(navArgument("onboarding") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            val onboarding = entry.arguments?.getBoolean("onboarding") ?: false
            val container = LocalContainer.current
            val scope = rememberCoroutineScope()
            BandPairingScreen(
                onboarding = onboarding,
                onBack = back,
                onFinished = {
                    if (onboarding) {
                        scope.launch {
                            container.settings.setOnboardingComplete(true)
                            nav.navigate(Routes.MAIN) { popUpTo(0) { inclusive = true } }
                        }
                    } else {
                        nav.popBackStack()
                    }
                },
                navigate = { nav.navigate(it) { launchSingleTop = true } },
            )
        }
        composable(Routes.INVITE) { InviteCaregiverScreen(onBack = back) }
        composable(
            Routes.MAP,
            arguments = listOf(
                navArgument("lat") { type = NavType.FloatType },
                navArgument("lng") { type = NavType.FloatType },
                navArgument("acc") { type = NavType.FloatType; defaultValue = -1f },
                navArgument("label") { type = NavType.StringType; defaultValue = "" },
                navArgument("eventId") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val a = entry.arguments
            val acc = a?.getFloat("acc")?.takeIf { it > 0f }?.toDouble()
            MapScreen(
                initial = com.sahayat.app.data.model.EventLocation(
                    lat = (a?.getFloat("lat") ?: 0f).toDouble(),
                    lng = (a?.getFloat("lng") ?: 0f).toDouble(),
                    accuracyM = acc,
                    source = null,
                ),
                label = a?.getString("label"),
                eventId = a?.getString("eventId")?.ifBlank { null },
                onBack = back,
            )
        }
        composable(
            Routes.JOIN_PATIENT,
            arguments = listOf(navArgument("onboarding") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            val onboarding = entry.arguments?.getBoolean("onboarding") ?: false
            JoinPatientScreen(
                onBack = if (onboarding) null else back,
                onJoined = { nav.navigate(Routes.MAIN) { popUpTo(0) { inclusive = true } } },
                onCreatePatient = { nav.navigate(Routes.PATIENT_SETUP) },
            )
        }
        composable(Routes.MAIN) {
            MainScreen(
                navigate = { nav.navigate(it) },
            )
        }
        composable(Routes.SOS, arguments = listOf(navArgument("eventId") { type = NavType.StringType })) { entry ->
            SosScreen(
                eventId = entry.arguments?.getString("eventId").orEmpty(),
                onClose = {
                    if (!nav.popBackStack()) nav.navigate(Routes.MAIN) { popUpTo(0) { inclusive = true } }
                },
            )
        }
        composable(Routes.HISTORY_DETAIL, arguments = listOf(navArgument("eventId") { type = NavType.StringType })) { entry ->
            HistoryDetailScreen(
                eventId = entry.arguments?.getString("eventId").orEmpty(),
                onBack = back,
                onOpenSos = { nav.navigate(Routes.sos(it)) },
            )
        }
        composable(
            Routes.CONTACT_EDIT,
            arguments = listOf(navArgument("id") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { entry ->
            ContactEditScreen(caregiverId = entry.arguments?.getString("id"), onDone = back)
        }
        composable(Routes.DEVICE) {
            DeviceScreen(
                onBack = back,
                onDiagnostics = { nav.navigate(Routes.DIAGNOSTICS) },
                onPair = { nav.navigate(Routes.pairing(onboarding = false)) },
            )
        }
        composable(Routes.DIAGNOSTICS) {
            DiagnosticsScreen(onBack = back, onNotificationSettings = { nav.navigate(Routes.NOTIFICATION_SETTINGS) })
        }
        composable(Routes.DETECTION) { DetectionSettingsScreen(onBack = back) }
        composable(Routes.NOTIFICATION_SETTINGS) { NotificationSettingsScreen(onBack = back) }
        composable(Routes.LANGUAGE) { LanguageScreen(onBack = back) }
        composable(Routes.PROFILE) {
            ProfileScreen(onBack = back, onCreatePatient = { nav.navigate(Routes.PATIENT_SETUP) })
        }
        composable(Routes.PRIVACY) { PrivacyScreen(onBack = back) }
        composable(Routes.EXPORT) { ExportScreen(onBack = back) }
        composable(Routes.HELP) { HelpAboutScreen(onBack = back) }
    }
}
