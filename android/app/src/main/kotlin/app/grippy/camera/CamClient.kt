// SPDX-License-Identifier: MPL-2.0
// Grippy: the main phone's line to the camera phone. Not part of upstream.

package app.grippy.camera

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ConnectionPriorityRequest
import no.nordicsemi.android.ble.observer.ConnectionObserver

/**
 * Finds the phone that is being the camera (by its Bluetooth service, so there is nothing to pair
 * or type), connects, and carries commands one way and events the other. Main thread only.
 */
@SuppressLint("MissingPermission") // every Bluetooth call sits behind [hasPermission]
class CamClient(context: Context) {
    enum class Link(val label: String) {
        Off("Not connected"),
        NoPermission("Grippy needs the Nearby devices permission"),
        BluetoothOff("Bluetooth is off"),
        Searching("Looking for your camera phone…"),
        Connecting("Connecting to your camera phone…"),
        Ready("Camera phone connected"),
    }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var manager: Manager? = null
    private var wanted = false
    private var scanning = false
    private val assembler = CamPackets.Assembler()

    var link by mutableStateOf(Link.Off)
        private set
    /** The camera's last word on what it is doing. */
    var status by mutableStateOf<CamEvent.Status?>(null)
        private set
    /** Everything the camera says, as it arrives. */
    var onEvent: ((CamEvent) -> Unit)? = null

    val hasPermission: Boolean
        get() = REQUIRED.all { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }

    /** Start looking for the camera phone and stay connected until [disconnect]. */
    fun connect() {
        wanted = true
        if (link == Link.Ready || link == Link.Connecting || scanning) return
        search()
    }

    fun disconnect() {
        wanted = false
        stopScan()
        main.removeCallbacksAndMessages(null)
        manager?.disconnect()?.enqueue()
        status = null
        link = Link.Off
    }

    /** False when there is no camera to send to right now. */
    fun send(command: CamCommand): Boolean {
        val m = manager ?: return false
        if (link != Link.Ready) return false
        m.write(CamCodec.encode(command))
        return true
    }

    private fun search() {
        if (!wanted) return
        if (!hasPermission) { link = Link.NoPermission; return }
        val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) { link = Link.BluetoothOff; retryLater(); return }
        link = Link.Searching
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(CamLink.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, scan); scanning = true }
            .onFailure { Log.w(TAG, "scan refused", it); retryLater() }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(scan) }
    }

    private fun retryLater() {
        main.postDelayed({ if (wanted && link != Link.Ready && link != Link.Connecting && !scanning) search() }, RETRY_MS)
    }

    private val scan = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { found(result.device) }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post { scanning = false; retryLater() }
        }
    }

    private fun found(device: BluetoothDevice) {
        if (!scanning || !wanted) return
        stopScan()
        link = Link.Connecting
        val m = manager ?: Manager(app).also { it.setConnectionObserver(observer); manager = it }
        assembler.reset()
        m.connect(device).useAutoConnect(false).retry(2, 300).timeout(CONNECT_TIMEOUT_MS).enqueue()
    }

    private val observer = object : ConnectionObserver {
        override fun onDeviceConnecting(device: BluetoothDevice) = Unit
        override fun onDeviceConnected(device: BluetoothDevice) = Unit
        override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit

        override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
            main.post { lost() }
        }

        override fun onDeviceReady(device: BluetoothDevice) {
            main.post {
                link = Link.Ready
                send(CamCommand.Hello)
            }
        }

        override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
            main.post { lost() }
        }
    }

    private fun lost() {
        status = null
        if (!wanted) { link = Link.Off; return }
        link = Link.Searching
        if (!scanning) main.postDelayed({ if (wanted && !scanning && link == Link.Searching) search() }, 400)
    }

    private fun receive(event: CamEvent) {
        if (event is CamEvent.Status) status = event
        onEvent?.invoke(event)
    }

    private inner class Manager(context: Context) : BleManager(context) {
        private var command: BluetoothGattCharacteristic? = null
        private var event: BluetoothGattCharacteristic? = null

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val service = gatt.getService(CamLink.SERVICE) ?: return false
            command = service.getCharacteristic(CamLink.COMMAND)
            event = service.getCharacteristic(CamLink.EVENT)
            return command != null && event != null
        }

        override fun initialize() {
            requestMtu(MTU).enqueue()
            // Short connection interval: the count should feel instant.
            requestConnectionPriority(ConnectionPriorityRequest.CONNECTION_PRIORITY_HIGH).enqueue()
            setNotificationCallback(event).with { _, data ->
                val bytes = data.value ?: return@with
                main.post { assembler.accept(bytes)?.let(CamCodec::decodeEvent)?.let(::receive) }
            }
            enableNotifications(event).enqueue()
        }

        override fun onServicesInvalidated() {
            command = null
            event = null
        }

        fun write(message: ByteArray) {
            val target = command ?: return
            for (part in CamPackets.split(message, mtu - ATT_OVERHEAD)) {
                writeCharacteristic(target, part, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
            }
        }
    }

    companion object {
        private const val TAG = "GrippyCamClient"
        private const val MTU = 247
        private const val ATT_OVERHEAD = 3
        private const val RETRY_MS = 2_500L
        private const val CONNECT_TIMEOUT_MS = 12_000L
        val REQUIRED = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

        private var owner: Context? = null
        private var instance: CamClient? = null

        /** One per app (Robolectric makes a fresh app per test). */
        @Synchronized
        fun get(context: Context): CamClient {
            val app = context.applicationContext
            instance?.let { if (owner === app) return it }
            return CamClient(app).also { instance = it; owner = app }
        }
    }
}
