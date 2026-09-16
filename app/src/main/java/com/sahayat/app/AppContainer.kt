package com.sahayat.app

import android.content.Context
import com.sahayat.app.core.Backend
import com.sahayat.app.core.ConnectivityMonitor
import com.sahayat.app.core.SecureStore
import com.sahayat.app.data.SessionRepository
import com.sahayat.app.data.TestAlertRepository
import com.sahayat.app.data.service.AlertService
import com.sahayat.app.data.service.AuthService
import com.sahayat.app.data.service.CaregiverService
import com.sahayat.app.data.service.DeviceService
import com.sahayat.app.data.service.ExportService
import com.sahayat.app.data.service.FcmNotificationService
import com.sahayat.app.data.service.FirebaseAlertService
import com.sahayat.app.data.service.FirebaseAuthService
import com.sahayat.app.data.service.FirebaseDeviceService
import com.sahayat.app.data.service.FirestoreCaregiverService
import com.sahayat.app.data.service.FirestoreHistoryService
import com.sahayat.app.data.service.FirestorePatientService
import com.sahayat.app.data.service.HistoryService
import com.sahayat.app.data.service.NotificationService
import com.sahayat.app.data.service.PatientService
import com.sahayat.app.data.service.SettingsRepository
import com.sahayat.app.ble.BandLinkRepository
import com.sahayat.app.data.service.RelayService
import com.sahayat.app.emergency.SosInbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container. UI code only ever sees the service *interfaces*,
 * so a different backend can be swapped in here without touching screens.
 */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val backend = Backend(context)
    val connectivity = ConnectivityMonitor(context, appScope)
    val secureStore = SecureStore(context)
    val settings = SettingsRepository(context)
    val sosInbox = SosInbox()
    val testAlerts = TestAlertRepository()

    val authService: AuthService = FirebaseAuthService(backend, connectivity)
    val patientService: PatientService = FirestorePatientService(backend, connectivity)
    val deviceService: DeviceService = FirebaseDeviceService(backend, connectivity, secureStore)
    val alertService: AlertService = FirebaseAlertService(backend, connectivity)
    val caregiverService: CaregiverService = FirestoreCaregiverService(backend, connectivity)
    val historyService: HistoryService = FirestoreHistoryService(backend, connectivity)
    val notificationService: NotificationService = FcmNotificationService(context, backend, connectivity)
    val exportService = ExportService(context)
    val relayService = RelayService(backend, connectivity)
    val bandLink = BandLinkRepository(secureStore)

    val session = SessionRepository(
        scope = appScope,
        backend = backend,
        auth = authService,
        patients = patientService,
        notifications = notificationService,
        settings = settings,
        secureStore = secureStore,
    )
}
