package com.example.navtoesp32

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Handles a classic Bluetooth SPP connection to the ESP32 and sends
 * NavPayload objects as newline-terminated JSON.
 *
 * Usage:
 *   val sender = BtSppSender()
 *   sender.connect(device) // device = paired ESP32 BluetoothDevice
 *   sender.send(payload)   // call this from onPayload — safe to call repeatedly
 *   sender.disconnect()    // call when navigation stops / service is destroyed
 *
 * Requires BLUETOOTH_CONNECT permission on Android 12+ (API 31+) — check/request
 * this the same way ACCESS_FINE_LOCATION is handled elsewhere in the app.
 */
class BtSppSender {

    companion object {
        // Standard SPP UUID — works for BluetoothSerial on the ESP32 side out of the box.
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TAG = "NAV_BT"
    }

    private var socket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private val gson = Gson()

    /**
     * Connects to the given paired device. Must be called from a coroutine
     * (it blocks on socket.connect()), off the main thread.
     */
    suspend fun connect(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        try {
            val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
            sock.connect()
            socket = sock
            outputStream = sock.outputStream
            Log.d(TAG, "Connected to ${device.name}")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Connection failed: ${e.message}", e)
            closeQuietly()
            false
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing BLUETOOTH_CONNECT permission: ${e.message}", e)
            false
        }
    }

    /**
     * Serializes payload to JSON and writes it + a newline.
     * Safe to call from the main thread's location callback — internally
     * hops to IO dispatcher. Fire-and-forget; failures are logged, not thrown.
     */
    suspend fun send(payload: NavPayload): Boolean = withContext(Dispatchers.IO) {
        val stream = outputStream ?: return@withContext false
        try {
            val json = gson.toJson(payload)
            stream.write((json + "\n").toByteArray(Charsets.UTF_8))
            stream.flush()
            true
        } catch (e: IOException) {
            Log.e(TAG, "Send failed, connection likely dropped: ${e.message}", e)
            closeQuietly()
            false
        }
    }

    fun disconnect() {
        closeQuietly()
    }

    private fun closeQuietly() {
        try {
            outputStream?.close()
        } catch (_: IOException) { /* ignore */ }
        try {
            socket?.close()
        } catch (_: IOException) { /* ignore */ }
        outputStream = null
        socket = null
    }
}
