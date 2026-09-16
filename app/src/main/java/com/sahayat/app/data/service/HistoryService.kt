package com.sahayat.app.data.service

import com.google.firebase.Timestamp
import com.google.firebase.firestore.Query
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Backend
import com.sahayat.app.core.ConnectivityMonitor
import com.sahayat.app.core.Outcome
import com.sahayat.app.core.Remote
import com.sahayat.app.core.guard
import com.sahayat.app.data.model.SeizureEvent
import com.sahayat.app.data.model.toSeizureEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import java.util.Date

interface HistoryService {
    /** Live list of events in [fromMs, toMs]; null bounds are open. */
    fun observeEvents(patientId: String, fromMs: Long?, toMs: Long?): Flow<Remote<List<SeizureEvent>>>
    suspend fun fetchEvent(eventId: String): Outcome<SeizureEvent>
    suspend fun fetchEvents(patientId: String, fromMs: Long?, toMs: Long?): Outcome<List<SeizureEvent>>
}

class FirestoreHistoryService(
    private val backend: Backend,
    private val connectivity: ConnectivityMonitor,
) : HistoryService {

    /** Rules only allow events that list the caller in caregiverUids, so the query must say so too. */
    private fun query(patientId: String, fromMs: Long?, toMs: Long?): Query {
        val uid = backend.auth?.currentUser?.uid ?: throw IllegalStateException("not signed in")
        var q: Query = backend.firestore!!.collection("events")
            .whereEqualTo("patientId", patientId)
            .whereArrayContains("caregiverUids", uid)
        if (fromMs != null) q = q.whereGreaterThanOrEqualTo("detectedAt", Timestamp(Date(fromMs)))
        if (toMs != null) q = q.whereLessThanOrEqualTo("detectedAt", Timestamp(Date(toMs)))
        return q.orderBy("detectedAt", Query.Direction.DESCENDING).limit(500)
    }

    override fun observeEvents(patientId: String, fromMs: Long?, toMs: Long?): Flow<Remote<List<SeizureEvent>>> {
        if (backend.firestore == null) return unavailable(AppError.BackendNotConfigured)
        if (backend.auth?.currentUser == null) return unavailable(AppError.NotAuthenticated)
        return query(patientId, fromMs, toMs).observeList { it.toSeizureEvent() }
    }

    override suspend fun fetchEvent(eventId: String): Outcome<SeizureEvent> {
        val fs = backend.firestore ?: return Outcome.Failure(AppError.BackendNotConfigured)
        val r = guard { fs.collection("events").document(eventId).get().await().toSeizureEvent() }
        return when (r) {
            is Outcome.Failure -> r
            is Outcome.Success -> r.value?.let { Outcome.Success(it) } ?: Outcome.Failure(AppError.NotFound)
        }
    }

    override suspend fun fetchEvents(patientId: String, fromMs: Long?, toMs: Long?): Outcome<List<SeizureEvent>> {
        if (backend.firestore == null) return Outcome.Failure(AppError.BackendNotConfigured)
        if (!connectivity.isOnline.first()) return Outcome.Failure(AppError.NoInternet)
        if (backend.auth?.currentUser == null) return Outcome.Failure(AppError.NotAuthenticated)
        return guard(30_000) {
            // Server-only read so an export never silently uses a stale cache.
            query(patientId, fromMs, toMs).get(com.google.firebase.firestore.Source.SERVER).await()
                .documents.mapNotNull { it.toSeizureEvent() }
        }
    }
}
