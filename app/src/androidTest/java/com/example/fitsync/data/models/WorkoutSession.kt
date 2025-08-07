package com.example.fitsync.data.models

import android.media.metrics.LogSessionId
import com.google.android.gms.maps.model.LatLng
import kotlin.time.Duration

data class WorkoutSession(
    val id:String,
    val distance:Float,
    val duration:Long,
    val calories:Float,
    val routePoints:List<LatLng>,
    val averagePace:Double,
    val timestamp:Long
)
