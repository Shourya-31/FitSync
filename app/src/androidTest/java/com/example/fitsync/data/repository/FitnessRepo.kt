package com.example.fitsync.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.LatLng

class FitnessRepo(private val context: Context) {
    private val locationClient = LocationServices.getFusedLocationProviderClient(context)

    // LocationServices.getFusedLocationProviderClient(context) uses efficient google tracking.
    // We have implemented a filter to ensure accuracy up to 20 meters
    private var lastValidLocation: Location? = null
    private var isActuallyMoving = false
    private var totalDuration: Long = 0
    private var trackingStartTime: Long = 0
    private var pausedTime: Long = 0
    private var pauseStartTime: Long = 0
    private var isTrackingPaused = false
    private val MOVEMENT_THRESHOLD = 1.0f

    private val _routePoints = MutableLiveData<List<LatLng>>(emptyList())
    val routePoints: LiveData<List<LatLng>> = _routePoints

    private val _totalDistance = MutableLiveData<Float>(0f)
    val totalDistance: LiveData<Float> = _totalDistance

    val _isTracking = MutableLiveData<Boolean>(false)
    val isTracking: LiveData<Boolean> = _isTracking

    private val _currentLocation = MutableLiveData<LatLng?>(null)
    val currentLocation: LiveData<LatLng?> = _currentLocation // we are using this just to display the current location. Var without _ is for displaying only.

    // Timing variables
    private var startTime: Long = 0
    private var actionMovementTime: Long = 0
    private var lastMovementTime: Long = 0
    private var lastLocationUpdateTime: Long = 0

