package com.sahayat.app.ui.screens.pairing

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sahayat.app.AppContainer
import com.sahayat.app.ble.BandLinkError
import com.sahayat.app.ble.BandLinkService
import com.sahayat.app.ble.BandProbe
import com.sahayat.app.ble.BandProtocol
import com.sahayat.app.ble.ProbeResult
import com.sahayat.app.ble.BandScanner
import com.sahayat.app.ble.BlePermissions
import com.sahayat.app.ble.FoundBand
import com.sahayat.app.core.AppError
import com.sahayat.app.core.Format
import com.sahayat.app.core.Outcome
import com.sahayat.app.data.service.AppRole
import com.sahayat.app.emergency.SmsFallback
import com.sahayat.app.ui.LocalContainer
import com.sahayat.app.ui.components.Banner
import com.sahayat.app.ui.components.ButtonKind
import com.sahayat.app.ui.components.EmptyState
import com.sahayat.app.ui.components.HSpace
import com.sahayat.app.ui.components.IconBadge
import com.sahayat.app.ui.components.SButton
import com.sahayat.app.ui.components.SCard
import com.sahayat.app.ui.components.SIcons
import com.sahayat.app.ui.components.SListRow
import com.sahayat.app.ui.components.ScreenScaffold
import com.sahayat.app.ui.components.SectionLabel
import com.sahayat.app.ui.components.StatusPill
import com.sahayat.app.ui.components.Tone
import com.sahayat.app.ui.components.VSpace
import com.sahayat.app.ui.i18n.AppStrings
import com.sahayat.app.ui.i18n.LocalStrings
import com.sahayat.app.ui.navigation.Routes
import com.sahayat.app.ui.sahayatViewModel
import com.sahayat.app.ui.theme.SColors
import com.sahayat.app.ui.theme.SType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class BandPairingState(
    val scanning: Boolean = false,
    val scannedOnce: Boolean = false,
    val found: List<FoundBand> = emptyList(),
    val linking: String? = null,
    val error: AppError? = null,
    /** Why the last "connect and check" failed. */
    val bandError: BandLinkError? = null,
    val justLinked: Boolean = false,
    /** Bluetooth advertisers of any kind seen during the last scan (diagnostic). */
    val devicesSeen: Int = 0,
    val scanFailed: String? = null,
)

class BandPairingViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(BandPairingState())
    val state: StateFlow<BandPairingState> = _state
    private var scanJob: Job? = null

    fun scan(context: Context) {
        if (!BlePermissions.granted(context) || !BlePermissions.bluetoothOn(context)) return
        scanJob?.cancel()
        _state.update { it.copy(scanning = true, found = emptyList(), error = null, devicesSeen = 0, scanFailed = null) }
        val seen = java.util.Collections.synchronizedSet(HashSet<String>())
        scanJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(SCAN_MS) {
                    BandScanner.scan(context.applicationContext) { addr ->
                        if (seen.add(addr)) _state.update { it.copy(devicesSeen = seen.size) }
                    }.collect { band ->
                        _state.update { st ->
                            val others = st.found.filterNot { it.address == band.address }
                            // Real SAHAYAT service first, then by signal.
                            st.copy(found = (others + band).sortedWith(compareByDescending<FoundBand> { it.hasService }.thenByDescending { it.rssi }))
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(scanFailed = e.message ?: "Scan failed") }
            }
            _state.update { it.copy(scanning = false, scannedOnce = true) }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _state.update { it.copy(scanning = false) }
    }

    fun link(context: Context, band: FoundBand) {
        val pid = c.session.state.value.patientId
        if (pid == null) {
            _state.update { it.copy(error = AppError.NoPatient) }
            return
        }
        stopScan()
        _state.update { it.copy(linking = band.address, error = null, bandError = null) }
        viewModelScope.launch {
            // Really connect: discover the SAHAYAT service, read + validate the Device ID, enable notifications.
            val deviceId = when (val probe = BandProbe.verify(context.applicationContext, band.address)) {
                is ProbeResult.Failed -> {
                    _state.update { it.copy(linking = null, bandError = probe.error) }
                    return@launch
                }
                is ProbeResult.Verified -> probe.deviceId
            }
            val name = band.name ?: BandProtocol.EXPECTED_NAME
            when (val r = c.relayService.linkBand(pid, deviceId, name)) {
                is Outcome.Failure -> _state.update { it.copy(linking = null, error = r.error) }
                is Outcome.Success -> {
                    c.bandLink.onPaired(band.address, deviceId, name)
                    c.secureStore.deviceId = deviceId
                    BandLinkService.start(context.applicationContext)
                    _state.update { it.copy(linking = null, justLinked = true) }
                }
            }
        }
    }

    fun unpair(context: Context) {
        val st = c.session.state.value
        val did = c.secureStore.bandId ?: return
        val pid = st.patientId
        viewModelScope.launch {
            // Stop locally first: the band must not keep relaying for an unlinked patient.
            BandLinkService.stop(context.applicationContext)
            c.bandLink.onUnpaired()
            if (pid != null) c.deviceService.unpair(did, pid)
            _state.value = BandPairingState()
        }
    }

    fun clearError() = _state.update { it.copy(error = null, bandError = null) }

    private companion object {
        const val SCAN_MS = 15_000L
    }
}

