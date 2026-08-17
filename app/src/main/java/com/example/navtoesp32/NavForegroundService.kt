package com.example.navtoesp32

import android.app.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NavForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "nav_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_ORIGIN_LAT = "origin_lat"
        const val EXTRA_ORIGIN_LON = "origin_lon"
        const val EXTRA_DEST_TEXT = "dest_text"
        const val EXTRA_API_KEY = "api_key"

        // Name your ESP32 advertises via SerialBT.begin("ESP32_Nav") — must match exactly.
        const val ESP32_DEVICE_NAME = "ESP32_Nav"

        var onPayload: ((NavPayload) -> Unit)? = null
        var onStatus: ((String) -> Unit)? = null
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var locationCallback: LocationCallback? = null
    private lateinit var tracker: RouteTracker
    private lateinit var destination: LatLon
    private lateinit var repo: RouteRepository
    private var consecutiveOffRouteCount = 0
    private val offRouteRerouteThreshold = 3

    private val btSender = BtSppSender()
    private var btConnected = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        val originLat = intent?.getDoubleExtra(EXTRA_ORIGIN_LAT, 0.0) ?: return START_NOT_STICKY
        val originLon = intent.getDoubleExtra(EXTRA_ORIGIN_LON, 0.0)
        val destText = intent.getStringExtra(EXTRA_DEST_TEXT) ?: return START_NOT_STICKY
        val apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: return START_NOT_STICKY

        startForeground(NOTIFICATION_ID, buildNotification("Starting navigation..."))

        repo = RouteRepository(buildOrsApi(), apiKey)

        serviceScope.launch {
            try {
                onStatus?.invoke("Looking up \"$destText\"...")
                val dest = repo.geocodeAddress(destText)
                destination = dest

                onStatus?.invoke("Fetching route...")
                val steps = repo.fetchRoute(LatLon(originLat, originLon), dest)
                Log.d("NAV", "route fetched: ${steps.size} steps")

                tracker = RouteTracker(steps)
                onStatus?.invoke("Connecting to ESP32...")
                connectToEsp32()

                onStatus?.invoke("Navigating to $destText")
                startLocationUpdates()

            } catch (e: retrofit2.HttpException) {
                val errorBody = e.response()?.errorBody()?.string()
                Log.e("NAV", "HTTP ${e.code()}: $errorBody")
                onStatus?.invoke("Route lookup failed (HTTP ${e.code()})")
                stopSelf()
            } catch (e: Exception) {
                Log.e("NAV", "Failed to start navigation: ${e.message}", e)
                onStatus?.invoke("Error: ${e.message}")
                stopSelf()
            }
        }

        return START_STICKY
    }

    /**
     * Looks up the paired ESP32 by name and connects over classic BT SPP.
     * Non-fatal if it fails — navigation still works and updates the phone
     * UI/notification, it just won't reach the ESP32 until reconnected.
     */
    private suspend fun connectToEsp32() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w("NAV", "Missing BLUETOOTH_CONNECT permission, skipping ESP32 connection")
            onStatus?.invoke("Bluetooth permission missing — display won't update")
            return
        }

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            Log.w("NAV", "Bluetooth not available/enabled")
            onStatus?.invoke("Bluetooth is off — enable it to use the display")
            return
        }

        val device: BluetoothDevice? = try {
            adapter.bondedDevices.firstOrNull { it.name == ESP32_DEVICE_NAME }
        } catch (e: SecurityException) {
            Log.e("NAV", "SecurityException reading bonded devices: ${e.message}", e)
            null
        }

        if (device == null) {
            Log.w("NAV", "Paired device '$ESP32_DEVICE_NAME' not found. Pair it in phone Bluetooth settings first.")
            onStatus?.invoke("ESP32 not paired — pair \"$ESP32_DEVICE_NAME\" in Bluetooth settings")
            return
        }

        btConnected = btSender.connect(device)
        if (!btConnected) {
            Log.w("NAV", "Failed to connect to ESP32")
            onStatus?.invoke("Couldn't connect to ESP32 — check it's powered on and nearby")
        } else {
            Log.d("NAV", "ESP32 connected")
        }
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w("NAV", "Location permission not granted")
            stopSelf()
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                val currentLatLon = LatLon(loc.latitude, loc.longitude)
                val payload = tracker.onLocationUpdate(currentLatLon) ?: return

                Log.d("NAV", payload.toString())
                onPayload?.invoke(payload)
                updateNotification("${payload.turn} onto ${payload.roadName} — ${payload.distanceToTurnM}m")

                if (btConnected) {
                    serviceScope.launch { btSender.send(payload) }
                }

                if (payload.offRoute) {
                    consecutiveOffRouteCount++
                    if (consecutiveOffRouteCount >= offRouteRerouteThreshold) {
                        consecutiveOffRouteCount = 0
                        triggerReroute(currentLatLon)
                    }
                } else {
                    consecutiveOffRouteCount = 0
                }
            }
        }
        locationCallback = callback
        fusedLocationClient.requestLocationUpdates(request, callback, mainLooper)
    }

    private fun triggerReroute(currentLocation: LatLon) {
        onStatus?.invoke("Off route — recalculating...")
        serviceScope.launch {
            try {
                val newSteps = repo.fetchRoute(currentLocation, destination)
                tracker = RouteTracker(newSteps)
                onStatus?.invoke("Rerouted")
                Log.d("NAV", "Rerouted: ${newSteps.size} new steps")
            } catch (e: Exception) {
                Log.e("NAV", "Reroute failed: ${e.message}", e)
                onStatus?.invoke("Reroute failed, keeping current route")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Navigation", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, NavForegroundService::class.java).apply { action = "STOP" }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Navigating")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .addAction(0, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        locationCallback?.let { fusedLocationClient.removeLocationUpdates(it) }
        btSender.disconnect()
        btConnected = false
        onStatus?.invoke("Navigation stopped")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}