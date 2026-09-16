package com.sahayat.app.ui.navigation

import android.net.Uri

object Routes {
    const val SPLASH = "splash"
    const val WELCOME = "welcome"
    const val PROFILE_SETUP = "profile_setup?edit={edit}"
    const val AUTH = "auth/{mode}"
    const val PATIENT_SETUP = "patient_setup"
    const val CAREGIVER_SETUP = "caregiver_setup"
    const val PAIRING = "pairing?onboarding={onboarding}"
    const val MAIN = "main"
    const val SOS = "sos/{eventId}"
    const val HISTORY_DETAIL = "history/{eventId}"
    const val CONTACT_EDIT = "contact_edit?id={id}"
    const val DEVICE = "device"
    const val DIAGNOSTICS = "diagnostics"
    const val DETECTION = "detection"
    const val NOTIFICATION_SETTINGS = "notification_settings"
    const val LANGUAGE = "language"
    const val PROFILE = "profile"
    const val PRIVACY = "privacy"
    const val EXPORT = "export"
    const val HELP = "help"
    const val INVITE = "invite"
    const val MAP = "map?lat={lat}&lng={lng}&acc={acc}&label={label}&eventId={eventId}"

    fun map(l: com.sahayat.app.data.model.EventLocation, label: String?, eventId: String?): String =
        "map?lat=${l.lat}&lng=${l.lng}&acc=${l.accuracyM ?: -1.0}" +
            "&label=${Uri.encode(label ?: "")}&eventId=${Uri.encode(eventId ?: "")}"
    const val JOIN_PATIENT = "join_patient?onboarding={onboarding}"

    fun joinPatient(onboarding: Boolean) = "join_patient?onboarding=$onboarding"

    fun profileSetup(edit: Boolean) = "profile_setup?edit=$edit"
    fun auth(create: Boolean) = "auth/${if (create) "create" else "signin"}"
    fun pairing(onboarding: Boolean) = "pairing?onboarding=$onboarding"
    fun sos(eventId: String) = "sos/${Uri.encode(eventId)}"
    fun historyDetail(eventId: String) = "history/${Uri.encode(eventId)}"
    fun contactEdit(id: String?) = if (id == null) "contact_edit" else "contact_edit?id=${Uri.encode(id)}"

    /** Routes a signed-out user may be on. */
    val PUBLIC = setOf(SPLASH, WELCOME, AUTH, PROFILE_SETUP)
}

enum class MainTab { HOME, MONITOR, HISTORY, CONTACTS, SETTINGS }
