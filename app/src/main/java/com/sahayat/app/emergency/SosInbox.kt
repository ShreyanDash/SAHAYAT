package com.sahayat.app.emergency

import com.sahayat.app.data.model.AlertPayload
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process hand-off between the FCM service / notification intents and the UI.
 * Holds the last real payload so the SOS screen can show what the backend sent
 * immediately, while the authoritative Firestore document loads.
 */
class SosInbox {
    private val payloads = mutableMapOf<String, AlertPayload>()

    private val _openRequests = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)
    /** Event ids the UI should navigate to (emitted for foreground alerts and notification taps). */
    val openRequests: SharedFlow<String> = _openRequests.asSharedFlow()

    private val _latest = MutableStateFlow<AlertPayload?>(null)
    val latest: StateFlow<AlertPayload?> = _latest

    @Synchronized
    fun onAlertReceived(payload: AlertPayload) {
        payloads[payload.eventId] = payload
        _latest.value = payload
        _openRequests.tryEmit(payload.eventId)
    }

    fun requestOpen(eventId: String) {
        _openRequests.tryEmit(eventId)
    }

    @Synchronized
    fun payloadFor(eventId: String): AlertPayload? = payloads[eventId]

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun consumeOpenRequest() {
        _openRequests.resetReplayCache()
    }
}
