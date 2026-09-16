package com.sahayat.app.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted local cache of the authenticated user's patient/device association.
 * The backend remains the source of truth; this only lets the app render the
 * correct "paired device" state instantly on cold start and after FCM wake-ups.
 */
class SecureStore(context: Context) {
    private val prefs: SharedPreferences? = runCatching {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "sahayat_secure",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    var uid: String?
        get() = prefs?.getString(K_UID, null)
        set(v) { prefs?.edit()?.putString(K_UID, v)?.apply() }

    var patientId: String?
        get() = prefs?.getString(K_PATIENT, null)
        set(v) { prefs?.edit()?.putString(K_PATIENT, v)?.apply() }

    var deviceId: String?
        get() = prefs?.getString(K_DEVICE, null)
        set(v) { prefs?.edit()?.putString(K_DEVICE, v)?.apply() }

    // ---- Band linked to this phone (Patient mode only) ----
    var bandAddress: String?
        get() = prefs?.getString(K_BAND_ADDR, null)
        set(v) { prefs?.edit()?.putString(K_BAND_ADDR, v)?.apply() }

    var bandId: String?
        get() = prefs?.getString(K_BAND_ID, null)
        set(v) { prefs?.edit()?.putString(K_BAND_ID, v)?.apply() }

    var bandName: String?
        get() = prefs?.getString(K_BAND_NAME, null)
        set(v) { prefs?.edit()?.putString(K_BAND_NAME, v)?.apply() }

    fun saveBand(address: String, id: String, name: String) {
        prefs?.edit()?.putString(K_BAND_ADDR, address)?.putString(K_BAND_ID, id)?.putString(K_BAND_NAME, name)?.apply()
    }

    fun clearBand() {
        prefs?.edit()?.remove(K_BAND_ADDR)?.remove(K_BAND_ID)?.remove(K_BAND_NAME)?.apply()
    }

    /** Relay band alerts as TEST (set by the user on the patient's phone). */
    var bandTestMode: Boolean
        get() = prefs?.getBoolean(K_TEST_MODE, false) ?: false
        set(v) { prefs?.edit()?.putBoolean(K_TEST_MODE, v)?.apply() }

    /**
     * Last band alert this phone has seen ("<ref>" or "<ref>|<kind>"); null means "no baseline yet",
     * so the alert value read right after the first connection is not treated as a new alert.
     */
    var lastAlertRef: String?
        get() = prefs?.getString(K_LAST_ALERT, null)
        set(v) { prefs?.edit()?.putString(K_LAST_ALERT, v)?.apply() }

    /** Caregiver numbers for offline SMS, cached from Firestore (never logged). */
    var smsNumbers: List<String>
        get() = prefs?.getString(K_SMS_NUMBERS, null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        set(v) { prefs?.edit()?.putString(K_SMS_NUMBERS, v.joinToString("\n"))?.apply() }

    var smsFallbackEnabled: Boolean
        get() = prefs?.getBoolean(K_SMS_ENABLED, true) ?: true
        set(v) { prefs?.edit()?.putBoolean(K_SMS_ENABLED, v)?.apply() }

    var patientName: String?
        get() = prefs?.getString(K_PATIENT_NAME, null)
        set(v) { prefs?.edit()?.putString(K_PATIENT_NAME, v)?.apply() }

    /** Alerts that could not reach the backend yet, one JSON object per line. */
    var pendingAlerts: String?
        get() = prefs?.getString(K_PENDING, null)
        set(v) { prefs?.edit()?.putString(K_PENDING, v)?.apply() }

    fun clearAssociation() {
        prefs?.edit()?.remove(K_UID)?.remove(K_PATIENT)?.remove(K_DEVICE)
            ?.remove(K_BAND_ADDR)?.remove(K_BAND_ID)?.remove(K_BAND_NAME)
            ?.remove(K_SMS_NUMBERS)?.remove(K_LAST_ALERT)?.remove(K_PATIENT_NAME)?.remove(K_PENDING)?.apply()
    }

    private companion object {
        const val K_UID = "uid"
        const val K_PATIENT = "patient_id"
        const val K_DEVICE = "device_id"
        const val K_BAND_ADDR = "band_address"
        const val K_BAND_ID = "band_id"
        const val K_BAND_NAME = "band_name"
        const val K_SMS_NUMBERS = "sms_numbers"
        const val K_SMS_ENABLED = "sms_enabled"
        const val K_PATIENT_NAME = "patient_name"
        const val K_PENDING = "pending_alerts"
        const val K_TEST_MODE = "band_test_mode"
        const val K_LAST_ALERT = "band_last_alert"
    }
}
