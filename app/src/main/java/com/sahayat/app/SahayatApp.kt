package com.sahayat.app

import android.app.Application
import com.sahayat.app.emergency.EmergencyNotifier

class SahayatApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Channels must exist before any FCM message can arrive.
        EmergencyNotifier.ensureChannels(this)
        container = AppContainer(this)
        // OpenStreetMap tiles: identify the app (required by the OSM tile policy); cache stays in app storage.
        runCatching {
            org.osmdroid.config.Configuration.getInstance().apply {
                load(this@SahayatApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
                userAgentValue = packageName
            }
        }
    }
}
