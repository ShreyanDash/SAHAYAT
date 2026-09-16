package com.sahayat.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

private const val TAG = "SahayatBLE"

/** Runtime permissions needed to scan for and talk to the band on this Android version. */
object BlePermissions {
    /** Android 11 and older only return BLE scan results while Location is switched on. */
    fun locationNeededAndOff(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 31) return false
        val lm = context.getSystemService(android.location.LocationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 28) !lm.isLocationEnabled
        else !lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) &&
            !lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    }

    val required: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun granted(context: Context): Boolean = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    fun bluetoothOn(context: Context): Boolean = adapter(context)?.isEnabled == true
}

/**
 * A nearby device that advertises the SAHAYAT service ([hasService]) — or is named like a SAHAYAT band but
 * does NOT advertise the service (shown only as a diagnostic: old firmware). Identity is verified after connecting.
 */
data class FoundBand(
    val address: String,
    val name: String?,
    val rssi: Int,
    val hasService: Boolean,
    /** Service UUIDs this device actually put in its advertisement (diagnostic). */
    val advertisedServices: List<String> = emptyList(),
) {
    val nameMatches: Boolean get() = name == BandProtocol.EXPECTED_NAME
}

/** Scans for devices advertising the SAHAYAT service UUID (never 0x2902, never by name alone). */
object BandScanner {
    /**
     * Scans without a hardware filter (some phones' Bluetooth chips drop 128-bit UUID filters) and matches the
     * SAHAYAT service UUID in code. [onAnyDevice] receives the address of every advertiser seen, so the UI can
     * tell "Bluetooth scanning works but no band" apart from "nothing at all".
     */
    @SuppressLint("MissingPermission")
    fun scan(context: Context, onAnyDevice: (String) -> Unit = {}): Flow<FoundBand> = callbackFlow {
        val scanner = BlePermissions.adapter(context)?.bluetoothLeScanner
        if (scanner == null || !BlePermissions.granted(context)) {
            close()
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                onAnyDevice(result.device.address)
                val hasService = record?.serviceUuids?.any { it.uuid == BandProtocol.SERVICE } == true
                val name = record?.deviceName ?: runCatching { result.device.name }.getOrNull()
                val looksLikeBand = name?.startsWith("SAHAYAT", ignoreCase = true) == true
                if (!hasService && !looksLikeBand) return
                val advertised = record?.serviceUuids?.map { it.uuid.toString().uppercase() }.orEmpty()
                trySend(FoundBand(result.device.address, name, result.rssi, hasService, advertised))
            }

            override fun onScanFailed(errorCode: Int) {
                Log.w(TAG, "Scan failed: $errorCode")
                close(IllegalStateException("Scan failed ($errorCode)"))
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        // Empty filter list + in-code service UUID match (see above).
        scanner.startScan(emptyList<ScanFilter>(), settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}

enum class BandLinkError {
    /** Connected, but the SAHAYAT service is not on this device (old firmware?). */
    SERVICE_NOT_FOUND,
    /** The service exists but a required characteristic or its CCCD is missing. */
    CHARACTERISTIC_MISSING,
    /** Device ID characteristic did not contain a valid SAH-xxxx id. */
    DEVICE_ID_INVALID,
    /** A different band answered (Device ID doesn't match the paired one). */
    WRONG_DEVICE,
    /** Enabling notifications failed. */
    NOTIFY_FAILED,
    /** Could not connect at all. */
    CONNECT_FAILED,
}

/**
 * One GATT connection to the band. All GATT operations are serialized on a private thread.
 * The band counts as connected only after the whole setup succeeded:
 * discover → check service/characteristics → MTU → read + verify Device ID → enable Status/Alert
 * notifications → read Status → read Alert.
 *
 * @param expectedDeviceId the paired band's id; null accepts any valid SAH-xxxx id (pairing check).
 * @param autoReconnect keep reconnecting after drops (relay service) or give up (pairing check).
 */
class BandBleClient(
    private val context: Context,
    private val listener: Listener,
    private val expectedDeviceId: String?,
    private val autoReconnect: Boolean = true,
) {
    interface Listener {
        /** true only after full setup; false when a ready link is lost. */
        fun onConnectionChanged(connected: Boolean)
        fun onDeviceId(deviceId: String) {}
        fun onStatus(status: BandStatus)
        /** [fromNotification] is false for the value read right after connecting. */
        fun onAlert(alert: BandAlert, fromNotification: Boolean)
        fun onRssi(rssi: Int) {}
        fun onError(error: BandLinkError) {}
    }

    private val thread = HandlerThread("sahayat-ble").apply { start() }
    private val handler = Handler(thread.looper)
    private var gatt: BluetoothGatt? = null
    private var address: String? = null
    private var wanted = false
    private var ready = false
    private var retry = 0
    private var readDeviceId: String? = null
    private val ops = ArrayDeque<() -> Boolean>()
    private var busy = false
    private var opToken = 0

    fun connect(address: String) = handler.post {
        this.address = address
        wanted = true
        retry = 0
        openGatt()
    }

    fun disconnect() = handler.post {
        wanted = false
        closeGatt(notify = true)
    }

    fun release() {
        disconnect()
        handler.post { thread.quitSafely() }
    }

    /** Writes a text command (PING / START / STOP / STATUS). Ignored unless connected. */
    fun write(value: ByteArray) = handler.post {
        if (ready) enqueue { writeCommand(value) }
    }

    fun readStatus() = handler.post {
        if (ready) enqueue { read(BandProtocol.STATUS) }
    }

    fun readRssi() = handler.post {
        if (ready) runCatching { gattCall { it.readRemoteRssi() } }
    }

    val isReady: Boolean get() = ready

    // ------------------------------------------------------------------ internals

    @SuppressLint("MissingPermission")
    private inline fun gattCall(block: (BluetoothGatt) -> Boolean): Boolean {
        val g = gatt ?: return false
        if (!BlePermissions.granted(context)) return false
        return block(g)
    }

    @SuppressLint("MissingPermission")
    private fun openGatt() {
        val addr = address ?: return
        if (!BlePermissions.granted(context) || !BlePermissions.bluetoothOn(context)) {
            scheduleReconnect()
            return
        }
        closeGatt(notify = false)
        val device: BluetoothDevice = try {
            BlePermissions.adapter(context)?.getRemoteDevice(addr) ?: return
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Bad address")
            return
        }
        Log.i(TAG, "Connecting (attempt ${retry + 1})")
        readDeviceId = null
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt(notify: Boolean) {
        val wasReady = ready
        val g = gatt
        gatt = null
        ready = false
        ops.clear()
        busy = false
        if (g != null && BlePermissions.granted(context)) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        if (notify && wasReady) listener.onConnectionChanged(false)
    }

    private fun fail(error: BandLinkError, retryLater: Boolean) {
        Log.w(TAG, "Band link error: $error")
        listener.onError(error)
        closeGatt(notify = true)
        if (retryLater) scheduleReconnect() else wanted = false
    }

    private fun scheduleReconnect() {
        if (!wanted) return
        if (!autoReconnect) {
            wanted = false
            listener.onError(BandLinkError.CONNECT_FAILED)
            return
        }
        val delays = longArrayOf(2_000, 5_000, 10_000, 20_000, 30_000)
        val delay = delays[retry.coerceAtMost(delays.lastIndex)]
        retry++
        handler.postDelayed({ if (wanted && gatt == null) openGatt() }, delay)
    }

    private fun enqueue(op: () -> Boolean) {
        ops.addLast(op)
        if (!busy) next()
    }

    private fun next() {
        busy = false
        while (ops.isNotEmpty()) {
            val op = ops.removeFirst()
            if (op()) {
                busy = true
                val token = ++opToken
                // Watchdog: never stall the queue if a GATT callback is lost.
                handler.postDelayed({ if (busy && token == opToken) next() }, 5_000)
                return
            }
        }
    }

    private fun service() = gatt?.getService(BandProtocol.SERVICE)

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic? = service()?.getCharacteristic(uuid)

    @SuppressLint("MissingPermission")
    private fun enableNotify(uuid: UUID): Boolean = gattCall { g ->
        val ch = characteristic(uuid) ?: return@gattCall false
        if (!g.setCharacteristicNotification(ch, true)) return@gattCall false
        val cccd = ch.getDescriptor(BandProtocol.CCCD) ?: return@gattCall false
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, value) == 0
        } else {
            @Suppress("DEPRECATION")
            cccd.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }

    @SuppressLint("MissingPermission")
    private fun read(uuid: UUID): Boolean = gattCall { g ->
        val ch = characteristic(uuid) ?: return@gattCall false
        g.readCharacteristic(ch)
    }

    @SuppressLint("MissingPermission")
    private fun writeCommand(value: ByteArray): Boolean = gattCall { g ->
        val ch = characteristic(BandProtocol.COMMAND) ?: return@gattCall false
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
        } else {
            @Suppress("DEPRECATION")
            ch.value = value
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    private fun route(uuid: UUID, value: ByteArray?, fromNotification: Boolean) {
        when (uuid) {
            BandProtocol.DEVICE_ID -> readDeviceId = BandProtocol.parseDeviceId(value)
            BandProtocol.STATUS -> BandProtocol.parseStatus(value)?.let(listener::onStatus)
            BandProtocol.ALERT -> BandProtocol.parseAlert(value)?.let { listener.onAlert(it, fromNotification) }
        }
    }

    /** Runs after service discovery. */
    @SuppressLint("MissingPermission")
    private fun setUp(g: BluetoothGatt) {
        val svc = g.getService(BandProtocol.SERVICE)
        if (svc == null) {
            fail(BandLinkError.SERVICE_NOT_FOUND, retryLater = autoReconnect)
            return
        }
        val required = listOf(BandProtocol.DEVICE_ID, BandProtocol.STATUS, BandProtocol.ALERT, BandProtocol.COMMAND)
        val missing = required.any { svc.getCharacteristic(it) == null } ||
            svc.getCharacteristic(BandProtocol.STATUS)?.getDescriptor(BandProtocol.CCCD) == null ||
            svc.getCharacteristic(BandProtocol.ALERT)?.getDescriptor(BandProtocol.CCCD) == null
        if (missing) {
            fail(BandLinkError.CHARACTERISTIC_MISSING, retryLater = autoReconnect)
            return
        }
        retry = 0
        enqueue { gattCall { it.requestMtu(BandProtocol.MTU) } }
        enqueue { read(BandProtocol.DEVICE_ID) }
        enqueue {
            val id = readDeviceId
            when {
                id == null -> fail(BandLinkError.DEVICE_ID_INVALID, retryLater = false)
                expectedDeviceId != null && id != expectedDeviceId -> fail(BandLinkError.WRONG_DEVICE, retryLater = false)
                else -> listener.onDeviceId(id)
            }
            false
        }
        enqueue {
            if (!enableNotify(BandProtocol.STATUS)) fail(BandLinkError.NOTIFY_FAILED, retryLater = autoReconnect)
            gatt != null
        }
        enqueue {
            if (!enableNotify(BandProtocol.ALERT)) fail(BandLinkError.NOTIFY_FAILED, retryLater = autoReconnect)
            gatt != null
        }
        enqueue { read(BandProtocol.STATUS) }
        enqueue { read(BandProtocol.ALERT) }
        enqueue {
            if (gatt != null) {
                ready = true
                Log.i(TAG, "Band ready")
                listener.onConnectionChanged(true)
            }
            false
        }
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (g != gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "GATT connected, discovering services")
                    if (!BlePermissions.granted(context) || !g.discoverServices()) {
                        closeGatt(notify = true)
                        scheduleReconnect()
                    }
                } else {
                    Log.i(TAG, "GATT disconnected (status $status)")
                    closeGatt(notify = true)
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (g != gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    closeGatt(notify = true)
                    scheduleReconnect()
                } else {
                    setUp(g)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (g != gatt) return@post
                Log.i(TAG, "MTU $mtu (status $status)")
                next()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            handler.post {
                if (g != gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fail(BandLinkError.NOTIFY_FAILED, retryLater = autoReconnect)
                } else {
                    next()
                }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            handler.post {
                if (g != gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) Log.w(TAG, "Command write failed: $status")
                next()
            }
        }

        // Android 13+
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            val uuid = characteristic.uuid
            handler.post {
                if (g != gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) route(uuid, value, fromNotification = false)
                next()
            }
        }

        // Android 12 and below
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val value = characteristic.value?.copyOf()
            val uuid = characteristic.uuid
            handler.post {
                if (g != gatt) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) route(uuid, value, fromNotification = false)
                next()
            }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val uuid = characteristic.uuid
            handler.post { if (g == gatt) route(uuid, value, fromNotification = true) }
        }

        // Android 12 and below
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            val value = characteristic.value?.copyOf()
            val uuid = characteristic.uuid
            handler.post { if (g == gatt) route(uuid, value, fromNotification = true) }
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) handler.post { listener.onRssi(rssi) }
        }
    }
}

