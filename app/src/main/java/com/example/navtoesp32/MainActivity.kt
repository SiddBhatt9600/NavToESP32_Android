package com.example.navtoesp32

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private lateinit var statusText: TextView
    private lateinit var destinationInput: EditText
    private lateinit var suggestionsList: ListView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private lateinit var searchRepo: MapplsRouteRepository

    private val debounceHandler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null
    private val debounceDelayMs = 350L

    private var currentSuggestions: List<MapplsSuggestion> = emptyList()
    private var selectedEloc: String? = null // cleared whenever the user edits the text again

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        statusText = findViewById(R.id.statusText)
        destinationInput = findViewById(R.id.destinationInput)
        suggestionsList = findViewById(R.id.suggestionsList)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        requestPermissionsIfNeeded()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        searchRepo = MapplsRouteRepository(buildMapplsRouteApi(), buildMapplsSearchApi(), BuildConfig.MAPPLS_API_KEY)

        NavForegroundService.onPayload = { payload ->
            runOnUiThread {
                statusText.text =
                    "${payload.turn} onto ${payload.roadName} — ${payload.distanceToTurnM}m, ETA ${payload.etaMinutes} min"
            }
        }
        NavForegroundService.onStatus = { status ->
            runOnUiThread { statusText.text = status }
        }

        setupDestinationAutosuggest()

        startButton.setOnClickListener {
            val destinationText = destinationInput.text.toString().trim()
            if (destinationText.isEmpty()) {
                statusText.text = "Please enter a destination"
                return@setOnClickListener
            }
            beginNavigation(destinationText, selectedEloc)
        }

        stopButton.setOnClickListener {
            val intent = Intent(this, NavForegroundService::class.java).apply { action = "STOP" }
            startService(intent)
            stopButton.isEnabled = false
            startButton.isEnabled = true
            destinationInput.isEnabled = true
        }
    }

    private fun setupDestinationAutosuggest() {
        destinationInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                // Any manual edit invalidates a previously picked suggestion —
                // falls back to plain geocoding unless they pick a new suggestion.
                selectedEloc = null

                debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
                val query = s?.toString()?.trim().orEmpty()

                if (query.length < 2) {
                    suggestionsList.visibility = View.GONE
                    return
                }

                val runnable = Runnable { fetchSuggestions(query) }
                debounceRunnable = runnable
                debounceHandler.postDelayed(runnable, debounceDelayMs)
            }
        })

        suggestionsList.setOnItemClickListener { _, _, position, _ ->
            val picked = currentSuggestions.getOrNull(position) ?: return@setOnItemClickListener
            selectedEloc = picked.eLoc
            destinationInput.setText(picked.placeName)
            destinationInput.setSelection(destinationInput.text.length)
            suggestionsList.visibility = View.GONE
        }
    }

    private fun fetchSuggestions(query: String) {
        lifecycleScope.launch {
            try {
                // Best-effort location bias — if it's not available yet, autosuggest
                // still works, just without proximity ranking.
                val bias = try {
                    if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        val loc = fusedLocationClient.lastLocation.await()
                        loc?.let { LatLon(it.latitude, it.longitude) }
                    } else null
                } catch (e: Exception) {
                    null
                }

                val results = searchRepo.autosuggest(query, bias)
                currentSuggestions = results

                if (results.isEmpty()) {
                    suggestionsList.visibility = View.GONE
                } else {
                    val labels = results.map { "${it.placeName}\n${it.placeAddress}" }
                    suggestionsList.adapter = ArrayAdapter(
                        this@MainActivity,
                        R.layout.item_suggestion,
                        labels
                    )
                    suggestionsList.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                // Autosuggest failing is non-fatal — just hide the dropdown and
                // let the user keep typing; Start still falls back to geocoding.
                suggestionsList.visibility = View.GONE
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 100)
        }
    }

    private fun beginNavigation(destinationText: String, preResolvedEloc: String?) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            statusText.text = "Location permission not granted"
            requestPermissionsIfNeeded()
            return
        }

        suggestionsList.visibility = View.GONE
        startButton.isEnabled = false
        destinationInput.isEnabled = false
        statusText.text = "Getting current location..."

        fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
            if (loc == null) {
                statusText.text = "Couldn't get current location — move outdoors or wait a moment and retry"
                startButton.isEnabled = true
                destinationInput.isEnabled = true
                return@addOnSuccessListener
            }

            val intent = Intent(this, NavForegroundService::class.java).apply {
                putExtra(NavForegroundService.EXTRA_ORIGIN_LAT, loc.latitude)
                putExtra(NavForegroundService.EXTRA_ORIGIN_LON, loc.longitude)
                putExtra(NavForegroundService.EXTRA_DEST_TEXT, destinationText)
                preResolvedEloc?.let { putExtra(NavForegroundService.EXTRA_DEST_ELOC, it) }
                putExtra(NavForegroundService.EXTRA_API_KEY, BuildConfig.MAPPLS_API_KEY)
            }
            startForegroundService(intent)
            stopButton.isEnabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        debounceRunnable?.let { debounceHandler.removeCallbacks(it) }
        NavForegroundService.onPayload = null
        NavForegroundService.onStatus = null
    }
}