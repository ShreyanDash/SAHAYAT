package com.sahayat.app.data.service

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Remote
import com.sahayat.app.core.toAppError
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

/** Live document → Remote. A missing document maps to [missing] (default NotFound). */
internal fun <T> DocumentReference.observe(
    missing: AppError = AppError.NotFound,
    map: (DocumentSnapshot) -> T?,
): Flow<Remote<T>> = callbackFlow {
    trySend(Remote.Loading)
    val reg = addSnapshotListener { snap, err ->
        when {
            err != null -> trySend(Remote.Unavailable(err.toAppError()))
            snap == null -> trySend(Remote.Unavailable(missing))
            else -> {
                val value = map(snap)
                trySend(if (value != null) Remote.Data(value) else Remote.Unavailable(missing))
            }
        }
    }
    awaitClose { reg.remove() }
}

/** Live query → Remote list. */
internal fun <T> Query.observeList(map: (DocumentSnapshot) -> T?): Flow<Remote<List<T>>> = callbackFlow {
    trySend(Remote.Loading)
    val reg = addSnapshotListener { snap: QuerySnapshot?, err ->
        if (err != null) {
            trySend(Remote.Unavailable(err.toAppError()))
        } else {
            trySend(Remote.Data(snap?.documents?.mapNotNull(map) ?: emptyList()))
        }
    }
    awaitClose { reg.remove() }
}

internal fun <T> unavailable(error: AppError): Flow<Remote<T>> = flowOf(Remote.Unavailable(error))
