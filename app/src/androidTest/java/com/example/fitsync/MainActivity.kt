package com.example.fitsync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Looper
import android.os.StrictMode
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.fitsync.data.repository.FitnessRepo
import com.example.fitsync.databinding.ActivityMainBinding
import com.example.fitsync.ui.FitnessViewModel
import com.google.android.gms.location.*
import com.google.android.gms.maps.model.LatLng
import org.osmdroid.library.BuildConfig
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var currentLocationMarker: Marker
    private lateinit var startStopButton: Button
    private lateinit var distanceValue: TextView
    private lateinit var caloriesValue: TextView
    private lateinit var pathPolyline: Polyline
    private var firstLocationUpdate = true
    private lateinit var durationValue: TextView
    private lateinit var binding: ActivityMainBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private var isWorkoutPaused = false

    private val viewModel: FitnessViewModel by viewModels {
        FitnessViewModel.Factory(FitnessRepo(this), this)
    }

    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.all { it.value }) {
                startLocationUpdates()
            } else {
                Toast.makeText(this, "Location permissions are required", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        // Initialize views
        map = binding.map
        startStopButton = binding.actionButton
        distanceValue = binding.distanceValue
        caloriesValue = binding.caloriesValue
        durationValue = binding.durationValue

        // Initialize location client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupMap()
        setupObservers()
        setupClickListeners()
        requestLocationPermissions()
        setupButtons()
        setupWeightInput()
    }

    private fun setupButtons() {
        binding.actionButton.setOnClickListener {
            when {
                !viewModel.isTracking.value!! -> checkPermissionsAndStartTracking()
                isWorkoutPaused -> resumeTracking()
                else -> pauseTracking()
            }
        }

        // Remove or comment out the resetButton click listener since it doesn't exist in layout
        // You can add this back when you add the reset button to your layout
        /*
        binding.resetButton.setOnClickListener {
            viewModel.clearWorkout()
            binding.actionButton.text = "START"
            binding.resetButton.visibility = View.GONE
            isWorkoutPaused = false
            pathPolyline.setPoints(emptyList())
        }
        */
    }

    private fun setupClickListeners() {
        startStopButton.setOnClickListener {
            if (viewModel.isTracking.value == true) {
                viewModel.stopWorkout()
            } else {
                viewModel.startWorkout()
            }
        }
    }

    private fun setupObservers() {
        viewModel.isTracking.observe(this) { isTracking ->
            startStopButton.text = if (isTracking) "Stop" else "Start"
        }

        viewModel.duration.observe(this) { duration ->
            durationValue.text = viewModel.formatDuration(duration)
        }

        viewModel.routePoints.observe(this) { points ->
            if (points.isNotEmpty()) {
                updateRouteOnMap(points)
            }
        }

        viewModel.calories.observe(this) { calories ->
            caloriesValue.text = viewModel.formatCalories(calories)
        }

        viewModel.currentLocation.observe(this) { location ->
            if (location != null) {
                updateLocationMarker(location)
            }
        }

        viewModel.distance.observe(this) { distance ->
            distanceValue.text = viewModel.formatDistance(distance)
        }
    }

    private fun enableMyLocation() {
        if (checkLocationPermission()) {
            getCurrentLocation()
        }
    }

    private fun getCurrentLocation() {
        if (checkLocationPermission()) {
            val locationClient = LocationServices.getFusedLocationProviderClient(this)
            locationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    val latLng = LatLng(location.latitude, location.longitude)
                    Log.d("MainActivity", "Initial location: ${location.latitude}, ${location.longitude}")
                    updateLocationMarker(latLng)
                } ?: run {
                    // If last location is null, request a fresh location
                    requestFreshLocation()
                }
            }
        }
    }

    private fun requestFreshLocation() {
        if (checkLocationPermission()) {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                1000
            ).apply {
                setMaxUpdates(1) // Just get one update
            }.build()

            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    result.lastLocation?.let { location ->
                        val latLng = LatLng(location.latitude, location.longitude)
                        Log.d("MainActivity", "Fresh location: ${location.latitude}, ${location.longitude}")
                        updateLocationMarker(latLng)
                    }
                    // Remove updates after getting location
                    fusedLocationClient.removeLocationUpdates(this)
                }
            }

            fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, mainLooper)
        }
    }

    private fun setupWeightInput() {
        binding.weightInput.setOnEditorActionListener { _, _, _ ->
            val weight = binding.weightInput.text.toString().toFloatOrNull()
            if (weight != null) {
                // Note: updateWeight method needs to be implemented in ViewModel
                // viewModel.updateWeight(weight)
                Toast.makeText(this, "Weight updated: $weight kg", Toast.LENGTH_SHORT).show()
            }
            false
        }
    }

    private fun pauseTracking() {
        isWorkoutPaused = true
        binding.actionButton.text = "RESUME"
        // Remove resetButton visibility change since it doesn't exist in layout
        // binding.resetButton.visibility = View.VISIBLE
        viewModel.pauseWorkout()
    }

    private fun resumeTracking() {
        isWorkoutPaused = false
        binding.actionButton.text = "PAUSE"
        // Remove resetButton visibility change since it doesn't exist in layout
        // binding.resetButton.visibility = View.GONE
        viewModel.resumeWorkout()
    }

    private fun setupMap() {
        map.apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(18.0)
        }

        currentLocationMarker = Marker(map).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@MainActivity, org.osmdroid.library.R.drawable.osm_ic_follow_me_on)
            title = "Current Location"
        }
        map.overlays.add(currentLocationMarker)

        pathPolyline = Polyline(map).apply {
            outlinePaint.color = ContextCompat.getColor(this@MainActivity, android.R.color.holo_blue_bright)
            outlinePaint.strokeWidth = 10f
        }
        map.overlays.add(pathPolyline)
    }

    private fun requestLocationPermissions() {
        if (!checkLocationPermission()) {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            startLocationUpdates()
        }
    }

    private fun checkLocationPermission(): Boolean {
        return (ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED)
    }

    private fun checkPermissionsAndStartTracking() {
        if (checkLocationPermission()) {
            startTracking()
        } else {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun startTracking() {
        viewModel.startWorkout()
    }

    private fun stopTracking() {
        viewModel.stopWorkout()
    }

    private fun startLocationUpdates() {
        if (!checkLocationPermission()) return

        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000 // Update interval in milliseconds
        ).apply {
            setMinUpdateIntervalMillis(500)
            setMaxUpdateDelayMillis(2000)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val latLng = LatLng(location.latitude, location.longitude)
                    updateLocationMarker(latLng)

                    // Update ViewModel with new location
                    viewModel.updateLocation(locationResult)
                    Log.d("MainActivity", "Location update received: ${location.latitude}, ${location.longitude}")
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Toast.makeText(this, "Location permission not granted", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateLocationMarker(latLng: LatLng) {
        val geoPoint = GeoPoint(latLng.latitude, latLng.longitude)
        currentLocationMarker.position = geoPoint

        if (firstLocationUpdate) {
            map.controller.setCenter(geoPoint)
            map.controller.setZoom(20.0)
            map.controller.animateTo(geoPoint)
            firstLocationUpdate = false
        } else if (viewModel.isTracking.value == true) {
            map.controller.animateTo(geoPoint)
        }
        map.invalidate()
    }

    private fun updateRouteOnMap(points: List<LatLng>) {
        if (points.isEmpty()) return

        try {
            val geoPoints = points.map { GeoPoint(it.latitude, it.longitude) }
            val currentLocation = geoPoints.last()

            // Update marker and path
            updateLocationMarker(LatLng(currentLocation.latitude, currentLocation.longitude))
            pathPolyline.setPoints(geoPoints)

            // Keep map centered on current location
            if (viewModel.isTracking.value == true) {
                map.controller.animateTo(currentLocation)
            }
            map.invalidate()

            Log.d("MainActivity", "Updated location: ${currentLocation.latitude}, ${currentLocation.longitude}")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error updating map", e)
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove location updates to prevent memory leaks
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
    }
}