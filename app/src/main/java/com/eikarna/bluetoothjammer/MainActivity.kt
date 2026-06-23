package com.eikarna.bluetoothjammer

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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

class MainActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyView: MaterialTextView
    private lateinit var deviceListAdapter: ArrayAdapter<String>

    private val deviceNames = mutableListOf<String>()
    private var devices: List<BluetoothDeviceInfo> = emptyList()
    private val scanner = ScanNearbyDevices.getInstance()

    // Guards lifecycle callbacks so nothing touches Bluetooth before the
    // permissions are granted - that was the original launch crash.
    private var bluetoothFlowStarted = false

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

        // A single adapter, updated in place (no per-second recreation).
        deviceListAdapter = ArrayAdapter(this, R.layout.item_device, R.id.deviceText, deviceNames)
        listView.adapter = deviceListAdapter
        listView.setOnItemClickListener { _, _, position, _ ->
            devices.getOrNull(position)?.let { showDeviceInfo(it) }
        }

        // Nothing that touches Bluetooth runs until permissions are granted.
        checkPermissionsAndStart()
    }

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
            // Android 12+ runtime Bluetooth permissions.
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
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
            // Ask the user to turn Bluetooth on, then continue from onResume.
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 1)
            return
        }

        bluetoothFlowStarted = true
        startScanningForDevices()
    }

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
        scanner.startScanning(this) { discovered ->
            devices = discovered
            deviceNames.clear()
            deviceNames.addAll(discovered.map { "${it.name}\n${it.address}" })
            deviceListAdapter.notifyDataSetChanged()
        }
    }

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
    }

    override fun onPause() {
        super.onPause()
        if (bluetoothFlowStarted) {
            scanner.stopScanning()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!bluetoothFlowStarted) {
            // Returning from the "enable Bluetooth" prompt - try to continue.
            if (hasPermissions(requiredPermissions())) {
                onPermissionsGranted()
            }
        } else {
            scanner.resumeScanning()
        }
    }
}
