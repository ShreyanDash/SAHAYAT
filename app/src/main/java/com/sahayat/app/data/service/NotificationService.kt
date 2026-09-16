package com.sahayat.app.data.service

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.sahayat.app.BuildConfig
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Backend
import com.sahayat.app.core.ConnectivityMonitor
import com.sahayat.app.core.Outcome
import com.sahayat.app.core.guard
import com.sahayat.app.emergency.EmergencyNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/** Snapshot of everything that decides whether an emergency alert can actually reach this phone. */
data class AlertReadiness(
    val backendConfigured: Boolean,
    val playServicesAvailable: Boolean,
    val notificationsAllowed: Boolean,
    val emergencyChannelEnabled: Boolean,
    val fullScreenIntentAllowed: Boolean,
    val dndAccessGranted: Boolean,
    val tokenRegistered: Boolean,
    val lastTokenError: AppError?,
)

interface NotificationService {
    val readiness: StateFlow<AlertReadiness>
    fun refreshReadiness()
    /** Fetches the current FCM token and stores it under users/{uid}/fcmTokens. */
    suspend fun registerCurrentToken(uid: String): Outcome<Unit>
    /** Called from FirebaseMessagingService.onNewToken. */
    suspend fun onTokenRefreshed(uid: String?, token: String): Outcome<Unit>
    /** Removes this phone's token so a signed-out phone stops receiving patient alerts. */
    suspend fun unregisterToken(uid: String)
}

class FcmNotificationService(
    private val context: Context,
    private val backend: Backend,
    private val connectivity: ConnectivityMonitor,
) : NotificationService {

    private val nm = context.getSystemService(NotificationManager::class.java)
    private var tokenRegistered = false
    private var lastTokenError: AppError? = null

    private val _readiness = MutableStateFlow(compute())
    override val readiness: StateFlow<AlertReadiness> = _readiness

    private fun compute(): AlertReadiness {
        val permissionOk = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channel = nm.getNotificationChannel(EmergencyNotifier.CHANNEL_SOS)
        return AlertReadiness(
            backendConfigured = backend.isConfigured,
            playServicesAvailable = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS,
            notificationsAllowed = permissionOk && NotificationManagerCompat.from(context).areNotificationsEnabled(),
            emergencyChannelEnabled = channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE,
            fullScreenIntentAllowed = if (Build.VERSION.SDK_INT >= 34) nm.canUseFullScreenIntent() else true,
            dndAccessGranted = nm.isNotificationPolicyAccessGranted,
            tokenRegistered = tokenRegistered,
            lastTokenError = lastTokenError,
        )
    }

    override fun refreshReadiness() {
        _readiness.value = compute()
    }

    private suspend fun store(uid: String, token: String): Outcome<Unit> {
        val fs = backend.firestore ?: return Outcome.Failure(AppError.BackendNotConfigured)
        return guard {
            fs.collection("users").document(uid).collection("fcmTokens").document(token).set(
                mapOf(
                    "token" to token,
                    "platform" to "android",
                    "appVersion" to BuildConfig.VERSION_NAME,
                    "sdk" to Build.VERSION.SDK_INT,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge(),
            ).await()
            Unit
        }
    }

    override suspend fun registerCurrentToken(uid: String): Outcome<Unit> {
        val messaging = backend.messaging
        val result: Outcome<Unit> = when {
            messaging == null -> Outcome.Failure(if (backend.isConfigured) AppError.FcmUnavailable else AppError.BackendNotConfigured)
            !_readiness.value.playServicesAvailable -> Outcome.Failure(AppError.FcmUnavailable)
            !connectivity.isOnline.first() -> Outcome.Failure(AppError.NoInternet)
            else -> when (val t = guard { messaging.token.await() }) {
                is Outcome.Failure -> Outcome.Failure(AppError.FcmUnavailable)
                is Outcome.Success -> store(uid, t.value)
            }
        }
        tokenRegistered = result is Outcome.Success
        lastTokenError = (result as? Outcome.Failure)?.error
        refreshReadiness()
        return result
    }

    override suspend fun onTokenRefreshed(uid: String?, token: String): Outcome<Unit> {
        if (uid == null) return Outcome.Failure(AppError.NotAuthenticated)
        val r = store(uid, token)
        tokenRegistered = r is Outcome.Success
        lastTokenError = (r as? Outcome.Failure)?.error
        refreshReadiness()
        return r
    }

    override suspend fun unregisterToken(uid: String) {
        val messaging = backend.messaging ?: return
        val fs = backend.firestore ?: return
        runCatching {
            val token = messaging.token.await()
            fs.collection("users").document(uid).collection("fcmTokens").document(token).delete().await()
            messaging.deleteToken().await()
        }.onFailure { Log.w("Sahayat", "Token unregister failed: ${it::class.java.simpleName}") }
        tokenRegistered = false
        refreshReadiness()
    }
}
