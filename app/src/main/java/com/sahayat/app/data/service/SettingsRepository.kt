package com.sahayat.app.data.service

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "sahayat_settings")

/** Device-local preferences (not patient data). */
data class LocalSettings(
    val onboardingComplete: Boolean = false,
    val previewMode: Boolean = false,
    val languageCode: String = "en",
    val emergencySound: Boolean = true,
    val emergencyVibration: Boolean = true,
    val generalNotifications: Boolean = true,
)

/**
 * Profile collected on the very first launch, before any account exists. It is kept on this
 * phone and used to pre-fill account creation and the patient profile.
 */
/** What this phone is used for. */
enum class AppRole {
    /** Worn-band phone: connects to the band over Bluetooth and relays alerts. */
    PATIENT,
    /** Receives SOS alerts for a patient. */
    CAREGIVER,
}

data class LocalProfile(
    val completed: Boolean = false,
    val role: AppRole? = null,
    val caregiverName: String = "",
    val caregiverPhone: String = "",
    val relationship: String = "",
    val patientName: String = "",
    val patientAge: Int? = null,
    val patientBloodGroup: String = "",
    val emergencyNotes: String = "",
)

class SettingsRepository(private val context: Context) {
    private object K {
        val onboarding = booleanPreferencesKey("onboarding_complete")
        val preview = booleanPreferencesKey("preview_mode")
        val language = stringPreferencesKey("language")
        val sound = booleanPreferencesKey("emergency_sound")
        val vibration = booleanPreferencesKey("emergency_vibration")
        val general = booleanPreferencesKey("general_notifications")
        val profileDone = booleanPreferencesKey("profile_done")
        val cgName = stringPreferencesKey("profile_caregiver_name")
        val cgPhone = stringPreferencesKey("profile_caregiver_phone")
        val relation = stringPreferencesKey("profile_relationship")
        val pName = stringPreferencesKey("profile_patient_name")
        val pAge = stringPreferencesKey("profile_patient_age")
        val pBlood = stringPreferencesKey("profile_patient_blood")
        val pNotes = stringPreferencesKey("profile_patient_notes")
        val role = stringPreferencesKey("profile_role")
    }

    val profile: Flow<LocalProfile> = context.dataStore.data.map { p ->
        LocalProfile(
            completed = p[K.profileDone] ?: false,
            role = p[K.role]?.let { r -> AppRole.entries.firstOrNull { it.name == r } },
            caregiverName = p[K.cgName] ?: "",
            caregiverPhone = p[K.cgPhone] ?: "",
            relationship = p[K.relation] ?: "",
            patientName = p[K.pName] ?: "",
            patientAge = p[K.pAge]?.toIntOrNull(),
            patientBloodGroup = p[K.pBlood] ?: "",
            emergencyNotes = p[K.pNotes] ?: "",
        )
    }

    suspend fun currentProfile(): LocalProfile = profile.first()

    suspend fun saveProfile(profile: LocalProfile) = context.dataStore.edit {
        it[K.profileDone] = true
        if (profile.role != null) it[K.role] = profile.role.name else it.remove(K.role)
        it[K.cgName] = profile.caregiverName.trim()
        it[K.cgPhone] = profile.caregiverPhone.trim()
        it[K.relation] = profile.relationship.trim()
        it[K.pName] = profile.patientName.trim()
        if (profile.patientAge != null) it[K.pAge] = profile.patientAge.toString() else it.remove(K.pAge)
        it[K.pBlood] = profile.patientBloodGroup.trim()
        it[K.pNotes] = profile.emergencyNotes.trim()
    }

    val settings: Flow<LocalSettings> = context.dataStore.data.map { p: Preferences ->
        LocalSettings(
            onboardingComplete = p[K.onboarding] ?: false,
            previewMode = p[K.preview] ?: false,
            languageCode = p[K.language] ?: "en",
            emergencySound = p[K.sound] ?: true,
            emergencyVibration = p[K.vibration] ?: true,
            generalNotifications = p[K.general] ?: true,
        )
    }

    suspend fun current(): LocalSettings = settings.first()

    suspend fun setOnboardingComplete(v: Boolean) = context.dataStore.edit { it[K.onboarding] = v }
    suspend fun setPreviewMode(v: Boolean) = context.dataStore.edit { it[K.preview] = v }
    suspend fun setLanguage(code: String) = context.dataStore.edit { it[K.language] = code }
    suspend fun setEmergencySound(v: Boolean) = context.dataStore.edit { it[K.sound] = v }
    suspend fun setEmergencyVibration(v: Boolean) = context.dataStore.edit { it[K.vibration] = v }
    suspend fun setGeneralNotifications(v: Boolean) = context.dataStore.edit { it[K.general] = v }

    suspend fun resetForSignOut() = context.dataStore.edit {
        it.remove(K.onboarding)
        it.remove(K.preview)
    }
}