private fun hasBle(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

private fun ignoringBattery(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

@SuppressLint("BatteryLife")
private fun batteryIntent(context: Context) =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

@Composable
fun BandPairingScreen(onboarding: Boolean, onBack: () -> Unit, onFinished: () -> Unit, navigate: (String) -> Unit) {
    val s = LocalStrings.current
    val context = LocalContext.current
    val container = LocalContainer.current
    val vm = sahayatViewModel { BandPairingViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val link by container.bandLink.state.collectAsStateWithLifecycle()
    val profile by container.settings.profile.collectAsStateWithLifecycle(null)
    val session by container.session.state.collectAsStateWithLifecycle()

    // Re-read system state (permissions, Bluetooth, battery) whenever we come back to this screen.
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val permsOk = remember(tick) { BlePermissions.granted(context) }
    val btOn = remember(tick) { BlePermissions.bluetoothOn(context) }
    val bleSupported = remember { hasBle(context) }
    val locationOff = remember(tick) { BlePermissions.locationNeededAndOff(context) }
    val smsOk = remember(tick) { SmsFallback.hasPermission(context) }
    val batteryOk = remember(tick) { ignoringBattery(context) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }

    // Start scanning automatically once everything is in place.
    LaunchedEffect(permsOk, btOn, link.paired) {
        if (permsOk && btOn && !link.paired && profile?.role != AppRole.CAREGIVER) vm.scan(context)
    }
    DisposableEffect(Unit) { onDispose { vm.stopScan() } }

    val isCaregiver = profile?.role == AppRole.CAREGIVER

    ScreenScaffold(
        title = s.pairBandTitle,
        eyebrow = s.bandName,
        subtitle = if (isCaregiver) null else s.pairBandSubtitle,
        onBack = if (onboarding) null else onBack,
        showMenu = !onboarding,
        bottom = {
            if (link.paired || isCaregiver) {
                SButton(if (onboarding) s.finish else s.done, onFinished, Modifier.fillMaxWidth())
            } else if (onboarding) {
                SButton(s.skipForNow, onFinished, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
            }
        },
    ) {
        if (isCaregiver) {
            SCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(SIcons.Bluetooth)
                    HSpace(14)
                    Text(s.pairOnPatientPhone, style = SType.CardTitle, modifier = Modifier.weight(1f))
                }
                VSpace(10)
                Text(s.pairOnPatientPhoneBody, style = SType.Body)
            }
            SListRow(s.joinPatient, icon = SIcons.PersonAdd, subtitle = s.joinSubtitle, fullWidth = true, onClick = { navigate(Routes.JOIN_PATIENT) })
        } else {
            PatientPairingContent(
                s, state, link.paired, link.name ?: link.deviceId.orEmpty(), link.deviceId,
                patientReady = session.patientId != null, preview = session.isPreview,
                bleSupported = bleSupported, locationOff = locationOff, permsOk = permsOk, btOn = btOn, smsOk = smsOk, batteryOk = batteryOk,
                onClearError = vm::clearError,
                onUnpair = { vm.unpair(context) },
                onRequestPerms = { permLauncher.launch(BlePermissions.required) },
                onEnableBt = {
                    runCatching { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) } }
                },
                onScan = { vm.scan(context) },
                onLink = { vm.link(context, it) },
                onSms = { smsLauncher.launch(Manifest.permission.SEND_SMS) },
                onBattery = {
                    runCatching { batteryLauncher.launch(batteryIntent(context)) }
                        .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
                },
                smsAvailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING),
                notificationsMissing = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED,
                navigate = navigate,
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PatientPairingContent(
    s: AppStrings,
    state: BandPairingState,
    paired: Boolean,
    bandName: String,
    bandId: String?,
    patientReady: Boolean,
    preview: Boolean,
    bleSupported: Boolean,
    locationOff: Boolean,
    permsOk: Boolean,
    btOn: Boolean,
    smsOk: Boolean,
    batteryOk: Boolean,
    onClearError: () -> Unit,
    onUnpair: () -> Unit,
    onRequestPerms: () -> Unit,
    onEnableBt: () -> Unit,
    onScan: () -> Unit,
    onLink: (FoundBand) -> Unit,
    onSms: () -> Unit,
    onBattery: () -> Unit,
    smsAvailable: Boolean,
    notificationsMissing: Boolean,
    navigate: (String) -> Unit,
) {
        state.error?.let { Banner(s.error(it), Tone.Danger, action = s.close to onClearError) }
        state.bandError?.let { Banner(s.bandLinkError(it), Tone.Danger, action = s.close to onClearError) }
        if (state.linking != null) Banner(s.verifyingBand, Tone.Neutral, icon = SIcons.BluetoothSearching)
        if (!patientReady && !preview) Banner(s.needPatientFirst, Tone.Warn)

        when {
            !bleSupported -> Banner(s.bleNotSupported, Tone.Danger, icon = SIcons.BluetoothOff)

            paired -> PairedSection(s, bandName, bandId, justLinked = state.justLinked, onUnpair = onUnpair)

            !permsOk -> SCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(SIcons.Bluetooth, SColors.Warning)
                    HSpace(14)
                    Text(s.bluetoothPermissionNeeded, style = SType.Body, modifier = Modifier.weight(1f))
                }
                VSpace(14)
                SButton(s.allow, onRequestPerms, Modifier.fillMaxWidth())
            }

            !btOn -> SCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(SIcons.BluetoothOff, SColors.Warning)
                    HSpace(14)
                    Text(s.bluetoothOff, style = SType.CardTitle, modifier = Modifier.weight(1f))
                }
                VSpace(14)
                SButton(s.turnOnBluetooth, onEnableBt, Modifier.fillMaxWidth())
            }

            else -> {
                SectionLabel(s.nearbyBands, action = if (state.scanning) null else (s.searchAgain to onScan))
                if (state.scanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = SColors.Accent, strokeWidth = 2.dp)
                        HSpace(12)
                        Text(s.searchingBands, style = SType.Body)
                    }
                }
                if (locationOff) Banner(s.locationNeeded, Tone.Warn, icon = SIcons.LocationOff)
                state.scanFailed?.let { Banner(s.scanFailedMsg(it), Tone.Danger) }
                state.found.forEach { band ->
                    // A band that doesn't advertise the service can still be tapped: the app connects and checks
                    // the real GATT table, and reports exactly what is missing (nothing is assumed).
                    val canLink = state.linking == null && patientReady
                    SListRow(
                        band.name ?: s.unnamedDevice,
                        icon = if (band.hasService) SIcons.Bluetooth else SIcons.BluetoothOff,
                        iconColor = if (band.hasService) SColors.Accent else SColors.Danger,
                        subtitle = listOfNotNull(
                            if (band.hasService) s.advertisesService else s.advertisedWrong(band.advertisedServices),
                            "${s.bandSignal} ${Format.signalBars(band.rssi)}/4",
                            if (band.nameMatches || !band.hasService) null else s.bandNameUnexpected,
                        ).joinToString(" · "),
                        fullWidth = true,
                        onClick = if (canLink) ({ onLink(band) }) else null,
                        trailing = {
                            if (state.linking == band.address) {
                                CircularProgressIndicator(Modifier.size(20.dp), color = SColors.Accent, strokeWidth = 2.dp)
                            }
                        },
                    )
                }
                if (state.scanning || state.scannedOnce) {
                    Text(s.devicesSeenMsg(state.devicesSeen), style = SType.Caption)
                }
                if (!state.scanning && state.scannedOnce && state.found.none { it.hasService }) {
                    SCard {
                        EmptyState(
                            SIcons.BluetoothSearching, s.nearbyBands,
                            if (state.devicesSeen == 0) s.noDevicesAtAll else s.noBandsFound,
                        )
                    }
                }
            }
        }

        if (paired) {
            SectionLabel(s.preferences)
            PermissionCard(
                icon = SIcons.Sms, title = s.smsBackupTitle, body = s.smsBackupBody,
                granted = smsOk, grantedText = s.smsAllowed, buttonText = s.allow, available = smsAvailable,
                onRequest = onSms,
            )
            com.sahayat.app.ui.components.LocationPermissionCard()
            PermissionCard(
                icon = SIcons.BatterySaver, title = s.batteryTitle, body = s.batteryBody,
                granted = batteryOk, grantedText = s.batteryAllowed, buttonText = s.allow, available = true,
                onRequest = onBattery,
            )
            if (notificationsMissing) {
                SListRow(s.notifications, icon = SIcons.Notifications, subtitle = s.notificationsSubtitle, fullWidth = true, onClick = { navigate(Routes.NOTIFICATION_SETTINGS) })
            }
            SListRow(s.inviteCaregiver, icon = SIcons.PersonAdd, subtitle = s.inviteSubtitle, fullWidth = true, onClick = { navigate(Routes.INVITE) })
        }
}

