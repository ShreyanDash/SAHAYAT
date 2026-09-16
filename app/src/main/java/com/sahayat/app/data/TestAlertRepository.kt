package com.sahayat.app.data

import com.sahayat.app.data.model.SeizureEvent
import com.sahayat.app.data.model.SmsStatus
import com.sahayat.app.data.model.SosStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Developer-only TEST ALERT. Lives entirely in memory, is never written to Firestore,
 * never appears in History or exports, and is always labelled TEST ALERT in the UI.
 */
class TestAlertRepository {
    private val _event = MutableStateFlow<SeizureEvent?>(null)
    val event: StateFlow<SeizureEvent?> = _event

    fun create(patientId: String?, patientName: String?, deviceId: String?): SeizureEvent {
        val now = System.currentTimeMillis()
        val e = SeizureEvent(
            id = "$TEST_PREFIX${UUID.randomUUID().toString().take(8)}",
            patientId = patientId ?: "test-patient",
            patientName = patientName,
            deviceId = deviceId,
            alertType = "TEST_ALERT",
            status = SosStatus.ACTIVE,
            detectedAtMs = now,
            endedAtMs = null,
            durationSec = null,
            batteryPercent = null,
            location = null,
            confidence = null,
            acknowledgedBy = null,
            acknowledgedAtMs = null,
            responseTimeSec = null,
            cancelledBy = null,
            escalateAtMs = null,
            escalatedAtMs = null,
            smsStatus = SmsStatus.NOT_REQUIRED,
            smsDetail = null,
            smsUpdatedAtMs = null,
            resolvedAtMs = null,
            notes = null,
            isTest = true,
        )
        _event.value = e
        return e
    }

    fun transition(eventId: String, to: SosStatus) {
        val cur = _event.value ?: return
        if (cur.id != eventId) return
        val now = System.currentTimeMillis()
        _event.value = cur.copy(
            status = to,
            acknowledgedAtMs = if (to == SosStatus.ACKNOWLEDGED) now else cur.acknowledgedAtMs,
            responseTimeSec = if (to == SosStatus.ACKNOWLEDGED) (now - cur.detectedAtMs) / 1000 else cur.responseTimeSec,
            resolvedAtMs = if (to == SosStatus.RESOLVED) now else cur.resolvedAtMs,
        )
    }

    fun clear() { _event.value = null }

    companion object {
        const val TEST_PREFIX = "TEST-"
        fun isTestId(id: String) = id.startsWith(TEST_PREFIX)
    }
}
