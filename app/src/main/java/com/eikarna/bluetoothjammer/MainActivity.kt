package com.eikarna.bluetoothjammer

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import api.BluetoothDeviceInfo
import api.ScanNearbyDevices
import com.google.android.material.textview.MaterialTextView
import java.util.Date

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: MaterialTextView
    private lateinit var deviceListAdapter: ArrayAdapter<String>
    private var devices: List<BluetoothDeviceInfo> = emptyList()
    private val scanner = ScanNearbyDevices.getInstance()

    // Tracks whether the Bluetooth flow has been started so lifecycle
    // callbacks (onResume/onPause) don't touch Bluetooth before we have
    // permission - that was the original launch crash.
    private var bluetoothFlowStarted = false
    private var receiverRegistered = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById<Toolbar>(R.id.topAppBar))

        listView = findViewById(R.id.deviceListView)
        emptyView = findViewById(R.id.emptyView)
        listView.emptyView = emptyView

        // Nothing that touches Bluetooth runs until permissions are granted.
        checkPermissionsAndStart()
    }

    /**
     * Requests every permission we need up-front. Only once they are all
     * granted do we enable Bluetooth, request discoverability and scan.
     */
    private fun checkPermissionsAndStart() {
        val permissions = requiredPermissions()
        if (hasPermissions(permissions)) {
            onPermissionsGranted()
        } else {
            ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
        }
    }

    private fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ runtime Bluetooth permissions. ADVERTISE is required
            // for ACTION_REQUEST_DISCOVERABLE, otherwise it throws a
            // SecurityException and crashes the app.
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
    }

    private fun onPermissionsGranted() {
        val bluetoothManager: BluetoothManager? = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "This device has no Bluetooth adapter.", Toast.LENGTH_LONG).show()
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            // Ask the user to turn Bluetooth on, then wait for onResume to retry.
            requestEnableBluetooth()
            return
        }

        startBluetoothFlow()
    }

    @SuppressLint("MissingPermission")
    private fun requestEnableBluetooth() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        startActivityForResult(enableBtIntent, 1)
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothFlow() {
        if (bluetoothFlowStarted) return
        bluetoothFlowStarted = true

        // Make this device discoverable. Safe now that BLUETOOTH_ADVERTISE is granted.
        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 1200)
        }
        startActivityForResult(discoverableIntent, 2)

        registerDiscoveryReceiver()
        startScanningForDevices()
    }

    private fun registerDiscoveryReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    // Create a BroadcastReceiver for ACTION_FOUND.
    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            val action: String? = intent.action
            Log.d("MainActivity", "Action: $action")
            if (BluetoothDevice.ACTION_FOUND == action) {
                val device: BluetoothDevice? =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                val deviceInfo = BluetoothDeviceInfo(
                    name = device?.name ?: "Unknown Device",
                    address = device?.address ?: "00:00:00:00"
                )
                Toast.makeText(
                    this@MainActivity,
                    "Found new device!\n\n${deviceInfo.name}\n${deviceInfo.address}\n\n${Date()}",
                    Toast.LENGTH_SHORT
                ).show()
                ScanNearbyDevices.devicesList.add(deviceInfo)
            }
        }
    }

    // Handle the permission request result
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                onPermissionsGranted()
            } else {
                Toast.makeText(
                    this,
                    "Bluetooth & location permissions are required to scan for devices.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun hasPermissions(permissions: Array<String>): Boolean {
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startScanningForDevices() {
        scanner.startScanning(this) { discoveredDevices ->
            devices = discoveredDevices
            val deviceNames = devices.map { "${it.name}\n${it.address}" }

            deviceListAdapter = ArrayAdapter(this, R.layout.item_device, R.id.deviceText, deviceNames)
            listView.adapter = deviceListAdapter

            listView.setOnItemClickListener { _, _, position, _ ->
                showDeviceInfo(devices[position])
            }
        }
    }

    // Show device details in a dialog
    private fun showDeviceInfo(device: BluetoothDeviceInfo) {
        val message = "Name: ${device.name}\nAddress: ${device.address}"

        AlertDialog.Builder(this)
            .setTitle("Device Info")
            .setMessage(message)
            .setPositiveButton("Attack") { dialog, _ ->
                dialog.dismiss()
                scanner.stopScanning()
                val intent = Intent(this, AttackActivity::class.java).apply {
                    putExtra("DEVICE_NAME", device.name)
                    putExtra("ADDRESS", device.address)
                    putExtra("THREADS", 8)
                }
                startActivity(intent)
            }
            .setNegativeButton("Close") { dialog, _ -> dialog.dismiss() }
            .setNeutralButton("Copy Info") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = android.content.ClipData.newPlainText("Device Info", message)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Device info copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .create()
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.stopScanning()
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
    }

    override fun onPause() {
        super.onPause()
        if (bluetoothFlowStarted) {
            scanner.stopScanning()
        }
    }

    override fun onResume() {
        super.onResume()
        // If we returned from the "enable Bluetooth" prompt, try to continue.
        if (!bluetoothFlowStarted) {
            if (hasPermissions(requiredPermissions())) {
                onPermissionsGranted()
            }
        } else {
            scanner.resumeScanning()
        }
    }
}
