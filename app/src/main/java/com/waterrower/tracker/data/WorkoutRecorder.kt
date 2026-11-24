package com.waterrower.tracker.data

import com.waterrower.tracker.model.RowingTelemetry
import java.util.UUID
import kotlin.math.roundToInt

sealed interface RecordingState {
    data object Idle : RecordingState
    data class Active(
        val workoutId: String,
        val startedAtEpochMs: Long,
        val elapsedSeconds: Int,
        val distanceMeters: Int,
        val isPaused: Boolean,
        val sampleCount: Int,
    ) : RecordingState
}

data class RecordedWorkout(
    val workout: WorkoutEntity,
    val samples: List<WorkoutSampleEntity>,
)

/**
 * Stateful workout session recorder with auto-start support, 1Hz sample capture,
 * pause handling, and summary aggregation. Pure Kotlin for easy JVM unit testing.
 */
class WorkoutRecorder {

    private var workoutId: String? = null
    private var startedAtMs: Long = 0L
    private var lastSampleTimestampMs: Long = 0L
    private var isPaused: Boolean = false
    private var isSimulatedSession: Boolean = false

    private var baseDistance: Int? = null
    private var baseStrokes: Int? = null
    private var baseCalories: Int? = null
    private var lastRawDistance: Int = 0
    private var lastRawStrokes: Int = 0
    private var lastRawCalories: Int = 0
    private var lastActiveTimestampMs: Long = 0L

    private var accumulatedActiveSeconds: Int = 0
    private val samples = mutableListOf<WorkoutSampleEntity>()

    private var spmSum = 0.0
    private var spmCount = 0
    private var wattsSum = 0L
    private var wattsCount = 0
    private var hrSum = 0L
    private var hrCount = 0

    var state: RecordingState = RecordingState.Idle
        private set

    fun start(
        nowMs: Long = System.currentTimeMillis(),
        initialTelemetry: RowingTelemetry? = null,
        isSimulated: Boolean = false,
    ): RecordingState {
        resetInternal()
        val id = UUID.randomUUID().toString()
        workoutId = id
        startedAtMs = nowMs
        lastSampleTimestampMs = 0L
        lastActiveTimestampMs = nowMs
        isPaused = false
        isSimulatedSession = isSimulated

        if (initialTelemetry != null) {
            baseDistance = initialTelemetry.distanceMeters
            baseStrokes = initialTelemetry.strokeCount
            baseCalories = initialTelemetry.calories
            lastRawDistance = initialTelemetry.distanceMeters
            lastRawStrokes = initialTelemetry.strokeCount
            lastRawCalories = initialTelemetry.calories
        }

        state = RecordingState.Active(
            workoutId = id,
            startedAtEpochMs = nowMs,
            elapsedSeconds = 0,
            distanceMeters = 0,
            isPaused = false,
            sampleCount = 0,
        )
        return state
    }

    fun togglePause(nowMs: Long = System.currentTimeMillis()): RecordingState {
        val id = workoutId ?: return state
        isPaused = !isPaused
        if (!isPaused) {
            lastActiveTimestampMs = nowMs
        }
        state = RecordingState.Active(
            workoutId = id,
            startedAtEpochMs = startedAtMs,
            elapsedSeconds = accumulatedActiveSeconds,
            distanceMeters = currentDistanceDelta(),
            isPaused = isPaused,
            sampleCount = samples.size,
        )
        return state
    }

