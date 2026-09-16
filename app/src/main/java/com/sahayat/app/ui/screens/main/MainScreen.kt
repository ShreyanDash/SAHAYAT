package com.sahayat.app.ui.screens.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.sahayat.app.ui.LocalContainer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sahayat.app.ui.components.InteractiveSurface
import com.sahayat.app.ui.components.LocalInTabs
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.tint
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.navigation.MainTab
import com.sahayat.app.ui.navigation.Routes
import com.sahayat.app.ui.screens.contacts.ContactsScreen
import com.sahayat.app.ui.screens.history.HistoryScreen
import com.sahayat.app.ui.screens.home.HomeScreen
import com.sahayat.app.ui.screens.monitoring.LiveMonitoringScreen
import com.sahayat.app.ui.screens.settings.SettingsScreen
import com.sahayat.app.ui.theme.SColors
import com.sahayat.app.ui.theme.SShapes
import com.sahayat.app.ui.theme.SType

@Composable
fun MainScreen(navigate: (String) -> Unit) {
    val s = LocalStrings.current
    var tab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    val openTab: (MainTab) -> Unit = { tab = it }

    // Emergency alerts can't be shown without notification permission (Android 13+): ask once per launch.
    val context = LocalContext.current
    val container = LocalContainer.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        container.notificationService.refreshReadiness()
    }
    LaunchedEffect(Unit) {
        if (!asked && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            asked = true
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(Modifier.fillMaxSize().background(SColors.background)) {
        Box(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalInTabs provides true) {
                when (tab) {
                    MainTab.HOME -> HomeScreen(navigate, openTab)
                    MainTab.MONITOR -> LiveMonitoringScreen(navigate)
                    MainTab.HISTORY -> HistoryScreen(
                        onOpen = { navigate(Routes.historyDetail(it)) },
                        onExport = { navigate(Routes.EXPORT) },
                    )
                    MainTab.CONTACTS -> ContactsScreen(
                        onAdd = { navigate(Routes.contactEdit(null)) },
                        onEdit = { navigate(Routes.contactEdit(it)) },
                    )
                    MainTab.SETTINGS -> SettingsScreen(navigate, openTab)
                }
            }
        }
        // Floating glossy pill navigation, icons only (labels kept for accessibility).
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .clip(SShapes.Pill)
                .background(SColors.Card)
                .background(SColors.cardGloss)
                .border(1.dp, SColors.Stroke, SShapes.Pill)
                .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val items = listOf(
                Triple(MainTab.HOME, SIcons.Home, s.dashboard),
                Triple(MainTab.MONITOR, SIcons.Pulse, s.liveMonitoring),
                Triple(MainTab.HISTORY, SIcons.History, s.historyTitle),
                Triple(MainTab.CONTACTS, SIcons.People, s.contactsTitle),
                Triple(MainTab.SETTINGS, SIcons.Settings, s.settingsTitle),
            )
            items.forEach { (t, icon, label) ->
                NavItem(icon, label, selected = tab == t, onClick = { tab = t })
            }
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    InteractiveSurface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) SColors.Chip else Color.Transparent,
        border = if (selected) BorderStroke(1.dp, SColors.Stroke) else null,
        role = Role.Tab,
        modifier = Modifier.size(52.dp),
    ) {
        Icon(
            icon,
            label,
            tint = tint(if (selected) SColors.TextPrimary else SColors.TextSecondary),
            modifier = Modifier.align(Alignment.Center).size(24.dp),
        )
    }
}
