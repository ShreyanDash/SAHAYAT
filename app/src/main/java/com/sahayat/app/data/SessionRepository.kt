package com.sahayat.app.data

import com.sahayat.app.core.AppError
import com.sahayat.app.core.Backend
import com.sahayat.app.core.Remote
import com.sahayat.app.core.valueOrNull
import com.sahayat.app.core.SecureStore
import com.sahayat.app.data.model.Patient
import com.sahayat.app.data.model.UserProfile
import com.sahayat.app.data.service.AuthService
import com.sahayat.app.data.service.NotificationService
import com.sahayat.app.data.service.PatientService
import com.sahayat.app.data.service.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SessionPhase {
    /** Still resolving auth/profile. */
    STARTING,
    SIGNED_OUT,
    /** Explicit, clearly-labelled offline preview (no account, no backend). */
    PREVIEW,
    /** Signed in but no patient profile created yet. */
    NEEDS_PATIENT,
    READY,
}

data class SessionState(
    val phase: SessionPhase = SessionPhase.STARTING,
    val backendConfigured: Boolean = false,
    val uid: String? = null,
    val profile: UserProfile? = null,
    val patient: Remote<Patient> = Remote.Loading,
) {
    val patientId: String? get() = patient.valueOrNull?.id ?: profile?.activePatientId
    val deviceId: String? get() = patient.valueOrNull?.deviceId
    val isPreview: Boolean get() = phase == SessionPhase.PREVIEW
}

/**
 * Single source of truth for "who is signed in, which patient, which device".
 * Screens read from here instead of re-querying.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionRepository(
    private val scope: CoroutineScope,
    private val backend: Backend,
    private val auth: AuthService,
    private val patients: PatientService,
    private val notifications: NotificationService,
    private val settings: SettingsRepository,
    private val secureStore: SecureStore,
) {
    private fun sessionFor(uid: String): Flow<SessionState> =
        patients.observeProfile(uid).flatMapLatest { profile ->
            when (profile) {
                Remote.Loading -> flowOf(SessionState(SessionPhase.STARTING, true, uid))
                is Remote.Unavailable -> flowOf(
                    if (profile.error == AppError.NotFound) {
                        SessionState(SessionPhase.NEEDS_PATIENT, true, uid, patient = Remote.Unavailable(AppError.NoPatient))
                    } else {
                        // Profile can't be read right now (offline / backend down). Keep the user in the
                        // app and let each screen show the unavailable state.
                        SessionState(SessionPhase.READY, true, uid, patient = Remote.Unavailable(profile.error))
                    },
                )
                is Remote.Data -> {
                    val pid = profile.value.activePatientId
                    if (pid == null) {
                        flowOf(SessionState(SessionPhase.NEEDS_PATIENT, true, uid, profile.value, Remote.Unavailable(AppError.NoPatient)))
                    } else {
                        patients.observePatient(pid).map { p ->
                            SessionState(SessionPhase.READY, true, uid, profile.value, p)
                        }
                    }
                }
            }
        }

    val state: StateFlow<SessionState> =
        combine(auth.uid.distinctUntilChanged(), settings.settings.map { it.previewMode }.distinctUntilChanged()) { uid, preview ->
            uid to preview
        }.flatMapLatest { (uid, preview) ->
            when {
                uid != null -> sessionFor(uid)
                preview -> flowOf(
                    SessionState(
                        SessionPhase.PREVIEW,
                        backend.isConfigured,
                        patient = Remote.Unavailable(if (backend.isConfigured) AppError.NotAuthenticated else AppError.BackendNotConfigured),
                    ),
                )
                else -> flowOf(SessionState(SessionPhase.SIGNED_OUT, backend.isConfigured, patient = Remote.Unavailable(AppError.NotAuthenticated)))
            }
        }.stateIn(scope, SharingStarted.Eagerly, SessionState(backendConfigured = backend.isConfigured))

    init {
        // Keep the encrypted association cache and the FCM token registration in sync with the session.
        state.map { Triple(it.uid, it.patientId, it.deviceId) }
            .distinctUntilChanged()
            .onEach { (uid, pid, did) ->
                if (uid != null) {
                    secureStore.uid = uid
                    if (pid != null) secureStore.patientId = pid
                    if (state.value.patient is Remote.Data) secureStore.deviceId = did
                }
            }.launchIn(scope)

        state.map { it.uid }.distinctUntilChanged().onEach { uid ->
            if (uid != null) notifications.registerCurrentToken(uid)
        }.launchIn(scope)
    }

    fun signOut() {
        val uid = state.value.uid
        scope.launch {
            if (uid != null) notifications.unregisterToken(uid)
            auth.signOut()
            secureStore.clearAssociation()
            settings.resetForSignOut()
        }
    }
}