/** Result of connecting to a scanned band once, before linking it to the patient. */
sealed interface ProbeResult {
    data class Verified(val deviceId: String) : ProbeResult
    data class Failed(val error: BandLinkError) : ProbeResult
}

/**
 * Pairing check: really connects, reads and validates the Device ID, confirms Status/Alert
 * notifications can be enabled, then disconnects. Nothing is assumed.
 */
object BandProbe {
    suspend fun verify(context: Context, address: String, timeoutMs: Long = 25_000): ProbeResult {
        val result = CompletableDeferred<ProbeResult>()
        var id: String? = null
        val client = BandBleClient(
            context.applicationContext,
            object : BandBleClient.Listener {
                override fun onConnectionChanged(connected: Boolean) {
                    val deviceId = id
                    if (connected && deviceId != null) result.complete(ProbeResult.Verified(deviceId))
                }
                override fun onDeviceId(deviceId: String) { id = deviceId }
                override fun onStatus(status: BandStatus) {}
                override fun onAlert(alert: BandAlert, fromNotification: Boolean) {}
                override fun onError(error: BandLinkError) { result.complete(ProbeResult.Failed(error)) }
            },
            expectedDeviceId = null,
            autoReconnect = false,
        )
        client.connect(address)
        val r = withTimeoutOrNull(timeoutMs) { result.await() } ?: ProbeResult.Failed(BandLinkError.CONNECT_FAILED)
        client.release()
        return r
    }
}
