package com.sahayat.app.data.service

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Backend
import com.sahayat.app.core.ConnectivityMonitor
import com.sahayat.app.core.Outcome
import com.sahayat.app.core.Remote
import com.sahayat.app.core.guard
import com.sahayat.app.data.model.DetectionSettings
import com.sahayat.app.data.model.Patient
import com.sahayat.app.data.model.UserProfile
import com.sahayat.app.data.model.toPatient
import com.sahayat.app.data.model.toUserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

data class PatientInput(
    val name: String,
    val age: Int?,
    val bloodGroup: String?,
    val emergencyNotes: String?,
)

/** Caregiver account profile + the patient they look after. */
interface PatientService {
    fun observeProfile(uid: String): Flow<Remote<UserProfile>>
    fun observePatient(patientId: String): Flow<Remote<Patient>>
    suspend fun updateProfile(uid: String, displayName: String, phone: String?): Outcome<Unit>
    suspend fun createPatient(uid: String, input: PatientInput): Outcome<String>
    suspend fun updatePatient(patientId: String, input: PatientInput): Outcome<Unit>
    suspend fun updateDetection(patientId: String, settings: DetectionSettings): Outcome<Unit>
    /** Records whether this account is used on the patient's phone or a caregiver's phone. */
    suspend fun setRole(uid: String, role: AppRole): Outcome<Unit>
}

class FirestorePatientService(
    private val backend: Backend,
    private val connectivity: ConnectivityMonitor,
) : PatientService {

    private val db get() = backend.firestore

    override fun observeProfile(uid: String): Flow<Remote<UserProfile>> {
        val fs = db ?: return unavailable(AppError.BackendNotConfigured)
        val email = backend.auth?.currentUser?.email
        return fs.collection("users").document(uid).observe { it.toUserProfile(email) }
    }

    override fun observePatient(patientId: String): Flow<Remote<Patient>> {
        val fs = db ?: return unavailable(AppError.BackendNotConfigured)
        return fs.collection("patients").document(patientId).observe { it.toPatient() }
    }

    private suspend fun write(block: suspend () -> Unit): Outcome<Unit> {
        if (db == null) return Outcome.Failure(AppError.BackendNotConfigured)
        if (!connectivity.isOnline.first()) return Outcome.Failure(AppError.NoInternet)
        return guard { block() }
    }

    override suspend fun updateProfile(uid: String, displayName: String, phone: String?): Outcome<Unit> {
        if (displayName.isBlank()) return Outcome.Failure(AppError.InvalidInput)
        return write {
            db!!.collection("users").document(uid).set(
                mapOf("displayName" to displayName.trim(), "phone" to phone?.trim()?.ifBlank { null }),
                SetOptions.merge(),
            ).await()
        }
    }

    private fun PatientInput.toMap(): Map<String, Any?> = mapOf(
        "name" to name.trim(),
        "age" to age,
        "bloodGroup" to bloodGroup?.trim()?.ifBlank { null },
        "emergencyNotes" to emergencyNotes?.trim()?.ifBlank { null },
        "updatedAt" to FieldValue.serverTimestamp(),
    )

    override suspend fun createPatient(uid: String, input: PatientInput): Outcome<String> {
        if (input.name.isBlank()) return Outcome.Failure(AppError.InvalidInput)
        if (db == null) return Outcome.Failure(AppError.BackendNotConfigured)
        if (!connectivity.isOnline.first()) return Outcome.Failure(AppError.NoInternet)
        return guard {
            val fs = db!!
            val ref = fs.collection("patients").document()
            val batch = fs.batch()
            batch.set(
                ref,
                input.toMap() + mapOf(
                    "ownerUid" to uid,
                    "caregiverUids" to listOf(uid),
                    "detection" to mapOf(
                        "sensitivity" to "MEDIUM",
                        "escalationTimeoutSec" to 60,
                        "smsFallbackEnabled" to true,
                    ),
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
            )
            batch.set(
                fs.collection("users").document(uid),
                mapOf("activePatientId" to ref.id, "patientIds" to FieldValue.arrayUnion(ref.id)),
                SetOptions.merge(),
            )
            batch.commit().await()
            ref.id
        }
    }

    override suspend fun updatePatient(patientId: String, input: PatientInput): Outcome<Unit> {
        if (input.name.isBlank()) return Outcome.Failure(AppError.InvalidInput)
        return write { db!!.collection("patients").document(patientId).update(input.toMap()).await() }
    }

    override suspend fun setRole(uid: String, role: AppRole): Outcome<Unit> = write {
        db!!.collection("users").document(uid).set(mapOf("role" to role.name), SetOptions.merge()).await()
    }

    override suspend fun updateDetection(patientId: String, settings: DetectionSettings): Outcome<Unit> {
        if (settings.escalationTimeoutSec !in 15..600) return Outcome.Failure(AppError.InvalidInput)
        return write {
            db!!.collection("patients").document(patientId).update(
                mapOf(
                    "detection" to mapOf(
                        "sensitivity" to settings.sensitivity.name,
                        "escalationTimeoutSec" to settings.escalationTimeoutSec,
                        "smsFallbackEnabled" to settings.smsFallbackEnabled,
                    ),
                    // Backend pushes the new threshold to the device on its next heartbeat.
                    "detectionUpdatedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
        }
    }
}
