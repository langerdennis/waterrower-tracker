package com.waterrower.tracker.model

import com.waterrower.tracker.data.WorkoutEntity
import com.waterrower.tracker.data.WorkoutSampleEntity
import kotlin.math.roundToInt

data class GhostPoint(
    val offsetSeconds: Int,
    val distanceMeters: Int,
)

data class GhostReference(
    val workoutId: String,
    val label: String,
    val totalDistanceMeters: Int,
    val durationSeconds: Int,
    val avgPaceSecPer500m: Int,
    val points: List<GhostPoint>,
)

data class GhostDelta(
    val deltaMeters: Int,
    val ghostDistanceMeters: Int,
    val ghostFinished: Boolean,
) {
    val formattedCompact: String
        get() = when {
            deltaMeters > 0 -> "+$deltaMeters m"
            deltaMeters < 0 -> "$deltaMeters m"
            else -> "±0 m"
        }
}

/**
 * Pure-Kotlin live ghost racer that compares current rowing progress against a previously
 * recorded workout using linear interpolation over its 1Hz samples.
 */
object GhostRacer {

    fun fromWorkout(
        workout: WorkoutEntity,
        samples: List<WorkoutSampleEntity>,
        label: String,
    ): GhostReference {
        val sorted = samples
            .sortedBy { it.offsetSeconds }
            .map { GhostPoint(it.offsetSeconds.coerceAtLeast(0), it.distanceMeters.coerceAtLeast(0)) }

        val points = buildList {
            if (sorted.isEmpty() || sorted.first().offsetSeconds > 0) {
                add(GhostPoint(0, 0))
            }
            addAll(sorted)
            if (sorted.isEmpty() && workout.durationSeconds > 0 && workout.totalDistanceMeters > 0) {
                add(GhostPoint(workout.durationSeconds, workout.totalDistanceMeters))
            }
        }

        return GhostReference(
            workoutId = workout.id,
            label = label,
            totalDistanceMeters = workout.totalDistanceMeters,
            durationSeconds = workout.durationSeconds,
            avgPaceSecPer500m = workout.avgPaceSecPer500m,
            points = points,
        )
    }

    /**
     * Evaluates the live lead/deficit in meters at [elapsedSeconds] relative to [reference].
     */
    fun evaluate(
        reference: GhostReference,
        elapsedSeconds: Int,
        currentDistanceMeters: Int,
    ): GhostDelta? {
        val pts = reference.points
        if (pts.size < 2) return null

        val safeElapsed = elapsedSeconds.coerceAtLeast(0)
        val safeDist = currentDistanceMeters.coerceAtLeast(0)
        val last = pts.last()

        if (safeElapsed >= last.offsetSeconds) {
            val ghostDist = maxOf(last.distanceMeters, reference.totalDistanceMeters)
            return GhostDelta(
                deltaMeters = safeDist - ghostDist,
                ghostDistanceMeters = ghostDist,
                ghostFinished = true,
            )
        }

        // Binary search for surrounding sample interval
        var lo = 0
        var hi = pts.lastIndex
        while (lo + 1 < hi) {
            val mid = (lo + hi) ushr 1
            if (pts[mid].offsetSeconds <= safeElapsed) {
                lo = mid
            } else {
                hi = mid
            }
        }

        val p0 = pts[lo]
        val p1 = pts[hi]
        val dt = (p1.offsetSeconds - p0.offsetSeconds).coerceAtLeast(1)
        val frac = ((safeElapsed - p0.offsetSeconds).toDouble() / dt.toDouble()).coerceIn(0.0, 1.0)
        val ghostDist = (p0.distanceMeters + (p1.distanceMeters - p0.distanceMeters) * frac).roundToInt()

        return GhostDelta(
            deltaMeters = safeDist - ghostDist,
            ghostDistanceMeters = ghostDist,
            ghostFinished = false,
        )
    }
}
