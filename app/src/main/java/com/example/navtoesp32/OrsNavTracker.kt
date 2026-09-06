package com.example.navtoesp32

import com.google.gson.annotations.SerializedName
import kotlin.math.*

/**
 * Provider-agnostic navigation model. Previously shared this file with ORS's
 * OrsApi/RouteRepository — those are removed now that Mappls has fully
 * replaced ORS (see MapplsNavTracker.kt for the routing/geocoding layer).
 */

data class LatLon(val lat: Double, val lon: Double)

data class Step(
    val instruction: String,
    val roadName: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val maneuverType: Int,
    val polyline: List<LatLon>
)

data class NavPayload(
    @SerializedName("turn") val turn: String,
    @SerializedName("road") val roadName: String,
    @SerializedName("dist") val distanceToTurnM: Int,
    @SerializedName("eta") val etaMinutes: Int,
    @SerializedName("off") val offRoute: Boolean
)

class RouteTracker(private val steps: List<Step>) {

    private var currentStepIndex = 0
    private val offRouteThresholdMeters = 35.0

    fun onLocationUpdate(current: LatLon): NavPayload? {
        if (currentStepIndex >= steps.size) return null

        advanceStepIfNeeded(current)

        val step = steps[currentStepIndex]
        val distToStepEnd = distanceToPoint(current, step.polyline.last())
        val distFromRoute = minDistanceToPolyline(current, step.polyline)
        val isOffRoute = distFromRoute > offRouteThresholdMeters

        val etaSeconds = remainingDuration(current)
        val etaMinutes = ceil(etaSeconds / 60.0).toInt()

        return NavPayload(
            turn = maneuverToCode(step.maneuverType),
            roadName = step.roadName,
            distanceToTurnM = distToStepEnd.roundToInt(),
            etaMinutes = etaMinutes,
            offRoute = isOffRoute
        )
    }

    private fun advanceStepIfNeeded(current: LatLon) {
        val lookahead = 3
        var bestIndex = currentStepIndex
        var bestDist = minDistanceToPolyline(current, steps[currentStepIndex].polyline)

        for (i in (currentStepIndex + 1) until minOf(currentStepIndex + lookahead, steps.size)) {
            val dist = minDistanceToPolyline(current, steps[i].polyline)
            if (dist < bestDist) {
                bestDist = dist
                bestIndex = i
            }
        }

        currentStepIndex = bestIndex
    }

    private fun remainingDuration(current: LatLon): Double {
        val step = steps[currentStepIndex]
        val stepFractionDone = 1.0 - (distanceToPoint(current, step.polyline.last()) / max(step.distanceMeters, 1.0))
        val currentStepRemaining = step.durationSeconds * (1.0 - stepFractionDone.coerceIn(0.0, 1.0))
        val futureStepsDuration = steps.drop(currentStepIndex + 1).sumOf { it.durationSeconds }
        return currentStepRemaining + futureStepsDuration
    }

    private fun maneuverToCode(type: Int): String = when (type) {
        0 -> "L"
        1 -> "R"
        2 -> "SHARP_L"
        3 -> "SHARP_R"
        4 -> "SLIGHT_L"
        5 -> "SLIGHT_R"
        6 -> "STRAIGHT"
        7 -> "ROUNDABOUT"
        10 -> "ARRIVE"
        11 -> "DEPART"
        else -> "STRAIGHT"
    }

    private fun distanceToPoint(a: LatLon, b: LatLon): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * R * asin(sqrt(h))
    }

    private fun minDistanceToPolyline(p: LatLon, line: List<LatLon>): Double {
        if (line.size < 2) return line.firstOrNull()?.let { distanceToPoint(p, it) } ?: Double.MAX_VALUE
        var minDist = Double.MAX_VALUE
        for (i in 0 until line.size - 1) {
            val d = distanceToSegment(p, line[i], line[i + 1])
            if (d < minDist) minDist = d
        }
        return minDist
    }

    private fun distanceToSegment(p: LatLon, a: LatLon, b: LatLon): Double {
        val latRef = Math.toRadians(a.lat)
        fun toXY(pt: LatLon): Pair<Double, Double> {
            val x = Math.toRadians(pt.lon) * cos(latRef) * 6371000.0
            val y = Math.toRadians(pt.lat) * 6371000.0
            return x to y
        }
        val (px, py) = toXY(p)
        val (ax, ay) = toXY(a)
        val (bx, by) = toXY(b)
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0.0, 1.0)
        val projX = ax + t * dx
        val projY = ay + t * dy
        return sqrt((px - projX).pow(2) + (py - projY).pow(2))
    }
}