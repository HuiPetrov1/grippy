// SPDX-License-Identifier: MPL-2.0
// Grippy: the camera phone waiting for the main phone over Bluetooth. Not part of upstream.

package app.grippy.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import run.nuri.getagrip.R

/**
 * Runs for as long as this phone is the camera. It shows up over Bluetooth as "a Grippy camera",
 * and when the main phone says Wake it lights the screen and opens the camera — over the lock
 * screen, without unlocking the phone.
 */
@SuppressLint("MissingPermission") // every Bluetooth call sits behind [hasBluetooth]
class CameraLinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var server: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var eventCharacteristic: BluetoothGattCharacteristic? = null
    private val subscribers = LinkedHashMap<String, BluetoothDevice>()
    private val mtus = HashMap<String, Int>()
    private val assemblers = HashMap<String, CamPackets.Assembler>()

    private class Outgoing(val event: CamEvent)
    private val outbox = Channel<Outgoing>(Channel.UNLIMITED)
    private var queued = 0
    private var notified: CompletableDeferred<Unit>? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channels(this)
        val notification = standbyNotification()
        val foreground = runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(STANDBY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(STANDBY_ID, notification)
        }
        if (foreground.isFailure) {
            Log.w(TAG, "could not go foreground", foreground.exceptionOrNull())
            CamHub.problem.value = "Android would not let the camera link run in the background. Open Grippy once."
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            registerReceiver(bluetoothState, IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED))
            scope.launch { pump() }
            scope.launch { CamHub.events.collect { send(it) } }
            open()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(bluetoothState) }
        close()
        scope.cancel()
        CamHub.advertising.value = false
        CamHub.listeners.value = 0
        super.onDestroy()
    }

    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                android.bluetooth.BluetoothAdapter.STATE_ON -> open()
                android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF, android.bluetooth.BluetoothAdapter.STATE_OFF -> close()
            }
        }
    }

    private fun open() {
        if (server != null) return
        if (!hasBluetooth(this)) { CamHub.problem.value = "Allow Bluetooth (\"Nearby devices\") for Grippy."; return }
        val manager = getSystemService(BluetoothManager::class.java)
        val adapter = manager?.adapter
        if (adapter == null || !adapter.isEnabled) { CamHub.problem.value = "Turn Bluetooth on."; return }
        val gatt = runCatching { manager.openGattServer(this, callback) }.getOrNull()
        if (gatt == null) { CamHub.problem.value = "Bluetooth would not start. Turn it off and on again."; return }
        val service = BluetoothGattService(CamLink.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val command = BluetoothGattCharacteristic(
            CamLink.COMMAND,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val event = BluetoothGattCharacteristic(CamLink.EVENT, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        event.addDescriptor(BluetoothGattDescriptor(CamLink.CLIENT_CONFIG, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        service.addCharacteristic(command)
        service.addCharacteristic(event)
        gatt.addService(service)
        server = gatt
        eventCharacteristic = event
        advertiser = adapter.bluetoothLeAdvertiser
        advertise()
    }

    private fun advertise() {
        val ad = advertiser
        if (ad == null) { CamHub.problem.value = "This phone cannot be found over Bluetooth (no advertising)."; return }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true).setTimeout(0).build()
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(CamLink.SERVICE)).setIncludeDeviceName(false).build()
        runCatching { ad.stopAdvertising(advertising) }
        runCatching { ad.startAdvertising(settings, data, advertising) }.onFailure {
            CamHub.problem.value = "Bluetooth advertising failed to start."
        }
    }

    private val advertising = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            CamHub.advertising.value = true
            CamHub.problem.value = ""
        }

        override fun onStartFailure(errorCode: Int) {
            if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) { CamHub.advertising.value = true; return }
            CamHub.advertising.value = false
            CamHub.problem.value = "Bluetooth advertising failed (code $errorCode)."
        }
    }

    private fun close() {
        runCatching { advertiser?.stopAdvertising(advertising) }
        runCatching { server?.close() }
        server = null
        eventCharacteristic = null
        subscribers.clear(); mtus.clear(); assemblers.clear()
        CamHub.advertising.value = false
        CamHub.listeners.value = 0
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            scope.launch {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    subscribers.remove(device.address); mtus.remove(device.address); assemblers.remove(device.address)
                    CamHub.listeners.value = subscribers.size
                    // Some phones stop advertising once someone connects: make sure we can be found again.
                    advertise()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            scope.launch { mtus[device.address] = mtu }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) }
            if (characteristic.uuid != CamLink.COMMAND || value == null) return
            scope.launch {
                val whole = assemblers.getOrPut(device.address) { CamPackets.Assembler() }.accept(value) ?: return@launch
                CamCodec.decodeCommand(whole)?.let { receive(it) }
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null) }
            if (descriptor.uuid != CamLink.CLIENT_CONFIG) return
            val on = value != null && value.isNotEmpty() && value[0].toInt() != 0
            scope.launch {
                if (on) subscribers[device.address] = device else subscribers.remove(device.address)
                CamHub.listeners.value = subscribers.size
                if (on) send(currentStatus())
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor) {
            val value = if (subscribers.containsKey(device.address)) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            runCatching { server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value) }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            notified?.complete(Unit)
        }
    }

    private fun receive(command: CamCommand) {
        when (command) {
            CamCommand.Hello -> send(currentStatus())
            is CamCommand.Wake -> {
                CameraRole.setFrontCamera(this, command.frontCamera)
                CamHub.clearCommands()
                CamHub.commands.trySend(command)
                if (!CamHub.screenOpen.value) {
                    CamHub.status.value = CamHub.status.value.copy(state = CamState.Starting, note = "")
                    send(currentStatus())
                    wakeScreen(this)
                    CameraActivity.open(this, byLink = true)
                }
            }
            else -> CamHub.commands.trySend(command)
        }
    }

    private fun currentStatus(): CamEvent.Status = CamHub.status.value.copy(battery = battery())

    private fun battery(): Int =
        getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 0

    /** Frames are only worth sending fresh: when the radio is behind, they are dropped, never queued up. */
    private fun send(event: CamEvent) {
        if (subscribers.isEmpty()) return
        if (event is CamEvent.Frame && queued > MAX_QUEUED_FRAMES) return
        queued++
        outbox.trySend(Outgoing(event))
    }

    /** One packet at a time: Android wants each notification acknowledged before the next. */
    private suspend fun pump() {
        for (out in outbox) {
            queued--
            val gatt = server ?: continue
            val characteristic = eventCharacteristic ?: continue
            val bytes = CamCodec.encode(out.event)
            for (device in subscribers.values.toList()) {
                val packet = (mtus[device.address] ?: DEFAULT_MTU) - ATT_OVERHEAD
                for (part in CamPackets.split(bytes, packet)) {
                    val done = CompletableDeferred<Unit>()
                    notified = done
                    val accepted = runCatching {
                        if (Build.VERSION.SDK_INT >= 33) {
                            gatt.notifyCharacteristicChanged(device, characteristic, false, part) == BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION") characteristic.value = part
                            @Suppress("DEPRECATION") gatt.notifyCharacteristicChanged(device, characteristic, false)
                        }
                    }.getOrDefault(false)
                    if (accepted) withTimeoutOrNull(NOTIFY_TIMEOUT_MS) { done.await() } else delay(15)
                }
            }
        }
    }

    private fun standbyNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, CameraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, STANDBY_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("Grippy camera is ready")
            .setContentText("Press record on your main phone to start counting.")
            .setOngoing(true).setSilent(true).setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "GrippyCamLink"
        private const val STANDBY_CHANNEL = "grippy.camera.standby"
        const val WAKE_CHANNEL = "grippy.camera.wake"
        private const val STANDBY_ID = 7301
        const val WAKE_ID = 7302
        private const val DEFAULT_MTU = 23
        private const val ATT_OVERHEAD = 3
        private const val NOTIFY_TIMEOUT_MS = 800L
        private const val MAX_QUEUED_FRAMES = 4

        fun hasBluetooth(context: Context): Boolean =
            listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT).all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }

        fun channels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(STANDBY_CHANNEL, "Camera standby", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while this phone waits to be your camera."
                    setShowBadge(false)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(WAKE_CHANNEL, "Camera wake-up", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Opens the camera when your main phone asks."
                    setSound(null, null)
                    setShowBadge(false)
                },
            )
        }

        /** Starts the link if this phone is the camera and Android allows it right now. */
        fun start(context: Context) {
            if (!CameraRole.isCamera(context)) return
            if (!hasBluetooth(context)) { CamHub.problem.value = "Allow Bluetooth (\"Nearby devices\") for Grippy."; return }
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CameraLinkService::class.java)) }
                .onFailure { Log.w(TAG, "start refused", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraLinkService::class.java))
        }

        /** Lights the screen. Showing the camera on top of the lock screen is the camera screen's own doing. */
        @Suppress("DEPRECATION")
        fun wakeScreen(context: Context) {
            val power = context.getSystemService(PowerManager::class.java) ?: return
            runCatching {
                power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "grippy:camera-wake")
                    .acquire(8_000)
            }
        }
    }
}

/** After a restart the camera phone goes back to waiting, without anyone opening the app. */
class CameraBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            CameraLinkService.start(context)
        }
    }
}
