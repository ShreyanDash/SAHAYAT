package com.sahayat.app.data.service

import android.net.Uri
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Backend
import com.sahayat.app.core.ConnectivityMonitor
import com.sahayat.app.core.Outcome
import com.sahayat.app.core.Remote
import com.sahayat.app.core.SecureStore
import com.sahayat.app.data.model.DeviceStatus
import com.sahayat.app.data.model.toDeviceStatus
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import com.sahayat.app.core.guard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

data class PairingCredentials(val deviceId: String, val pairingCode: String)

data class PairingResult(
    val deviceId: String,
    /** Whether the backend has a fresh heartbeat from the device right now. */
    val deviceOnline: Boolean,
)

interface DeviceService {
    fun observeDevice(deviceId: String): Flow<Remote<DeviceStatus>>
    suspend fun pair(credentials: PairingCredentials, patientId: String): Outcome<PairingResult>
    suspend fun unpair(deviceId: String, patientId: String): Outcome<Unit>
    /** Asks the backend to flag the device for an immediate status report. */
    suspend fun requestSync(deviceId: String): Outcome<Unit>

    companion object {
        /** Device IDs are printed on the band as SAH-XXXXXX (4–16 uppercase letters/digits). */
        private val ID_REGEX = Regex("^SAH-[A-Z0-9]{4,16}$")
        private val CODE_REGEX = Regex("^[0-9]{6}$")

        fun normalizeId(raw: String): String = raw.trim().uppercase().replace(' ', '-')
        fun isValidDeviceId(id: String): Boolean = ID_REGEX.matches(id)
        fun isValidCode(code: String): Boolean = CODE_REGEX.matches(code.trim())

        /**
         * Accepts QR contents in either form:
         *  - sahayat://pair?id=SAH-ABC123&code=123456
         *  - SAHAYAT:SAH-ABC123:123456
         */
        fun parseQr(raw: String): PairingCredentials? {
            val text = raw.trim()
            if (text.startsWith("sahayat://", ignoreCase = true)) {
                val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null
                val id = uri.getQueryParameter("id")?.let(::normalizeId) ?: return null
                val code = uri.getQueryParameter("code")?.trim() ?: return null
                return PairingCredentials(id, code).takeIf { isValidDeviceId(id) && isValidCode(code) }
            }
            val parts = text.split(':')
            if (parts.size == 3 && parts[0].equals("SAHAYAT", ignoreCase = true)) {
                val id = normalizeId(parts[1])
                val code = parts[2].trim()
                return PairingCredentials(id, code).takeIf { isValidDeviceId(id) && isValidCode(code) }
            }
            return null
        }
    }
}

class FirebaseDeviceService(
    private val backend: Backend,
    private val connectivity: ConnectivityMonitor,
    private val secureStore: SecureStore,
) : DeviceService {

    override fun observeDevice(deviceId: String): Flow<Remote<DeviceStatus>> {
        val fs = backend.firestore ?: return unavailable(AppError.BackendNotConfigured)
        return fs.collection("devices").document(deviceId).observe(AppError.DeviceNotFound) { it.toDeviceStatus() }
    }

    override suspend fun pair(credentials: PairingCredentials, patientId: String): Outcome<PairingResult> {
        val id = DeviceService.normalizeId(credentials.deviceId)
        if (!DeviceService.isValidDeviceId(id)) return Outcome.Failure(AppError.InvalidDeviceId)
        if (!DeviceService.isValidCode(credentials.pairingCode)) return Outcome.Failure(AppError.InvalidPairingCode)
        // Server verifies the pairing code against the device's provisioning record, checks the
        // device isn't linked to another patient, then writes the association atomically.
        return when (val r = backend.call(
            connectivity, "pairDevice",
            mapOf("deviceId" to id, "pairingCode" to credentials.pairingCode.trim(), "patientId" to patientId),
        )) {
            is Outcome.Failure -> r
            is Outcome.Success -> {
                if (r.value["status"] != "PAIRED") {
                    Outcome.Failure(AppError.Unknown("Unexpected pairing response"))
                } else {
                    secureStore.deviceId = id
                    secureStore.patientId = patientId
                    Outcome.Success(PairingResult(id, deviceOnline = r.value["deviceOnline"] == true))
                }
            }
        }
    }

    override suspend fun unpair(deviceId: String, patientId: String): Outcome<Unit> {
        val fs = backend.firestore ?: return Outcome.Failure(AppError.BackendNotConfigured)
        if (!connectivity.isOnline.first()) return Outcome.Failure(AppError.NoInternet)
        return guard {
            val batch = fs.batch()
            batch.set(
                fs.collection("devices").document(deviceId),
                mapOf("patientId" to null, "ownerUid" to null, "relayUid" to null, "status" to mapOf("online" to false)),
                SetOptions.merge(),
            )
            batch.update(fs.collection("patients").document(patientId), mapOf("deviceId" to null, "patientPhoneUid" to null))
            batch.commit().await()
            secureStore.deviceId = null
        }
    }

    /** The patient's phone watches this field and asks the band for a fresh status. */
    override suspend fun requestSync(deviceId: String): Outcome<Unit> {
        val fs = backend.firestore ?: return Outcome.Failure(AppError.BackendNotConfigured)
        if (!connectivity.isOnline.first()) return Outcome.Failure(AppError.NoInternet)
        return guard {
            fs.collection("devices").document(deviceId)
                .update("syncRequestedAt", FieldValue.serverTimestamp()).await()
            Unit
        }
    }
}
