package com.example.fitsync.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.*
import com.example.fitsync.data.models.WorkoutSession
import com.example.fitsync.data.repository.FitnessRepo
import com.google.android.gms.location.LocationResult
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.*
import java.util.UUID

// User data class definition
data class User(
    val id: String,
    val weight: Float,
    val height: Float,
    val age: Int
)

class FitnessViewModel(
    private val repository: FitnessRepo,
    private val context: Context
) : ViewModel() {

    private val _userWeight = MutableLiveData<Float>(70f)
    val userWeight: LiveData<Float> get() = _userWeight

    private val _isTracking = MutableLiveData(false)
    val isTracking: LiveData<Boolean> get() = _isTracking

    private val _duration = MutableLiveData(0L)
    val duration: LiveData<Long> get() = _duration

    private val _distance = MutableLiveData(0f)
    val distance: LiveData<Float> get() = _distance

    private val _averagePace = MutableLiveData(0.0)
    val averagePace: LiveData<Double> get() = _averagePace

    private val _calories = MutableLiveData(0f)
    val calories: LiveData<Float> get() = _calories

    private val _routePoints = MutableLiveData<List<LatLng>>(emptyList())
    val routePoints: LiveData<List<LatLng>> get() = _routePoints

    private val _currentLocation = MutableLiveData<LatLng>()
    val currentLocation: LiveData<LatLng> get() = _currentLocation

    private var trackingJob: Job? = null
    private var durationUpdateJob: Job? = null

    private val _currentUser = MutableLiveData<User>()
    val currentUser: LiveData<User> = _currentUser

    private var lastUpdateTime: Long = 0

    init {
        repository.totalDistance.observeForever { newDistance ->
            _distance.value = newDistance
            updateCalories()
            updatePace()
        }

        repository._isTracking.observeForever { isTracking ->
            _isTracking.value = isTracking
            if (!isTracking) {
                stopDurationUpdate()
            } else {
                startDurationUpdate()
            }
        }

        loadUserData()
    }

    private fun loadUserData() {
        viewModelScope.launch {
            try {
                // Get SharedPreferences instance
                val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

                // Load user data with default values if not found
                val user = User(
                    id = sharedPreferences.getString("user_id", UUID.randomUUID().toString()) ?: "",
                    weight = sharedPreferences.getFloat("user_weight", 70f),  // default 70kg
                    height = sharedPreferences.getFloat("user_height", 170f), // default 170cm
                    age = sharedPreferences.getInt("user_age", 25)           // default 25 years
                )

                _currentUser.value = user
                _userWeight.value = user.weight

                // Save user_id if it was generated
                if (!sharedPreferences.contains("user_id")) {
                    sharedPreferences.edit().putString("user_id", user.id).apply()
                }
            } catch (e: Exception) {
                Log.e("FitnessViewModel", "Error loading user data: ${e.message}")
            }
        }
    }

    fun updateWeight(newWeight: Float) {
        _userWeight.value = newWeight
        updateCalories()

        // Save to SharedPreferences
        try {
            val sharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            sharedPreferences.edit().putFloat("user_weight", newWeight).apply()
        } catch (e: Exception) {
            Log.e("FitnessViewModel", "Error saving weight: ${e.message}")
        }
    }

    private fun startDurationUpdate() {
        durationUpdateJob?.cancel()
        durationUpdateJob = viewModelScope.launch {
            while (isActive && _isTracking.value == true) {
                _duration.value = repository.getCurrentDuration()
                updateCalories()
                delay(1000)
            }
        }
    }

    private fun stopDurationUpdate() {
        durationUpdateJob?.cancel()
        durationUpdateJob = null
        _duration.value = repository.getCurrentDuration() // get final duration
    }

    fun startWorkout() {
        viewModelScope.launch {
            _duration.value = 0
            _calories.value = 0f
            _distance.value = 0f
            _routePoints.value = emptyList()
            _isTracking.value = true
            repository.startTracking()
            startDurationUpdate()
        }
    }

    private fun updateCalories() {
        val weight: Float = _userWeight.value ?: 70f
        val distance: Float = _distance.value ?: 0f
        val duration: Long = _duration.value ?: 0L
        val newCalories: Float = repository.calculateCalories(
            weight = weight,
            distance = distance,
            duration = duration
        )
        _calories.value = newCalories
    }

    private fun updatePace() {
        val distance: Float = _distance.value ?: 0f
        val duration: Long = _duration.value ?: 0L
        if (distance > 0) {
            _averagePace.value = repository.calculatePace(distance, duration)
        }
    }

    fun stopWorkout() {
        _isTracking.value = false
        stopDurationUpdate()
        trackingJob?.cancel()
        trackingJob = null
        repository.stopTracking()
        saveWorkout()
    }

    fun pauseWorkout() {
        _isTracking.value = false
        stopDurationUpdate()
        trackingJob?.cancel()
        trackingJob = null
        repository.pauseTracking()
    }

    fun resumeWorkout() {
        if (trackingJob == null) {
            _isTracking.value = true
            startDurationUpdate()
            repository.resumeTracking()
            trackingJob = viewModelScope.launch {
                while (isActive && _isTracking.value == true) {
                    delay(1000)
                    // Duration update is handled by startDurationUpdate()
                }
            }
        }
    }

    fun clearWorkout() {
        _duration.value = 0L
        _distance.value = 0f
        _calories.value = 0f
        _averagePace.value = 0.0
        _routePoints.value = emptyList()
        repository.clearTracking()
    }

    fun updateLocation(locationResult: LocationResult) {
        if (_isTracking.value != true) return

        val locations = locationResult.locations
        if (locations.isNotEmpty()) {
            val location = locations.last()
            val latLng = LatLng(location.latitude, location.longitude)

            val updatedPoints = _routePoints.value.orEmpty().toMutableList()
            if (updatedPoints.isNotEmpty()) {
                val last = updatedPoints.last()
                val result = FloatArray(1)
                android.location.Location.distanceBetween(
                    last.latitude, last.longitude,
                    latLng.latitude, latLng.longitude,
                    result
                )
                _distance.value = (_distance.value ?: 0f) + result[0]
                updateCalories()
                updatePace()
            }

            updatedPoints.add(latLng)
            _routePoints.value = updatedPoints
            _currentLocation.value = latLng
        }
    }

    fun calculateCalories() {
        val weight = _userWeight.value ?: 70f
        val durationInMinutes = (_duration.value ?: 0L) / 60000f
        _calories.value = weight * 0.0175f * 8.0f * durationInMinutes
    }

    private fun saveWorkout() {
        val session = WorkoutSession(
            id = UUID.randomUUID().toString(),
            duration = _duration.value ?: 0L,
            distance = _distance.value ?: 0f,
            calories = _calories.value ?: 0f,
            routePoints = _routePoints.value.orEmpty(),
            averagePace = calculatePace(),
            timestamp = System.currentTimeMillis()
        )

        viewModelScope.launch {
            try {
                // Save to repository - you'll need to implement this method in FitnessRepo
                // repository.saveWorkoutSession(session)
                Log.d("FitnessViewModel", "Workout saved: ${session.id}")
            } catch (e: Exception) {
                Log.e("FitnessViewModel", "Failed to save workout: ${e.message}")
            }
        }
    }

    fun calculatePace(): Double {
        val durationInHours = (_duration.value ?: 0L) / 3600000.0
        val distanceInKm = (_distance.value ?: 0f) / 1000.0
        return if (durationInHours > 0) distanceInKm / durationInHours else 0.0
    }

    fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun formatDistance(meters: Float): String = String.format("%.2f km", meters / 1000)

    fun formatCalories(cal: Float): String = String.format("%.0f kcal", cal)

    fun formatPace(pace: Double): String = String.format("%.2f km/h", pace)

    override fun onCleared() {
        super.onCleared()
        trackingJob?.cancel()
        durationUpdateJob?.cancel()
    }

    class Factory(
        private val fitnessRepo: FitnessRepo,
        private val context: Context
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(FitnessViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return FitnessViewModel(fitnessRepo, context) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}