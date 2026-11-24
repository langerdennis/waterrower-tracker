package com.waterrower.tracker.data

import com.waterrower.tracker.model.RowingTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutRecorderTest {

    @Test
    fun autoStartAndStop_aggregatesWorkoutMetricsCorrectly() {
        val recorder = WorkoutRecorder()
        val t0 = 1_700_000_000_000L

        // Idle frame with zero activity should not start recording
        val s0 = recorder.onTelemetry(
            RowingTelemetry(timestampMs = t0, distanceMeters = 0, strokeRateSpm = 0f),
            autoStart = true,
            isSimulated = false,
            nowMs = t0,
        )
        assertTrue(s0 is RecordingState.Idle)

        // Feed 10 seconds of steady rowing (4 m/s -> 125 sec / 500m pace)
        for (sec in 1..10) {
            val now = t0 + sec * 1_000L
            recorder.onTelemetry(
                RowingTelemetry(
                    timestampMs = now,
                    distanceMeters = sec * 4,
                    elapsedSeconds = sec,
                    strokeRateSpm = 26.0f,
                    strokeCount = sec,
                    paceSecondsPer500m = 125,
                    watts = 180,
                    calories = sec,
                    heartRateBpm = 140,
                ),
                autoStart = true,
                isSimulated = false,
                nowMs = now,
            )
        }

        val recorded = recorder.stop(nowMs = t0 + 10_000L)
        assertNotNull(recorded)
        recorded!!

        assertEquals(10, recorded.workout.durationSeconds)
        assertEquals(40, recorded.workout.totalDistanceMeters)
        assertEquals(125, recorded.workout.avgPaceSecPer500m)
        assertEquals(26.0f, recorded.workout.avgStrokeRate, 0.01f)
        assertEquals(180, recorded.workout.avgWatts)
        assertEquals(140, recorded.workout.avgHeartRateBpm)
        assertEquals(10, recorded.samples.size)
        assertTrue(recorder.state is RecordingState.Idle)
    }

    @Test
    fun pauseAndResume_skipsSamplesWhilePaused() {
        val recorder = WorkoutRecorder()
        val t0 = 1_700_000_000_000L
        recorder.start(nowMs = t0)

        for (sec in 1..6) {
            recorder.onTelemetry(
                RowingTelemetry(
                    timestampMs = t0 + sec * 1_000L,
                    distanceMeters = sec * 5,
                    strokeRateSpm = 24f,
                    watts = 150,
                ),
                autoStart = false,
                isSimulated = true,
                nowMs = t0 + sec * 1_000L,
            )
        }

        recorder.togglePause(nowMs = t0 + 7_000L)
        recorder.onTelemetry(
            RowingTelemetry(
                timestampMs = t0 + 8_000L,
                distanceMeters = 30,
                strokeRateSpm = 0f,
                watts = 0,
            ),
            autoStart = false,
            isSimulated = true,
            nowMs = t0 + 8_000L,
        )

        val recorded = recorder.stop(nowMs = t0 + 9_000L)
        assertNotNull(recorded)
        assertEquals(6, recorded!!.samples.size)
        assertEquals(30, recorded.workout.totalDistanceMeters)
    }

    @Test
    fun tooShortSession_isDiscardedOnStop() {
        val recorder = WorkoutRecorder()
        val t0 = 1_700_000_000_000L
        recorder.start(nowMs = t0)
        recorder.onTelemetry(
            RowingTelemetry(timestampMs = t0 + 1_000L, distanceMeters = 2, strokeRateSpm = 20f),
            autoStart = false,
            isSimulated = false,
            nowMs = t0 + 1_000L,
        )

        assertNull(recorder.stop(nowMs = t0 + 2_000L))
    }
}
