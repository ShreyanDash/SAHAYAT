package com.sahayat.app.data.service

import com.google.firebase.firestore.Query
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Backend
import com.sahayat.app.core.ConnectivityMonitor
import com.sahayat.app.core.Outcome
import com.sahayat.app.core.Remote
import com.sahayat.app.core.guard
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import com.sahayat.app.data.model.SeizureEvent
import com.sahayat.app.data.model.SosStatus
import com.sahayat.app.data.model.toSeizureEvent
import kotlinx.coroutines.flow.Flow

/**
 * SOS event access. Transitions run as Firestore transactions; firestore.rules only allows the
 * legal SOS state-machine moves, so every phone sees the same, server-stored status.
 */
interface AlertService {
    fun observeEvent(eventId: String): Flow<Remote<SeizureEvent>>
    fun observeOpenEvents(patientId: String): Flow<Remote<List<SeizureEvent>>>
    suspend fun acknowledge(eventId: String): Outcome<SosStatus>
    suspend fun cancelFalseAlarm(eventId: String): Outcome<SosStatus>
    suspend fun resolve(eventId: String, notes: String?): Outcome<SosStatus>
}

class FirebaseAlertService(
    private val backend: Backend,
    private val connectivity: ConnectivityMonitor,
) : AlertService {

    override fun observeEvent(eventId: String): Flow<Remote<SeizureEvent>> {
        val fs = backend.firestore ?: return unavailable(AppError.BackendNotConfigured)
        return fs.collection("events").document(eventId).observe { it.toSeizureEvent() }
    }

    override fun observeOpenEvents(patientId: String): Flow<Remote<List<SeizureEvent>>> {
        val fs = backend.firestore ?: return unavailable(AppError.BackendNotConfigured)
        val uid = backend.auth?.currentUser?.uid ?: return unavailable(AppError.NotAuthenticated)
        return fs.collection("events")
            .whereEqualTo("patientId", patientId)
            .whereArrayContains("caregiverUids", uid)
            .whereIn("status", listOf(SosStatus.ALERT_RECEIVED.name, SosStatus.ACTIVE.name, SosStatus.ESCALATED.name))
            .orderBy("detectedAt", Query.Direction.DESCENDING)
            .limit(5)
            .observeList { it.toSeizureEvent() }
    }

    private suspend fun transition(eventId: String, extra: (from: SosStatus, detectedAtMs: Long?) -> Pair<SosStatus, Map<String, Any?>>?): Outcome<SosStatus> {
        val fs = backend.firestore ?: return Outcome.Failure(AppError.BackendNotConfigured)
        if (backend.auth?.currentUser == null) return Outcome.Failure(AppError.NotAuthenticated)
        if (!connectivity.isOnline.first()) return Outcome.Failure(AppError.NoInternet)
        val ref = fs.collection("events").document(eventId)
        return guard {
            fs.runTransaction { tx ->
                val snap = tx.get(ref)
                if (!snap.exists()) throw IllegalStateException("NotFound")
                val from = SosStatus.parse(snap.getString("status"))
                // Someone else may already have handled it: report the current status instead of failing.
                val change = extra(from, snap.getTimestamp("detectedAt")?.toDate()?.time) ?: return@runTransaction from
                tx.update(ref, change.second + mapOf("status" to change.first.name, "updatedAt" to FieldValue.serverTimestamp()))
                change.first
            }.await()
        }
    }

    private fun me(): Map<String, Any?> {
        val u = backend.auth?.currentUser
        val name = u?.displayName?.takeIf { it.isNotBlank() } ?: u?.email?.substringBefore('@') ?: "Caregiver"
        return mapOf("uid" to u?.uid, "name" to name)
    }

    override suspend fun acknowledge(eventId: String) = transition(eventId) { from, detected ->
        if (from != SosStatus.ACTIVE && from != SosStatus.ESCALATED && from != SosStatus.ALERT_RECEIVED) return@transition null
        val response = detected?.let { ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0) }
        SosStatus.ACKNOWLEDGED to mapOf(
            "acknowledgedBy" to me(),
            "acknowledgedAt" to FieldValue.serverTimestamp(),
            "responseTimeSec" to response,
        )
    }

    override suspend fun cancelFalseAlarm(eventId: String) = transition(eventId) { from, _ ->
        if (from != SosStatus.ACTIVE && from != SosStatus.ESCALATED && from != SosStatus.ALERT_RECEIVED) return@transition null
        SosStatus.CANCELLED to mapOf(
            "cancelledBy" to me(),
            "cancelledAt" to FieldValue.serverTimestamp(),
            "cancelReason" to "FALSE_ALARM",
            "endedAt" to FieldValue.serverTimestamp(),
        )
    }

    override suspend fun resolve(eventId: String, notes: String?) = transition(eventId) { from, _ ->
        if (from != SosStatus.ACKNOWLEDGED) return@transition null
        SosStatus.RESOLVED to mapOf(
            "resolvedAt" to FieldValue.serverTimestamp(),
            "endedAt" to FieldValue.serverTimestamp(),
            "notes" to notes?.trim()?.take(500)?.ifBlank { null },
        )
    }
}
