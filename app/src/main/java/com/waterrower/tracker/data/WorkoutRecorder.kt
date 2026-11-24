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
        val strokeCount: Int = 0,
        val calories: Int = 0,
        val isAutoPaused: Boolean = false,
    ) : RecordingState
}

data class RecordedWorkout(
    val workout: WorkoutEntity,
    val samples: List<WorkoutSampleEntity>,
)

/**
 * Stateful workout session recorder with real-movement auto-start, manual-stop cooldown,
 * auto-pause/auto-resume, drift-free millisecond timing, S4 mid-session reset preservation,
 * and 1Hz sample capture. Thread-safe and pure Kotlin for JVM unit testing.
 */
class WorkoutRecorder(
    private val manualStopCooldownMs: Long = DEFAULT_MANUAL_STOP_COOLDOWN_MS,
    private val autoPauseTimeoutMs: Long = DEFAULT_AUTO_PAUSE_TIMEOUT_MS,
) {
    private val lock = Any()

    private var workoutId: String? = null
    private var startedAtMs: Long = 0L
    private var lastActiveClockMs: Long = 0L
    private var lastActiveTimestampMs: Long = 0L
    private var isPaused: Boolean = false
    private var isAutoPaused: Boolean = false
    private var isSimulatedSession: Boolean = false

    private var baseDistance: Int? = null
    private var baseStrokes: Int? = null
    private var baseCalories: Int? = null
    private var distanceOffset: Int = 0
    private var strokesOffset: Int = 0
    private var caloriesOffset: Int = 0
    private var lastRawDistance: Int = 0
    private var lastRawStrokes: Int = 0
    private var lastRawCalories: Int = 0
    private var hasSeenSessionTelemetry: Boolean = false

    private var lastPausedRawDistance: Int = 0
    private var lastPausedRawStrokes: Int = 0
    private var lastPausedRawCalories: Int = 0

    // Idle baseline & cooldown persist across resetInternal()
    private var manualStopCooldownUntilMs: Long = 0L
    private var idleBaselineDistance: Int? = null
    private var idleBaselineStrokes: Int? = null
    private var idleBaselineCalories: Int? = null
    private var lastIdleTimestampMs: Long = 0L

    private var accumulatedActiveMs: Long = 0L
    private var lastSampleElapsedSec: Int = 0
    private val samples = mutableListOf<WorkoutSampleEntity>()

    private var spmSum = 0.0
    private var spmCount = 0
    private var wattsSum = 0L
    private var wattsCount = 0
    private var hrSum = 0L
    private var hrCount = 0

    @Volatile
    var state: RecordingState = RecordingState.Idle
        private set

    fun start(
        nowMs: Long = System.currentTimeMillis(),
        initialTelemetry: RowingTelemetry? = null,
        isSimulated: Boolean = false,
    ): RecordingState = synchronized(lock) {
        startLocked(
            nowMs = nowMs,
            initialTelemetry = initialTelemetry,
            isSimulated = isSimulated,
            clockAnchorMs = nowMs,
        )
    }

    fun togglePause(nowMs: Long = System.currentTimeMillis()): RecordingState = synchronized(lock) {
        val id = workoutId ?: return state
        if (isPaused) {
            resumeLocked(nowMs)
        } else {
            isPaused = true
            isAutoPaused = false
            lastPausedRawDistance = lastRawDistance
            lastPausedRawStrokes = lastRawStrokes
            lastPausedRawCalories = lastRawCalories
        }
        state = buildActiveStateLocked(id)
        state
    }

    fun resetIdleBaseline() = synchronized(lock) {
        idleBaselineDistance = null
        idleBaselineStrokes = null
        idleBaselineCalories = null
        lastIdleTimestampMs = 0L
    }

    /**
     * Feeds a new telemetry frame into the recorder.
     * If [autoStart] is true and the recorder is idle, recording starts automatically only on
     * genuine rowing movement (stroke rate > 0 or increasing distance/strokes vs idle baseline)
     * and outside the post-stop cooldown window.
     */
    fun onTelemetry(
        telemetry: RowingTelemetry,
        autoStart: Boolean,
        isSimulated: Boolean,
        nowMs: Long = telemetry.timestampMs,
    ): RecordingState = synchronized(lock) {
        if (workoutId == null) {
            if (nowMs < manualStopCooldownUntilMs) {
                idleBaselineDistance = telemetry.distanceMeters
                idleBaselineStrokes = telemetry.strokeCount
                idleBaselineCalories = telemetry.calories
                lastIdleTimestampMs = nowMs
                return state
            }

            if (!autoStart) {
                idleBaselineDistance = telemetry.distanceMeters
                idleBaselineStrokes = telemetry.strokeCount
                idleBaselineCalories = telemetry.calories
                lastIdleTimestampMs = nowMs
                return state
            }

            if (idleBaselineDistance == null) {
                idleBaselineDistance = telemetry.distanceMeters
                idleBaselineStrokes = telemetry.strokeCount
                idleBaselineCalories = telemetry.calories
            } else {
                // Handle S4 monitor reset while idle
                if (telemetry.distanceMeters < idleBaselineDistance!!) {
                    idleBaselineDistance = telemetry.distanceMeters
                }
                if (telemetry.strokeCount < (idleBaselineStrokes ?: 0)) {
                    idleBaselineStrokes = telemetry.strokeCount
                }
                if (telemetry.calories < (idleBaselineCalories ?: 0)) {
                    idleBaselineCalories = telemetry.calories
                }
            }

            val baselineDist = idleBaselineDistance!!
            val baselineStrokes = idleBaselineStrokes ?: 0
            val baselineCalories = idleBaselineCalories ?: 0
            val distanceIncreased = telemetry.distanceMeters > baselineDist
            val strokesIncreased = telemetry.strokeCount > baselineStrokes
            val hasRealMovement = telemetry.strokeRateSpm > 0f || distanceIncreased || strokesIncreased

            if (hasRealMovement) {
                val prevIdleTs = lastIdleTimestampMs
                val clockAnchor = if (prevIdleTs > 0L && nowMs >= prevIdleTs && nowMs - prevIdleTs <= 2_000L) {
                    prevIdleTs.coerceAtLeast(nowMs - 1_000L)
                } else {
                    nowMs
                }
                startLocked(
                    nowMs = nowMs,
                    initialTelemetry = null,
                    isSimulated = isSimulated,
                    clockAnchorMs = clockAnchor,
                )
                baseDistance = baselineDist
                baseStrokes = baselineStrokes
                baseCalories = baselineCalories
                lastRawDistance = baseDistance ?: 0
                lastRawStrokes = baseStrokes ?: 0
                lastRawCalories = baseCalories ?: 0
                idleBaselineDistance = null
                idleBaselineStrokes = null
                idleBaselineCalories = null
                lastIdleTimestampMs = 0L
            } else {
                lastIdleTimestampMs = nowMs
                return state
            }
        }

        val id = workoutId ?: return state

        if (isPaused) {
            val wakeMovement = telemetry.strokeRateSpm > 0f ||
                telemetry.distanceMeters > lastPausedRawDistance ||
                telemetry.strokeCount > lastPausedRawStrokes
            if (isAutoPaused && wakeMovement) {
                resumeLocked(nowMs)
            } else {
                lastPausedRawDistance = telemetry.distanceMeters
                lastPausedRawStrokes = telemetry.strokeCount
                lastPausedRawCalories = telemetry.calories
                return state
            }
        }

        val hasMovement = telemetry.strokeRateSpm > 0f ||
            (hasSeenSessionTelemetry && (telemetry.distanceMeters > lastRawDistance || telemetry.strokeCount > lastRawStrokes))

        if (hasMovement) {
            if (nowMs > lastActiveTimestampMs) {
                lastActiveTimestampMs = nowMs
            }
        } else if (autoPauseTimeoutMs > 0L && nowMs - lastActiveTimestampMs >= autoPauseTimeoutMs) {
            isPaused = true
            isAutoPaused = true
            lastPausedRawDistance = telemetry.distanceMeters
            lastPausedRawStrokes = telemetry.strokeCount
            lastPausedRawCalories = telemetry.calories
            state = buildActiveStateLocked(id)
            return state
        }

        if (baseDistance == null) {
            // If monitor started from 0, keep base at 0 so early meters aren't lost.
            baseDistance = if (telemetry.distanceMeters <= 25) 0 else telemetry.distanceMeters
            baseStrokes = if (telemetry.strokeCount <= 3) 0 else telemetry.strokeCount
            baseCalories = if (telemetry.calories <= 5) 0 else telemetry.calories
        }

        // Preserve accumulated progress if S4 monitor resets mid-session (even when base == 0)
        if (hasSeenSessionTelemetry) {
            if (telemetry.distanceMeters < lastRawDistance) {
                distanceOffset += (lastRawDistance - (baseDistance ?: 0)).coerceAtLeast(0)
                baseDistance = 0
            }
            if (telemetry.strokeCount < lastRawStrokes) {
                strokesOffset += (lastRawStrokes - (baseStrokes ?: 0)).coerceAtLeast(0)
                baseStrokes = 0
            }
            if (telemetry.calories < lastRawCalories) {
                caloriesOffset += (lastRawCalories - (baseCalories ?: 0)).coerceAtLeast(0)
                baseCalories = 0
            }
        }

        lastRawDistance = telemetry.distanceMeters
        lastRawStrokes = telemetry.strokeCount
        lastRawCalories = telemetry.calories
        hasSeenSessionTelemetry = true

        val dtMs = (nowMs - lastActiveClockMs).coerceIn(0L, MAX_SINGLE_TICK_MS)
        accumulatedActiveMs += dtMs
        if (nowMs > lastActiveClockMs) {
            lastActiveClockMs = nowMs
        }

        val elapsedSec = currentElapsedSecondsLocked()
        if (elapsedSec > lastSampleElapsedSec) {
            lastSampleElapsedSec = elapsedSec

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
                    offsetSeconds = elapsedSec,
                    distanceMeters = currentDistanceDeltaLocked(),
                    paceSecPer500m = telemetry.paceSecondsPer500m,
                    strokeRateSpm = telemetry.strokeRateSpm,
                    watts = telemetry.watts,
                    heartRateBpm = telemetry.heartRateBpm,
                ),
            )
        }

        state = buildActiveStateLocked(id)
        state
    }

    /**
     * Stops the current workout and returns the finalized [RecordedWorkout],
     * or null if no meaningful activity was recorded.
     */
    fun stop(nowMs: Long = System.currentTimeMillis()): RecordedWorkout? = synchronized(lock) {
        val id = workoutId ?: return null
        val distance = currentDistanceDeltaLocked()
        val duration = currentElapsedSecondsLocked()
        val totalStrokes = currentStrokesDeltaLocked()
        val totalCalories = currentCaloriesDeltaLocked()

        val hadTelemetry = hasSeenSessionTelemetry
        val stopRawDist = if (isPaused) lastPausedRawDistance else lastRawDistance
        val stopRawStrokes = if (isPaused) lastPausedRawStrokes else lastRawStrokes
        val stopRawCalories = if (isPaused) lastPausedRawCalories else lastRawCalories

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
                    totalStrokes = totalStrokes,
                    totalCalories = totalCalories,
                    avgHeartRateBpm = avgHr,
                    isSimulated = isSimulatedSession,
                    syncedAtEpochMs = null,
                ),
                samples = samples.toList(),
            )
        } else {
            null
        }

        resetInternalLocked()
        manualStopCooldownUntilMs = nowMs + manualStopCooldownMs
        idleBaselineDistance = if (hadTelemetry) stopRawDist else null
        idleBaselineStrokes = if (hadTelemetry) stopRawStrokes else null
        idleBaselineCalories = if (hadTelemetry) stopRawCalories else null
        lastIdleTimestampMs = if (hadTelemetry) nowMs else 0L
        state = RecordingState.Idle
        result
    }

    private fun startLocked(
        nowMs: Long,
        initialTelemetry: RowingTelemetry?,
        isSimulated: Boolean,
        clockAnchorMs: Long,
    ): RecordingState {
        resetInternalLocked()
        val id = UUID.randomUUID().toString()
        workoutId = id
        startedAtMs = nowMs
        lastActiveClockMs = clockAnchorMs
        lastActiveTimestampMs = nowMs
        isPaused = false
        isAutoPaused = false
        isSimulatedSession = isSimulated

        if (initialTelemetry != null) {
            baseDistance = initialTelemetry.distanceMeters
            baseStrokes = initialTelemetry.strokeCount
            baseCalories = initialTelemetry.calories
            lastRawDistance = initialTelemetry.distanceMeters
            lastRawStrokes = initialTelemetry.strokeCount
            lastRawCalories = initialTelemetry.calories
            hasSeenSessionTelemetry = true
        }

        state = buildActiveStateLocked(id)
        return state
    }

    private fun resumeLocked(nowMs: Long) {
        if (hasSeenSessionTelemetry) {
            distanceOffset = currentDistanceDeltaLocked()
            strokesOffset = currentStrokesDeltaLocked()
            caloriesOffset = currentCaloriesDeltaLocked()
            baseDistance = lastPausedRawDistance
            baseStrokes = lastPausedRawStrokes
            baseCalories = lastPausedRawCalories
            lastRawDistance = lastPausedRawDistance
            lastRawStrokes = lastPausedRawStrokes
            lastRawCalories = lastPausedRawCalories
        }
        isPaused = false
        isAutoPaused = false
        lastActiveTimestampMs = nowMs
        lastActiveClockMs = nowMs
    }

    private fun buildActiveStateLocked(id: String): RecordingState.Active =
        RecordingState.Active(
            workoutId = id,
            startedAtEpochMs = startedAtMs,
            elapsedSeconds = currentElapsedSecondsLocked(),
            distanceMeters = currentDistanceDeltaLocked(),
            isPaused = isPaused,
            sampleCount = samples.size,
            strokeCount = currentStrokesDeltaLocked(),
            calories = currentCaloriesDeltaLocked(),
            isAutoPaused = isAutoPaused,
        )

    private fun currentElapsedSecondsLocked(): Int = (accumulatedActiveMs / 1_000L).toInt()

    private fun currentDistanceDeltaLocked(): Int =
        distanceOffset + (lastRawDistance - (baseDistance ?: 0)).coerceAtLeast(0)

    private fun currentStrokesDeltaLocked(): Int =
        strokesOffset + (lastRawStrokes - (baseStrokes ?: 0)).coerceAtLeast(0)

    private fun currentCaloriesDeltaLocked(): Int =
        caloriesOffset + (lastRawCalories - (baseCalories ?: 0)).coerceAtLeast(0)

    private fun resetInternalLocked() {
        workoutId = null
        startedAtMs = 0L
        lastActiveClockMs = 0L
        lastActiveTimestampMs = 0L
        isPaused = false
        isAutoPaused = false
        isSimulatedSession = false
        baseDistance = null
        baseStrokes = null
        baseCalories = null
        distanceOffset = 0
        strokesOffset = 0
        caloriesOffset = 0
        lastRawDistance = 0
        lastRawStrokes = 0
        lastRawCalories = 0
        hasSeenSessionTelemetry = false
        lastPausedRawDistance = 0
        lastPausedRawStrokes = 0
        lastPausedRawCalories = 0
        accumulatedActiveMs = 0L
        lastSampleElapsedSec = 0
        samples.clear()
        spmSum = 0.0
        spmCount = 0
        wattsSum = 0L
        wattsCount = 0
        hrSum = 0L
        hrCount = 0
    }

    companion object {
        const val DEFAULT_MANUAL_STOP_COOLDOWN_MS = 15_000L
        const val DEFAULT_AUTO_PAUSE_TIMEOUT_MS = 15_000L
        private const val MAX_SINGLE_TICK_MS = 5_000L
    }
}