    private val locationCallback = object : LocationCallback() {
        // This is the Kotlin way of creating anonymous object
        // => the parent class LocationCallback is being overridden here
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { newLocation ->
                // This whole function works for location update of the user whenever the user moves
                // this function is called to calculate user's calories and other attributes based on its movement
                Log.d(
                    "FitnessRepo",
                    "New Location Received: ${newLocation.latitude}, ${newLocation.longitude}"
                )
                val currentTime = System.currentTimeMillis()
                _currentLocation.postValue(
                    LatLng(
                        newLocation.latitude,
                        newLocation.longitude
                    )
                ) // We are assigning this location to new location

                // Don't process location updates if tracking is paused
                if (isTrackingPaused || _isTracking.value != true) {
                    return
                }

                if (newLocation.accuracy > 20f) {
                    Log.d("FitnessRepo", "Location is not accurate enough")
                    isActuallyMoving = false
                    return
                }

                lastValidLocation?.let { lastLocation ->
                    val distance: Float = newLocation.distanceTo(lastLocation)
                    val timeGap: Long = currentTime - lastLocationUpdateTime

                    val speed: Float = if (timeGap > 0) (distance * 1000) / timeGap else 0f

                    isActuallyMoving = distance > MOVEMENT_THRESHOLD &&
                            speed < 8f &&
                            speed > 0.3f

                    // More strict movement validation
                    if (isActuallyMoving) {
                        updateMovementTime(currentTime)
                        updateLocationData(newLocation) // if the user is actually moving then first check it and then update the location and time data
                        Log.d(
                            "FitnessRepo",
                            "User is actually moving: $distance meters, Speed: $speed m/s"
                        )
                    }
                } ?: run {
                    lastValidLocation = newLocation // => We wont consider the start point as movement point, after that whatever we get will be our movement points
                    isActuallyMoving = false
                }

                lastLocationUpdateTime = currentTime
            }
        }
    }

    private fun updateMovementTime(currentTime: Long) {
        if (lastMovementTime > 0) {
            actionMovementTime = actionMovementTime + currentTime - lastMovementTime
        }
        lastMovementTime = currentTime
    }

    fun getCurrentDuration(): Long {
        return if (_isTracking.value == true) {
            if (isTrackingPaused) {
                totalDuration + pausedTime
            } else {
                totalDuration + (System.currentTimeMillis() - trackingStartTime) - pausedTime
            }
        } else {
            totalDuration
        }
    }

    private fun updateLocationData(newLocation: Location) {
        if (!isActuallyMoving || isTrackingPaused) {
            Log.d("FitnessRepo", "User is not actually moving or tracking is paused")
            return
        }

        val latLng = LatLng(newLocation.latitude, newLocation.longitude)
        val currentPoints: MutableList<LatLng> =
            _routePoints.value?.toMutableList() ?: mutableListOf()

        if (currentPoints.isEmpty()) {
            currentPoints.add(latLng) // adding current location to the latLng where its converting to latlng
            _routePoints.postValue(currentPoints)
            Log.d("FitnessRepo", "First location added to route points")
            lastValidLocation = newLocation
            return
        }

        val distanceFromLastLocation = calculateDistance(latLng, currentPoints.last())
        if (distanceFromLastLocation > MOVEMENT_THRESHOLD / 1000f) {
            currentPoints.add(latLng)
            _routePoints.postValue(currentPoints)

            val totalDistanceValue: Float = _totalDistance.value ?: 0f
            _totalDistance.postValue(totalDistanceValue + distanceFromLastLocation)
            Log.d("FitnessRepo", "New location added to route points. Distance: $distanceFromLastLocation km")
        }

        lastValidLocation = newLocation
    }

    private fun initializeTracking() {
        startTime = System.currentTimeMillis()
        trackingStartTime = System.currentTimeMillis()
        totalDuration = 0
        pausedTime = 0
        isTrackingPaused = false
        _isTracking.postValue(true)
    }

    private fun requestLocationUpdate() {
        val locationRequest = LocationRequest.create().apply {
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
            interval = 1000
            fastestInterval = 500
            smallestDisplacement = 1f
        }
        if (checkLocationPermission()) {
            try {
                locationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    context.mainLooper
                )
            } catch (e: SecurityException) {
                Toast.makeText(context, "Location permission not granted", Toast.LENGTH_SHORT)
                    .show()
                Log.e("FitnessRepo", "Location permission error: ${e.message}")
            }
        } else {
            Log.w("FitnessRepo", "Location permission not granted")
        }
    }

    private fun checkLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    private fun calculateDistance(point1: LatLng, point2: LatLng): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            point1.latitude,
            point1.longitude,
            point2.latitude,
            point2.longitude,
            results
        )
        return results[0] / 1000f // Convert to kilometers
    }

    fun calculateCalories(weight: Float, distance: Float, duration: Long): Float {
        if (duration < 1000) {
            return 0f
        }
        val hours = duration / 3600000.0 // Convert milliseconds to hours
        if (hours <= 0) {
            return 0f
        }

        val speed: Double = if (hours > 0) distance.toDouble() / hours else 0.0
        val met: Float = when {
            speed <= 4.0 -> 2.0f
            speed <= 8.0 -> 7.0f
            speed <= 11.0 -> 8.5f
            else -> 10.0f
        }

        val calories = (met * weight.toDouble() * hours).toFloat()
        return calories
    }

    fun calculatePace(distance: Float, duration: Long): Double {
        if (duration < 1000 || distance <= 0) {
            return 0.0
        }
        // Convert duration from milliseconds to hours
        val hours = duration / 3600000.0
        // Calculate speed in kilometers per hour
        return distance / hours
    }

    fun startTracking() {
        if (checkLocationPermission()) {
            initializeTracking()
            requestLocationUpdate()
            Log.d("FitnessRepo", "Tracking started")
        } else {
            Log.w("FitnessRepo", "Cannot start tracking - location permission not granted")
        }
    }

    fun stopTracking() {
        try {
            locationClient.removeLocationUpdates(locationCallback)
            if (_isTracking.value == true && !isTrackingPaused) {
                totalDuration += (System.currentTimeMillis() - trackingStartTime) - pausedTime
            }
            _isTracking.postValue(false)
            isTrackingPaused = false
            Log.d("FitnessRepo", "Tracking stopped")
        } catch (e: Exception) {
            Log.e("FitnessRepo", "Error stopping tracking: ${e.message}")
        }
    }

    fun pauseTracking() {
        if (_isTracking.value == true && !isTrackingPaused) {
            pauseStartTime = System.currentTimeMillis()
            isTrackingPaused = true
            Log.d("FitnessRepo", "Tracking paused")
        }
    }

    fun resumeTracking() {
        if (_isTracking.value == true && isTrackingPaused) {
            pausedTime += System.currentTimeMillis() - pauseStartTime
            isTrackingPaused = false
            Log.d("FitnessRepo", "Tracking resumed")
        }
    }

    private fun resetTracking() {
        lastMovementTime = 0
        actionMovementTime = 0
        trackingStartTime = 0
        totalDuration = 0
        pausedTime = 0
        pauseStartTime = 0
        isTrackingPaused = false
    }

    fun clearTracking() {
        try {
            locationClient.removeLocationUpdates(locationCallback)
            _routePoints.postValue(emptyList())
            _totalDistance.postValue(0f)
            _isTracking.postValue(false)
            _currentLocation.postValue(null)
            startTime = 0
            resetTracking()
            lastValidLocation = null
            isActuallyMoving = false
            lastLocationUpdateTime = 0
            Log.d("FitnessRepo", "Tracking cleared")
        } catch (e: Exception) {
            Log.e("FitnessRepo", "Error clearing tracking: ${e.message}")
        }
    }
}