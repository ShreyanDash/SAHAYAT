package com.sahayat.app.core

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.messaging.FirebaseMessaging
import com.sahayat.app.BuildConfig
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * Single access point to Firebase. If google-services.json was not bundled, FirebaseApp is
 * never initialised and [isConfigured] is false — services then return
 * [AppError.BackendNotConfigured] instead of crashing or faking data.
 */
class Backend(private val context: Context) {

    val isConfigured: Boolean by lazy {
        runCatching { FirebaseApp.getApps(context).isNotEmpty() || FirebaseApp.initializeApp(context) != null }
            .getOrDefault(false)
    }

    val auth: FirebaseAuth? by lazy {
        if (!isConfigured) null else FirebaseAuth.getInstance().also {
            if (BuildConfig.DEBUG && BuildConfig.USE_EMULATOR) it.useEmulator(BuildConfig.EMULATOR_HOST, 9099)
        }
    }

    val firestore: FirebaseFirestore? by lazy {
        if (!isConfigured) null else FirebaseFirestore.getInstance().also {
            if (BuildConfig.DEBUG && BuildConfig.USE_EMULATOR) it.useEmulator(BuildConfig.EMULATOR_HOST, 8080)
        }
    }

    val functions: FirebaseFunctions? by lazy {
        if (!isConfigured) null else FirebaseFunctions.getInstance(BuildConfig.FUNCTIONS_REGION).also {
            if (BuildConfig.DEBUG && BuildConfig.USE_EMULATOR) it.useEmulator(BuildConfig.EMULATOR_HOST, 5001)
        }
    }

    val messaging: FirebaseMessaging? by lazy {
        if (!isConfigured) null else runCatching { FirebaseMessaging.getInstance() }.getOrNull()
    }

    /** Invoke a callable Cloud Function with a timeout, mapping all failures to [AppError]. */
    suspend fun call(
        connectivity: ConnectivityMonitor,
        name: String,
        data: Map<String, Any?>,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Outcome<Map<String, Any?>> {
        val fns = functions ?: return Outcome.Failure(AppError.BackendNotConfigured)
        if (!connectivity.isOnline.first()) return Outcome.Failure(AppError.NoInternet)
        return guard(timeoutMs) {
            val result = fns.getHttpsCallable(name).call(data).await()
            @Suppress("UNCHECKED_CAST")
            (result.getData() as? Map<String, Any?>) ?: emptyMap()
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
    }
}

/** Runs [block] with a timeout and converts every exception into an [Outcome.Failure]. */
suspend fun <T> guard(timeoutMs: Long = Backend.DEFAULT_TIMEOUT_MS, block: suspend () -> T): Outcome<T> =
    try {
        Outcome.Success(withTimeout(timeoutMs) { block() })
    } catch (e: TimeoutCancellationException) {
        Outcome.Failure(AppError.Timeout)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Failure(e.toAppError())
    }

fun Throwable.toAppError(): AppError {
    // Never log patient data — only the exception class and code.
    Log.w("Sahayat", "Backend error: ${this::class.java.simpleName}")
    return when (this) {
        is FirebaseNetworkException -> AppError.NoInternet
        is FirebaseAuthUserCollisionException -> AppError.AccountExists
        is FirebaseAuthWeakPasswordException -> AppError.WeakPassword
        is FirebaseAuthInvalidCredentialsException, is FirebaseAuthInvalidUserException -> AppError.InvalidCredentials
        is FirebaseFunctionsException -> when (code) {
            FirebaseFunctionsException.Code.NOT_FOUND -> {
                when ((details as? Map<*, *>)?.get("reason") as? String) {
                    "DEVICE_NOT_FOUND" -> AppError.DeviceNotFound
                    "INVALID_INVITE" -> AppError.InvalidInvite
                    else -> AppError.NotFound
                }
            }
            FirebaseFunctionsException.Code.ALREADY_EXISTS -> AppError.DeviceAlreadyLinked
            FirebaseFunctionsException.Code.INVALID_ARGUMENT -> {
                val reason = (details as? Map<*, *>)?.get("reason") as? String
                when (reason) {
                    "INVALID_DEVICE_ID" -> AppError.InvalidDeviceId
                    "INVALID_PAIRING_CODE" -> AppError.InvalidPairingCode
                    "INVALID_INVITE" -> AppError.InvalidInvite
                    else -> AppError.InvalidInput
                }
            }
            FirebaseFunctionsException.Code.PERMISSION_DENIED -> {
                val reason = (details as? Map<*, *>)?.get("reason") as? String
                if (reason == "INVALID_PAIRING_CODE") AppError.InvalidPairingCode else AppError.PermissionDenied
            }
            FirebaseFunctionsException.Code.UNAUTHENTICATED -> AppError.NotAuthenticated
            FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> AppError.RateLimited
            FirebaseFunctionsException.Code.DEADLINE_EXCEEDED -> AppError.Timeout
            FirebaseFunctionsException.Code.UNAVAILABLE -> AppError.BackendUnavailable
            FirebaseFunctionsException.Code.INTERNAL -> AppError.BackendUnavailable
            else -> AppError.Unknown(code.name)
        }
        is FirebaseFirestoreException -> when (code) {
            FirebaseFirestoreException.Code.PERMISSION_DENIED -> AppError.PermissionDenied
            FirebaseFirestoreException.Code.UNAUTHENTICATED -> AppError.NotAuthenticated
            FirebaseFirestoreException.Code.NOT_FOUND -> AppError.NotFound
            FirebaseFirestoreException.Code.UNAVAILABLE -> AppError.BackendUnavailable
            FirebaseFirestoreException.Code.DEADLINE_EXCEEDED -> AppError.Timeout
            else -> AppError.Unknown(code.name)
        }
        else -> AppError.Unknown(this::class.java.simpleName)
    }
}