@Composable
private fun PairedSection(s: AppStrings, name: String, deviceId: String?, justLinked: Boolean, onUnpair: () -> Unit) {
    SCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(SIcons.BluetoothConnected)
            HSpace(14)
            Column(Modifier.weight(1f)) {
                Text(if (justLinked) s.bandLinkedTitle else name, style = SType.CardTitle)
                Text(deviceId.orEmpty(), style = SType.Caption)
            }
            StatusPill(s.bandLink, Tone.Ok)
        }
        if (justLinked) {
            VSpace(10)
            Text(s.bandLinkedBody, style = SType.Body)
        }
        VSpace(14)
        SButton(s.unpairBand, onUnpair, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, icon = SIcons.Unlink)
    }
}

@Composable
private fun PermissionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String,
    granted: Boolean,
    grantedText: String,
    buttonText: String,
    available: Boolean,
    onRequest: () -> Unit,
) {
    if (!available) return
    SCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, if (granted) SColors.Accent else SColors.Warning)
            HSpace(14)
            Column(Modifier.weight(1f)) {
                Text(title, style = SType.CardTitle)
                Text(if (granted) grantedText else body, style = SType.Caption)
            }
        }
        if (!granted) {
            VSpace(12)
            SButton(buttonText, onRequest, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary)
        }
    }
}
