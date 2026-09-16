package com.sahayat.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.sahayat.app.ble.BandLinkService
import com.sahayat.app.emergency.AlertWatchService
import com.sahayat.app.data.TestAlertRepository
import kotlinx.coroutines.launch
import com.sahayat.app.emergency.EmergencyNotifier
import com.sahayat.app.ui.SahayatRoot

class MainActivity : ComponentActivity() {

    private val container get() = (application as SahayatApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { SahayatRoot(container) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Patient mode: make sure the Bluetooth relay to the band is running while the app is in use.
        lifecycleScope.launch {
            if (BandLinkService.shouldRun(this@MainActivity)) BandLinkService.start(this@MainActivity)
            if (AlertWatchService.shouldRun(this@MainActivity)) AlertWatchService.start(this@MainActivity)
        }
    }

    override fun onResume() {
        super.onResume()
        container.notificationService.refreshReadiness()
    }

    /** Routes SOS notification taps and sahayat://sos/{eventId} deep links to the SOS screen. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val fromExtra = intent.getStringExtra(EmergencyNotifier.EXTRA_EVENT_ID)
        val fromUri = intent.data?.takeIf { it.scheme == "sahayat" && it.host == "sos" }?.lastPathSegment
        val eventId = (fromExtra ?: fromUri)?.takeIf { ID.matches(it) } ?: return
        if (TestAlertRepository.isTestId(eventId) && !BuildConfig.ENABLE_TEST_ALERTS) return
        // Only an emergency may appear over the lock screen; SosScreen clears this when it closes.
        setSosLockScreenMode(true)
        container.sosInbox.requestOpen(eventId)
    }

    fun setSosLockScreenMode(enabled: Boolean) {
        setShowWhenLocked(enabled)
        setTurnScreenOn(enabled)
    }

    private companion object {
        val ID = Regex("^[A-Za-z0-9_-]{4,128}$")
    }
}
