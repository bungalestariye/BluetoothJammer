package api

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat

class ScanNearbyDevices private constructor() {

    private var isScanning = false
    private var appContext: Context? = null
    private var adapter: BluetoothAdapter? = null
    private var callback: ((List<BluetoothDeviceInfo>) -> Unit)? = null
    private var receiverRegistered = false

    // Keyed by MAC address so each device appears exactly once; insertion
    // order is preserved so the list doesn't jump around as devices arrive.
    private val devices = LinkedHashMap<String, BluetoothDeviceInfo>()

    companion object {
        private const val TAG = "ScanNearbyDevices"

        @Volatile
        private var instance: ScanNearbyDevices? = null

        fun getInstance(): ScanNearbyDevices =
            instance ?: synchronized(this) {
                instance ?: ScanNearbyDevices().also { instance = it }
            }
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (device != null) addDevice(device)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    // A discovery pass only lasts ~12s, so restart it to keep
                    // finding devices while the screen is open.
                    if (isScanning) startDiscovery()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScanning(context: Context, callback: (List<BluetoothDeviceInfo>) -> Unit) {
        if (isScanning) return
        appContext = context.applicationContext
        this.callback = callback

        val bluetoothManager =
            appContext?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        adapter = bluetoothManager?.adapter
        if (adapter?.isEnabled != true) {
            Log.e(TAG, "Bluetooth is disabled, cannot scan.")
            return
        }

        isScanning = true
        devices.clear()

        // Seed the list with already-paired devices so it's never empty.
        adapter?.bondedDevices?.forEach { addDevice(it) }

        registerReceiver()
        notifyUpdate()
        startDiscovery()
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        val a = adapter ?: return
        try {
            if (a.isDiscovering) a.cancelDiscovery()
            a.startDiscovery()
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission to start discovery", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun addDevice(device: BluetoothDevice) {
        val address = device.address ?: return
        val info = BluetoothDeviceInfo(
            name = device.name ?: "Unknown Device",
            address = address
        )
        val isNew = !devices.containsKey(address)
        devices[address] = info
        if (isNew) notifyUpdate()
    }

    private fun notifyUpdate() {
        callback?.invoke(devices.values.toList())
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        appContext?.let {
            // System (protected) broadcasts; NOT_EXPORTED satisfies Android 14+.
            ContextCompat.registerReceiver(it, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        if (!isScanning) return
        isScanning = false
        try {
            adapter?.cancelDiscovery()
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission to cancel discovery", e)
        }
        if (receiverRegistered) {
            try {
                appContext?.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                // Already unregistered - ignore.
            }
            receiverRegistered = false
        }
    }

    fun resumeScanning() {
        if (isScanning) return
        val ctx = appContext ?: return
        val cb = callback ?: return
        startScanning(ctx, cb)
    }
}

data class BluetoothDeviceInfo(
    val name: String,
    val address: String
)
