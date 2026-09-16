package com.sahayat.app.data.service

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.userProfileChangeRequest
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Backend
import com.sahayat.app.core.ConnectivityMonitor
import com.sahayat.app.core.Outcome
import com.sahayat.app.core.guard
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

interface AuthService {
    /** Emits the signed-in uid, or null. */
    val uid: Flow<String?>
    val currentUid: String?
    val currentEmail: String?
    suspend fun signIn(email: String, password: String): Outcome<Unit>
    suspend fun register(name: String, email: String, password: String): Outcome<Unit>
    suspend fun sendPasswordReset(email: String): Outcome<Unit>
    fun signOut()
}

class FirebaseAuthService(
    private val backend: Backend,
    private val connectivity: ConnectivityMonitor,
) : AuthService {

    override val uid: Flow<String?> = backend.auth?.let { auth ->
        callbackFlow {
            val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
            auth.addAuthStateListener(listener)
            awaitClose { auth.removeAuthStateListener(listener) }
        }
    } ?: flowOf(null)

    override val currentUid: String? get() = backend.auth?.currentUser?.uid
    override val currentEmail: String? get() = backend.auth?.currentUser?.email

    private suspend fun precheck(): AppError? = when {
        backend.auth == null -> AppError.BackendNotConfigured
        !connectivity.isOnline.first() -> AppError.NoInternet
        else -> null
    }

    override suspend fun signIn(email: String, password: String): Outcome<Unit> {
        precheck()?.let { return Outcome.Failure(it) }
        if (email.isBlank() || password.isBlank()) return Outcome.Failure(AppError.InvalidInput)
        return guard { backend.auth!!.signInWithEmailAndPassword(email.trim(), password).await(); Unit }
    }

    override suspend fun register(name: String, email: String, password: String): Outcome<Unit> {
        precheck()?.let { return Outcome.Failure(it) }
        if (name.isBlank() || email.isBlank() || password.length < 8) return Outcome.Failure(AppError.InvalidInput)
        return guard {
            val result = backend.auth!!.createUserWithEmailAndPassword(email.trim(), password).await()
            val user = result.user ?: error("No user returned")
            user.updateProfile(userProfileChangeRequest { displayName = name.trim() }).await()
            backend.firestore!!.collection("users").document(user.uid).set(
                mapOf(
                    "displayName" to name.trim(),
                    "email" to user.email,
                    "role" to "caregiver",
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
                SetOptions.merge(),
            ).await()
            Unit
        }
    }

    override suspend fun sendPasswordReset(email: String): Outcome<Unit> {
        precheck()?.let { return Outcome.Failure(it) }
        if (email.isBlank()) return Outcome.Failure(AppError.InvalidInput)
        return guard { backend.auth!!.sendPasswordResetEmail(email.trim()).await(); Unit }
    }

    override fun signOut() {
        backend.auth?.signOut()
    }
}