    /**
     * Feeds a new telemetry frame into the recorder.
     * If [autoStart] is true and the recorder is idle, recording starts automatically
     * once rowing activity (stroke rate > 0 or distance > 0) is detected.
     */
    fun onTelemetry(
        telemetry: RowingTelemetry,
        autoStart: Boolean,
        isSimulated: Boolean,
        nowMs: Long = telemetry.timestampMs,
    ): RecordingState {
        val isRowingActive = telemetry.strokeRateSpm > 0f || telemetry.distanceMeters > 0

        if (workoutId == null) {
            if (autoStart && isRowingActive) {
                start(nowMs = nowMs, initialTelemetry = null, isSimulated = isSimulated)
            } else {
                return state
            }
        }

        val id = workoutId ?: return state
        if (isPaused) return state

        if (baseDistance == null) {
            // If monitor started from 0, keep base at 0 so early meters aren't lost.
            baseDistance = if (telemetry.distanceMeters <= 25) 0 else telemetry.distanceMeters
            baseStrokes = if (telemetry.strokeCount <= 3) 0 else telemetry.strokeCount
            baseCalories = if (telemetry.calories <= 5) 0 else telemetry.calories
        }

        // Handle S4 monitor reset mid-session gracefully
        if (telemetry.distanceMeters < (baseDistance ?: 0)) {
            baseDistance = 0
        }
        if (telemetry.strokeCount < (baseStrokes ?: 0)) {
            baseStrokes = 0
        }
        if (telemetry.calories < (baseCalories ?: 0)) {
            baseCalories = 0
        }

        lastRawDistance = telemetry.distanceMeters
        lastRawStrokes = telemetry.strokeCount
        lastRawCalories = telemetry.calories

        // Downsample to at most 1 sample per second
        if (lastSampleTimestampMs == 0L || nowMs - lastSampleTimestampMs >= 950L) {
            val deltaSec = if (lastSampleTimestampMs == 0L) {
                1
            } else {
                ((nowMs - lastSampleTimestampMs) / 1000L).toInt().coerceIn(1, 5)
            }
            accumulatedActiveSeconds += deltaSec
            lastSampleTimestampMs = nowMs

            if (telemetry.strokeRateSpm > 0f) {
                spmSum += telemetry.strokeRateSpm
                spmCount += 1
            }
            if (telemetry.watts > 0) {
                wattsSum += telemetry.watts
                wattsCount += 1
            }
            telemetry.heartRateBpm?.let { hr ->
                if (hr > 0) {
                    hrSum += hr
                    hrCount += 1
                }
            }

            samples.add(
                WorkoutSampleEntity(
                    workoutId = id,
                    offsetSeconds = accumulatedActiveSeconds,
                    distanceMeters = currentDistanceDelta(),
                    paceSecPer500m = telemetry.paceSecondsPer500m,
                    strokeRateSpm = telemetry.strokeRateSpm,
                    watts = telemetry.watts,
                    heartRateBpm = telemetry.heartRateBpm,
                ),
            )
        }

        state = RecordingState.Active(
            workoutId = id,
            startedAtEpochMs = startedAtMs,
            elapsedSeconds = accumulatedActiveSeconds,
            distanceMeters = currentDistanceDelta(),
            isPaused = false,
            sampleCount = samples.size,
        )
        return state
    }

    /**
     * Stops the current workout and returns the finalized [RecordedWorkout],
     * or null if no meaningful activity was recorded.
     */
    fun stop(nowMs: Long = System.currentTimeMillis()): RecordedWorkout? {
        val id = workoutId ?: return null
        val distance = currentDistanceDelta()
        val duration = accumulatedActiveSeconds

        val result = if (duration >= 5 || distance >= 10) {
            val avgPace = if (distance > 0 && duration > 0) {
                ((duration * 500.0) / distance).roundToInt()
            } else {
                0
            }
            val avgSpm = if (spmCount > 0) (spmSum / spmCount).toFloat() else 0f
            val avgWatts = if (wattsCount > 0) (wattsSum / wattsCount).toInt() else 0
            val avgHr = if (hrCount > 0) (hrSum / hrCount).toInt() else null

            RecordedWorkout(
                workout = WorkoutEntity(
                    id = id,
                    startedAtEpochMs = startedAtMs,
                    endedAtEpochMs = nowMs.coerceAtLeast(startedAtMs),
                    durationSeconds = duration,
                    totalDistanceMeters = distance,
                    avgPaceSecPer500m = avgPace,
                    avgStrokeRate = avgSpm,
                    avgWatts = avgWatts,
                    totalStrokes = (lastRawStrokes - (baseStrokes ?: 0)).coerceAtLeast(0),
                    totalCalories = (lastRawCalories - (baseCalories ?: 0)).coerceAtLeast(0),
                    avgHeartRateBpm = avgHr,
                    isSimulated = isSimulatedSession,
                    syncedAtEpochMs = null,
                ),
                samples = samples.toList(),
            )
        } else {
            null
        }

        resetInternal()
        state = RecordingState.Idle
        return result
    }

    private fun currentDistanceDelta(): Int =
        (lastRawDistance - (baseDistance ?: 0)).coerceAtLeast(0)

    private fun resetInternal() {
        workoutId = null
        startedAtMs = 0L
        lastSampleTimestampMs = 0L
        isPaused = false
        isSimulatedSession = false
        baseDistance = null
        baseStrokes = null
        baseCalories = null
        lastRawDistance = 0
        lastRawStrokes = 0
        lastRawCalories = 0
        lastActiveTimestampMs = 0L
        accumulatedActiveSeconds = 0
        samples.clear()
        spmSum = 0.0
        spmCount = 0
        wattsSum = 0L
        wattsCount = 0
        hrSum = 0L
        hrCount = 0
    }
}
