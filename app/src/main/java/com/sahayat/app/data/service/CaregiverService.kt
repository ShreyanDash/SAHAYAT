package com.sahayat.app.data.service

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Backend
import com.sahayat.app.core.ConnectivityMonitor
import com.sahayat.app.core.Outcome
import com.sahayat.app.core.Remote
import com.sahayat.app.core.guard
import com.sahayat.app.data.model.Caregiver
import com.sahayat.app.data.model.toCaregiver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

data class CaregiverInput(
    val name: String,
    val phone: String,
    val relationship: String,
    val isPrimary: Boolean,
    val notifyPush: Boolean,
    val notifySms: Boolean,
)

interface CaregiverService {
    fun observeCaregivers(patientId: String): Flow<Remote<List<Caregiver>>>
    suspend fun get(patientId: String, caregiverId: String): Outcome<Caregiver>
    suspend fun add(patientId: String, input: CaregiverInput, priority: Int): Outcome<String>
    suspend fun update(patientId: String, caregiverId: String, input: CaregiverInput): Outcome<Unit>
    suspend fun delete(patientId: String, caregiverId: String): Outcome<Unit>
    /** Persists a new escalation order; index 0 is contacted first. */
    suspend fun reorder(patientId: String, orderedIds: List<String>): Outcome<Unit>

    companion object {
        private val PHONE = Regex("^\\+?[0-9]{10,15}$")
        fun normalizePhone(raw: String) = raw.filter { it.isDigit() || it == '+' }
        fun isValidPhone(raw: String) = PHONE.matches(normalizePhone(raw))
    }
}

class FirestoreCaregiverService(
    private val backend: Backend,
    private val connectivity: ConnectivityMonitor,
) : CaregiverService {

    private fun col(patientId: String) =
        backend.firestore!!.collection("patients").document(patientId).collection("caregivers")

    override fun observeCaregivers(patientId: String): Flow<Remote<List<Caregiver>>> {
        if (backend.firestore == null) return unavailable(AppError.BackendNotConfigured)
        return col(patientId).orderBy("priority", Query.Direction.ASCENDING).observeList { it.toCaregiver() }
    }

    private suspend fun <T> op(block: suspend () -> T): Outcome<T> {
        if (backend.firestore == null) return Outcome.Failure(AppError.BackendNotConfigured)
        if (!connectivity.isOnline.first()) return Outcome.Failure(AppError.NoInternet)
        return guard { block() }
    }

    private fun CaregiverInput.validate(): Boolean =
        name.isNotBlank() && CaregiverService.isValidPhone(phone)

    private fun CaregiverInput.toMap(): Map<String, Any?> = mapOf(
        "name" to name.trim(),
        "phone" to CaregiverService.normalizePhone(phone),
        "relationship" to relationship.trim(),
        "isPrimary" to isPrimary,
        "notifyPush" to notifyPush,
        "notifySms" to notifySms,
        "updatedAt" to FieldValue.serverTimestamp(),
    )

    override suspend fun get(patientId: String, caregiverId: String): Outcome<Caregiver> {
        val r = op {
            val snap = col(patientId).document(caregiverId).get().await()
            if (snap.exists()) snap.toCaregiver() else null
        }
        return when (r) {
            is Outcome.Failure -> r
            is Outcome.Success -> r.value?.let { Outcome.Success(it) } ?: Outcome.Failure(AppError.NotFound)
        }
    }

    /** Only one primary caregiver: clear the flag on the others in the same batch. */
    private suspend fun clearOtherPrimaries(patientId: String, keepId: String, batch: com.google.firebase.firestore.WriteBatch) {
        val others = col(patientId).whereEqualTo("isPrimary", true).get().await()
        others.documents.filter { it.id != keepId }.forEach { batch.update(it.reference, "isPrimary", false) }
    }

    override suspend fun add(patientId: String, input: CaregiverInput, priority: Int): Outcome<String> {
        if (!input.validate()) return Outcome.Failure(AppError.InvalidInput)
        return op {
            val ref = col(patientId).document()
            val batch = backend.firestore!!.batch()
            if (input.isPrimary) clearOtherPrimaries(patientId, ref.id, batch)
            batch.set(ref, input.toMap() + mapOf("priority" to priority, "createdAt" to FieldValue.serverTimestamp()))
            batch.commit().await()
            ref.id
        }
    }

    override suspend fun update(patientId: String, caregiverId: String, input: CaregiverInput): Outcome<Unit> {
        if (!input.validate()) return Outcome.Failure(AppError.InvalidInput)
        return op {
            val batch = backend.firestore!!.batch()
            if (input.isPrimary) clearOtherPrimaries(patientId, caregiverId, batch)
            batch.update(col(patientId).document(caregiverId), input.toMap())
            batch.commit().await()
            Unit
        }
    }

    override suspend fun delete(patientId: String, caregiverId: String): Outcome<Unit> = op {
        col(patientId).document(caregiverId).delete().await()
        Unit
    }

    override suspend fun reorder(patientId: String, orderedIds: List<String>): Outcome<Unit> = op {
        val batch = backend.firestore!!.batch()
        orderedIds.forEachIndexed { index, id -> batch.update(col(patientId).document(id), "priority", index) }
        batch.commit().await()
        Unit
    }
}
