package com.sahayat.app.ble

import com.sahayat.app.core.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class BandLinkPhase {
    /** No band linked to this phone. */
    NOT_PAIRED,
    BLUETOOTH_OFF,
    NO_PERMISSION,
    /** Link service not running (e.g. just after boot before it starts). */
    STOPPED,
    CONNECTING,
    CONNECTED,
    /** Was connected and lost the link; reconnecting automatically. */
    DISCONNECTED,
    /** Connected to something that is not the paired SAHAYAT band (see [BandLinkState.error]). */
    INCOMPATIBLE,
}

/** How the last band alert was relayed from this phone. */
data class RelayOutcome(
    val alertId: String,
    val isTest: Boolean,
    val atMs: Long,
    /** Backend event id, once the backend accepted the alert. */
    val eventId: String? = null,
    /** Number of caregiver phones FCM accepted the push for (null = not known yet). */
    val pushRecipients: Int? = null,
    val queuedOffline: Boolean = false,
    /** SMS result from this phone's SIM: SENT / FAILED / UNAVAILABLE, or null if no SMS was needed. */
    val smsStatus: String? = null,
    val smsDetail: String? = null,
)

data class BandLinkState(
    val phase: BandLinkPhase = BandLinkPhase.NOT_PAIRED,
    val deviceId: String? = null,
    val name: String? = null,
    val address: String? = null,
    /** Last status packet the band actually sent (null = none received this session). */
    val status: BandStatus? = null,
    val rssi: Int? = null,
    val lastAlert: BandAlert? = null,
    val lastRelay: RelayOutcome? = null,
    val connectedSinceMs: Long? = null,
    val lastBackendReportMs: Long? = null,
    val backendError: String? = null,
    /** Last GATT setup problem, e.g. SERVICE_NOT_FOUND or WRONG_DEVICE. */
    val error: BandLinkError? = null,
    /** Last "SAHAYAT_OK" reply to PING. */
    val lastPingOkMs: Long? = null,
    /** An alert the band raised whose 2-s cancel window is still open. */
    val pendingAlert: BandAlert? = null,
    /** Alerts from the band are relayed as TEST (labelled everywhere, no immediate SMS). */
    val testMode: Boolean = false,
    val pendingAlerts: Int = 0,
) {
    val paired: Boolean get() = deviceId != null && address != null
    val connected: Boolean get() = phase == BandLinkPhase.CONNECTED
}

/**
 * Live state of the Bluetooth link between this phone and the band. Written only by
 * [BandLinkService] and the pairing screen; read by the UI. Nothing here is ever
 * invented — fields stay null until the band reports them.
 */
class BandLinkRepository(private val store: SecureStore) {
    private val _state = MutableStateFlow(initial())
    val state: StateFlow<BandLinkState> = _state

    private fun initial(): BandLinkState {
        val id = store.bandId
        val addr = store.bandAddress
        return if (id != null && addr != null) {
            BandLinkState(BandLinkPhase.STOPPED, id, store.bandName, addr, testMode = store.bandTestMode)
        } else {
            BandLinkState(testMode = store.bandTestMode)
        }
    }

    fun update(block: (BandLinkState) -> BandLinkState) = _state.update(block)

    fun onPaired(address: String, deviceId: String, name: String) {
        store.saveBand(address, deviceId, name)
        store.lastAlertRef = null
        _state.value = BandLinkState(BandLinkPhase.STOPPED, deviceId, name, address, testMode = store.bandTestMode)
    }

    fun onUnpaired() {
        store.clearBand()
        _state.value = BandLinkState(testMode = store.bandTestMode)
    }

    fun setTestMode(on: Boolean) {
        store.bandTestMode = on
        _state.update { it.copy(testMode = on) }
    }

    fun reload() {
        val fresh = initial()
        _state.update { cur ->
            if (cur.deviceId == fresh.deviceId && cur.address == fresh.address) cur else fresh
        }
    }
}
