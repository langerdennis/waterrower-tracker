package com.waterrower.tracker.data

import com.waterrower.tracker.model.RowingTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun autoStart_ignoresStaticLeftoverDistanceFromS4Monitor() {
        val recorder = WorkoutRecorder()
        val t0 = 1_700_000_000_000L

        // S4 monitor keeps broadcasting leftover distance = 2500m with spm = 0
        for (sec in 0..5) {
            val st = recorder.onTelemetry(
                RowingTelemetry(
                    timestampMs = t0 + sec * 1_000L,
                    distanceMeters = 2500,
                    strokeCount = 210,
                    calories = 95,
                    strokeRateSpm = 0f,
                ),
                autoStart = true,
                isSimulated = false,
                nowMs = t0 + sec * 1_000L,
            )
            assertTrue("Expected Idle on static leftover distance at sec=$sec", st is RecordingState.Idle)
        }

        // Genuine pull increases distance & strokes from the idle baseline
        val active = recorder.onTelemetry(
            RowingTelemetry(
                timestampMs = t0 + 6_000L,
                distanceMeters = 2505,
                strokeCount = 211,
                calories = 96,
                strokeRateSpm = 24f,
                watts = 165,
            ),
            autoStart = true,
            isSimulated = false,
            nowMs = t0 + 6_000L,
        )
        assertTrue(active is RecordingState.Active)
        active as RecordingState.Active
        assertEquals(5, active.distanceMeters)
        assertEquals(1, active.strokeCount)
        assertEquals(1, active.calories)
    }

    @Test
    fun autoStart_respectsCooldownAfterManualStop() {
        val recorder = WorkoutRecorder(manualStopCooldownMs = 10_000L)
        val t0 = 1_700_000_000_000L

        recorder.start(nowMs = t0)
        for (sec in 1..6) {
            recorder.onTelemetry(
                RowingTelemetry(
                    timestampMs = t0 + sec * 1_000L,
                    distanceMeters = sec * 5,
                    strokeCount = sec,
                    calories = sec,
                    strokeRateSpm = 25f,
                    watts = 170,
                ),
                autoStart = true,
                isSimulated = false,
                nowMs = t0 + sec * 1_000L,
            )
        }
        val stopTs = t0 + 6_000L
        assertNotNull(recorder.stop(nowMs = stopTs))

        // During 10s cooldown: even coasting flywheel with spm > 0 must NOT start a ghost workout
        for (sec in 1..9) {
            val st = recorder.onTelemetry(
                RowingTelemetry(
                    timestampMs = stopTs + sec * 1_000L,
                    distanceMeters = 30 + sec,
                    strokeCount = 6,
                    strokeRateSpm = 14f,
                ),
                autoStart = true,
                isSimulated = false,
                nowMs = stopTs + sec * 1_000L,
            )
            assertTrue("Expected Idle during cooldown at +${sec}s", st is RecordingState.Idle)
        }

        // After cooldown: static distance (39m, spm=0) must remain Idle
        val afterCooldownIdle = recorder.onTelemetry(
            RowingTelemetry(
                timestampMs = stopTs + 10_000L,
                distanceMeters = 39,
                strokeCount = 6,
                strokeRateSpm = 0f,
            ),
            autoStart = true,
            isSimulated = false,
            nowMs = stopTs + 10_000L,
        )
        assertTrue(afterCooldownIdle is RecordingState.Idle)

        // New genuine rowing after cooldown starts cleanly from delta 0
        val restarted = recorder.onTelemetry(
            RowingTelemetry(
                timestampMs = stopTs + 11_000L,
                distanceMeters = 43,
                strokeCount = 7,
                strokeRateSpm = 24f,
            ),
            autoStart = true,
            isSimulated = false,
            nowMs = stopTs + 11_000L,
        )
        assertTrue(restarted is RecordingState.Active)
        assertEquals(4, (restarted as RecordingState.Active).distanceMeters)
    }

    @Test
    fun autoStart_handlesMonitorResetWhileIdle() {
        val recorder = WorkoutRecorder()
        val t0 = 1_700_000_000_000L

        // Idle at 2000m
        assertTrue(
            recorder.onTelemetry(
                RowingTelemetry(timestampMs = t0, distanceMeters = 2000, strokeCount = 180, strokeRateSpm = 0f),
                autoStart = true,
                isSimulated = false,
                nowMs = t0,
            ) is RecordingState.Idle,
        )

        // User presses reset on S4 monitor while idle -> 0m
        assertTrue(
            recorder.onTelemetry(
                RowingTelemetry(timestampMs = t0 + 1_000L, distanceMeters = 0, strokeCount = 0, strokeRateSpm = 0f),
                autoStart = true,
                isSimulated = false,
                nowMs = t0 + 1_000L,
            ) is RecordingState.Idle,
        )

        // User starts rowing -> 4m (even if spm hasn't updated yet)
        val started = recorder.onTelemetry(
            RowingTelemetry(timestampMs = t0 + 2_000L, distanceMeters = 4, strokeCount = 1, strokeRateSpm = 0f),
            autoStart = true,
            isSimulated = false,
            nowMs = t0 + 2_000L,
        )
        assertTrue(started is RecordingState.Active)
        assertEquals(4, (started as RecordingState.Active).distanceMeters)
    }

    @Test
    fun autoPause_pausesAfterInactivityAndAutoResumesOnMovement() {
        val recorder = WorkoutRecorder(autoPauseTimeoutMs = 15_000L)
        val t0 = 1_700_000_000_000L
        recorder.start(nowMs = t0)

        // Active rowing for 5s
        for (sec in 1..5) {
            recorder.onTelemetry(
                RowingTelemetry(
                    timestampMs = t0 + sec * 1_000L,
                    distanceMeters = sec * 4,
                    strokeCount = sec,
                    strokeRateSpm = 24f,
                    watts = 160,
                ),
                autoStart = false,
                isSimulated = false,
                nowMs = t0 + sec * 1_000L,
            )
        }

        // Stationary for 14s (sec=6..19): not auto-paused yet
        var lastState: RecordingState = recorder.state
        for (sec in 6..19) {
            lastState = recorder.onTelemetry(
                RowingTelemetry(
                    timestampMs = t0 + sec * 1_000L,
                    distanceMeters = 20,
                    strokeCount = 5,
                    strokeRateSpm = 0f,
                ),
                autoStart = false,
                isSimulated = false,
                nowMs = t0 + sec * 1_000L,
            )
            assertFalse("Should not auto-pause before 15s at sec=$sec", (lastState as RecordingState.Active).isPaused)
        }

        // At sec=20 (15s since last movement at sec=5) -> auto-pause triggers
        val autoPaused = recorder.onTelemetry(
            RowingTelemetry(
                timestampMs = t0 + 20_000L,
                distanceMeters = 20,
                strokeCount = 5,
                strokeRateSpm = 0f,
            ),
            autoStart = false,
            isSimulated = false,
            nowMs = t0 + 20_000L,
        ) as RecordingState.Active
        assertTrue(autoPaused.isPaused)
        assertTrue(autoPaused.isAutoPaused)
        val pausedElapsed = autoPaused.elapsedSeconds
        val pausedSamples = autoPaused.sampleCount

        // Remaining stationary while auto-paused freezes elapsed time & sample count
        val stillPaused = recorder.onTelemetry(
            RowingTelemetry(
                timestampMs = t0 + 40_000L,
                distanceMeters = 20,
                strokeCount = 5,
                strokeRateSpm = 0f,
            ),
            autoStart = false,
            isSimulated = false,
            nowMs = t0 + 40_000L,
        ) as RecordingState.Active
        assertTrue(stillPaused.isPaused)
        assertEquals(pausedElapsed, stillPaused.elapsedSeconds)
        assertEquals(pausedSamples, stillPaused.sampleCount)

        // Rowing resumes at +50s -> auto-resumes with ZERO ghost seconds!
        val resumed = recorder.onTelemetry(
            RowingTelemetry(
                timestampMs = t0 + 50_000L,
                distanceMeters = 24,
                strokeCount = 6,
                strokeRateSpm = 24f,
                watts = 160,
            ),
            autoStart = false,
            isSimulated = false,
            nowMs = t0 + 50_000L,
        ) as RecordingState.Active
        assertFalse(resumed.isPaused)
        assertFalse(resumed.isAutoPaused)
        assertEquals(pausedElapsed, resumed.elapsedSeconds)
        assertEquals(24, resumed.distanceMeters)

        // Next second advances cleanly by +1s
        val nextTick = recorder.onTelemetry(
            RowingTelemetry(
                timestampMs = t0 + 51_000L,
                distanceMeters = 28,
                strokeCount = 7,
                strokeRateSpm = 24f,
                watts = 160,
            ),
            autoStart = false,
            isSimulated = false,
            nowMs = t0 + 51_000L,
        ) as RecordingState.Active
        assertEquals(pausedElapsed + 1, nextTick.elapsedSeconds)
    }

    @Test
    fun manualPause_doesNotAutoResumeAndAddsNoGhostSecondsOnManualResume() {
        val recorder = WorkoutRecorder()
        val t0 = 1_700_000_000_000L
        recorder.start(nowMs = t0)

        for (sec in 1..6) {
            recorder.onTelemetry(
                RowingTelemetry(
                    timestampMs = t0 + sec * 1_000L,
                    distanceMeters = sec * 5,
                    strokeCount = sec,
                    strokeRateSpm = 25f,
                    watts = 170,
                ),
                autoStart = false,
                isSimulated = false,
                nowMs = t0 + sec * 1_000L,
            )
        }

        // Manual pause at +7s
        val paused = recorder.togglePause(nowMs = t0 + 7_000L) as RecordingState.Active
        assertTrue(paused.isPaused)
        assertFalse(paused.isAutoPaused)
        assertEquals(6, paused.elapsedSeconds)

        // Movement arriving during manual pause must NOT auto-resume
        val duringPause = recorder.onTelemetry(
            RowingTelemetry(
                timestampMs = t0 + 30_000L,
                distanceMeters = 35,
                strokeCount = 7,
                strokeRateSpm = 26f,
                watts = 180,
            ),
            autoStart = false,
            isSimulated = false,
            nowMs = t0 + 30_000L,
        ) as RecordingState.Active
        assertTrue(duringPause.isPaused)
        assertEquals(6, duringPause.elapsedSeconds)
        assertEquals(30, duringPause.distanceMeters)

        // Manual resume after 60s pause
        val resumed = recorder.togglePause(nowMs = t0 + 67_000L) as RecordingState.Active
        assertFalse(resumed.isPaused)
        assertEquals(6, resumed.elapsedSeconds)

        // First telemetry tick 1s after manual resume must add +1s (NOT +5s ghost seconds!)
        val afterResume = recorder.onTelemetry(
            RowingTelemetry(
                timestampMs = t0 + 68_000L,
                distanceMeters = 40,
                strokeCount = 8,
                strokeRateSpm = 25f,
                watts = 175,
            ),
            autoStart = false,
            isSimulated = false,
            nowMs = t0 + 68_000L,
        ) as RecordingState.Active
        assertEquals(7, afterResume.elapsedSeconds)
        assertEquals(35, afterResume.distanceMeters)
    }

    @Test
    fun elapsedTime_noDriftUnderJitterySlowAndHighFrequencyCadence() {
        val t0 = 1_700_000_000_000L

        // 1. Fast jittery cadence (960ms * 25 = 24_000ms -> exact 24s, not 25s)
        val fastRecorder = WorkoutRecorder()
        fastRecorder.start(nowMs = t0)
        for (i in 1..25) {
            val now = t0 + i * 960L
            fastRecorder.onTelemetry(
                RowingTelemetry(timestampMs = now, distanceMeters = i * 4, strokeRateSpm = 24f, watts = 160),
                autoStart = false,
                isSimulated = false,
                nowMs = now,
            )
        }
        val fastResult = fastRecorder.stop(nowMs = t0 + 24_000L)!!
        assertEquals(24, fastResult.workout.durationSeconds)
        assertEquals(24, fastResult.samples.size)

        // 2. Slow cadence (1400ms * 10 = 14_000ms -> exact 14s, not 10s)
        val slowRecorder = WorkoutRecorder()
        slowRecorder.start(nowMs = t0)
        for (i in 1..10) {
            val now = t0 + i * 1_400L
            slowRecorder.onTelemetry(
                RowingTelemetry(timestampMs = now, distanceMeters = i * 5, strokeRateSpm = 24f, watts = 160),
                autoStart = false,
                isSimulated = false,
                nowMs = now,
            )
        }
        val slowResult = slowRecorder.stop(nowMs = t0 + 14_000L)!!
        assertEquals(14, slowResult.workout.durationSeconds)

        // 3. High-frequency 4Hz cadence (250ms * 40 = 10_000ms -> exact 10s & 10 1Hz samples)
        val highFreqRecorder = WorkoutRecorder()
        highFreqRecorder.start(nowMs = t0)
        for (i in 1..40) {
            val now = t0 + i * 250L
            highFreqRecorder.onTelemetry(
                RowingTelemetry(timestampMs = now, distanceMeters = i, strokeRateSpm = 26f, watts = 180),
                autoStart = false,
                isSimulated = false,
                nowMs = now,
            )
        }
        val highFreqResult = highFreqRecorder.stop(nowMs = t0 + 10_000L)!!
        assertEquals(10, highFreqResult.workout.durationSeconds)
        assertEquals(10, highFreqResult.samples.size)
    }

    @Test
    fun s4ResetMidSession_preservesAccumulatedDistanceStrokesAndCalories() {
        val recorder = WorkoutRecorder()
        val t0 = 1_700_000_000_000L
        recorder.start(nowMs = t0, initialTelemetry = RowingTelemetry())

        // Phase 1: row from 0 -> 1000m (baseDistance == 0), 80 strokes, 40 kcal
        for (sec in 1..5) {
            recorder.onTelemetry(
                RowingTelemetry(
                    timestampMs = t0 + sec * 1_000L,
                    distanceMeters = sec * 200,
                    strokeCount = sec * 16,
                    calories = sec * 8,
                    strokeRateSpm = 26f,
                    watts = 190,
                ),
                autoStart = false,
                isSimulated = false,
                nowMs = t0 + sec * 1_000L,
            )
        }

        // Phase 2: S4 monitor resets to 0 mid-session and climbs to 400m, 32 strokes, 16 kcal
        var activeState: RecordingState.Active? = null
        for (i in 0..4) {
            val sec = 6 + i
            activeState = recorder.onTelemetry(
                RowingTelemetry(
                    timestampMs = t0 + sec * 1_000L,
                    distanceMeters = i * 100,
                    strokeCount = i * 8,
                    calories = i * 4,
                    strokeRateSpm = 26f,
                    watts = 190,
                ),
                autoStart = false,
                isSimulated = false,
                nowMs = t0 + sec * 1_000L,
            ) as RecordingState.Active
        }

        // Verify active live delta fields
        assertEquals(1400, activeState!!.distanceMeters)
        assertEquals(112, activeState.strokeCount)
        assertEquals(56, activeState.calories)

        val recorded = recorder.stop(nowMs = t0 + 10_000L)!!
        assertEquals(1400, recorded.workout.totalDistanceMeters)
        assertEquals(112, recorded.workout.totalStrokes)
        assertEquals(56, recorded.workout.totalCalories)

        // Sample distances must remain strictly non-decreasing across the reset
        for (i in 1 until recorded.samples.size) {
            assertTrue(
                "Sample distance regressed at index $i",
                recorded.samples[i].distanceMeters >= recorded.samples[i - 1].distanceMeters,
            )
        }
    }

    @Test
    fun autoStart_toggledFalseToTrueAfterUnrecordedRowing_staysIdleOnStationaryFrame() {
        val recorder = WorkoutRecorder()
        val t0 = 1_700_000_000_000L

        // User disables auto-start and rows 500m (45 strokes, 25 kcal) unrecorded
        assertTrue(
            recorder.onTelemetry(
                RowingTelemetry(timestampMs = t0, distanceMeters = 0, strokeCount = 0, calories = 0, strokeRateSpm = 0f),
                autoStart = false,
                isSimulated = false,
                nowMs = t0,
            ) is RecordingState.Idle,
        )
        for (sec in 1..10) {
            assertTrue(
                recorder.onTelemetry(
                    RowingTelemetry(
                        timestampMs = t0 + sec * 1_000L,
                        distanceMeters = sec * 50,
                        strokeCount = sec * 4,
                        calories = sec * 2,
                        strokeRateSpm = 26f,
                    ),
                    autoStart = false,
                    isSimulated = false,
                    nowMs = t0 + sec * 1_000L,
                ) is RecordingState.Idle,
            )
        }

        // User stops rowing and toggles autoStart = true; stationary frame must NOT start a ghost workout
        val stationaryAfterToggle = recorder.onTelemetry(
            RowingTelemetry(
                timestampMs = t0 + 12_000L,
                distanceMeters = 500,
                strokeCount = 40,
                calories = 20,
                strokeRateSpm = 0f,
            ),
            autoStart = true,
            isSimulated = false,
            nowMs = t0 + 12_000L,
        )
        assertTrue("Expected Idle after toggling autoStart=true while stationary", stationaryAfterToggle is RecordingState.Idle)

        // Subsequent genuine pull starts cleanly from delta 0 (no phantom 500m!)
        val realStart = recorder.onTelemetry(
            RowingTelemetry(
                timestampMs = t0 + 13_000L,
                distanceMeters = 506,
                strokeCount = 41,
                calories = 21,
                strokeRateSpm = 24f,
            ),
            autoStart = true,
            isSimulated = false,
            nowMs = t0 + 13_000L,
        ) as RecordingState.Active
        assertEquals(6, realStart.distanceMeters)
        assertEquals(1, realStart.strokeCount)
        assertEquals(1, realStart.calories)
    }

    @Test
    fun stopWithoutTelemetry_thenStaticHighDistanceAfterCooldown_staysIdle() {
        val recorder = WorkoutRecorder()
        val t0 = 1_700_000_000_000L

        // Manual start without any telemetry followed by immediate stop
        recorder.start(nowMs = t0, initialTelemetry = null)
        assertNull(recorder.stop(nowMs = t0 + 1_000L))

        // After default 15s cooldown, static leftover 5000m frame must remain Idle
        val afterCooldown = recorder.onTelemetry(
            RowingTelemetry(
                timestampMs = t0 + 17_000L,
                distanceMeters = 5000,
                strokeCount = 400,
                calories = 200,
                strokeRateSpm = 0f,
            ),
            autoStart = true,
            isSimulated = false,
            nowMs = t0 + 17_000L,
        )
        assertTrue("Expected Idle on static 5000m frame after stop without telemetry", afterCooldown is RecordingState.Idle)
    }

    @Test
    fun staleOutOfOrderTimestampAfterManualResume_addsZeroGhostSeconds() {
        val recorder = WorkoutRecorder()
        val t0 = 1_700_000_000_000L
        recorder.start(nowMs = t0)

        for (sec in 1..10) {
            recorder.onTelemetry(
                RowingTelemetry(
                    timestampMs = t0 + sec * 1_000L,
                    distanceMeters = sec * 5,
                    strokeCount = sec,
                    strokeRateSpm = 25f,
                    watts = 175,
                ),
                autoStart = false,
                isSimulated = false,
                nowMs = t0 + sec * 1_000L,
            )
        }

        // Pause at +11s, resume at +71s
        recorder.togglePause(nowMs = t0 + 11_000L)
        recorder.togglePause(nowMs = t0 + 71_000L)

        // Stale buffered BLE frame captured 5s before resume arrives right after resume
        val afterStale = recorder.onTelemetry(
            RowingTelemetry(
                timestampMs = t0 + 66_000L,
                distanceMeters = 50,
                strokeCount = 10,
                strokeRateSpm = 24f,
                watts = 170,
            ),
            autoStart = false,
            isSimulated = false,
            nowMs = t0 + 66_000L,
        ) as RecordingState.Active
        assertEquals(10, afterStale.elapsedSeconds)

        // Next real frame at +72s must advance by exactly +1s (11s total, zero ghost seconds)
        val afterReal = recorder.onTelemetry(
            RowingTelemetry(
                timestampMs = t0 + 72_000L,
                distanceMeters = 55,
                strokeCount = 11,
                strokeRateSpm = 25f,
                watts = 175,
            ),
            autoStart = false,
            isSimulated = false,
            nowMs = t0 + 72_000L,
        ) as RecordingState.Active
        assertEquals(11, afterReal.elapsedSeconds)
    }

    @Test
    fun outOfOrderTimestampsMidSession_doNotInflateDuration() {
        val recorder = WorkoutRecorder()
        val t0 = 1_700_000_000_000L
        recorder.start(nowMs = t0)

        // Alternating forward & stale timestamps over 20s wall clock
        for (sec in 1..20) {
            val highTs = t0 + sec * 1_000L
            recorder.onTelemetry(
                RowingTelemetry(
                    timestampMs = highTs,
                    distanceMeters = sec * 4,
                    strokeCount = sec,
                    strokeRateSpm = 26f,
                    watts = 180,
                ),
                autoStart = false,
                isSimulated = false,
                nowMs = highTs,
            )
            // Stale frame 4s behind highTs must not rewind lastActiveClockMs
            val staleTs = highTs - 4_000L
            recorder.onTelemetry(
                RowingTelemetry(
                    timestampMs = staleTs,
                    distanceMeters = sec * 4,
                    strokeCount = sec,
                    strokeRateSpm = 26f,
                    watts = 180,
                ),
                autoStart = false,
                isSimulated = false,
                nowMs = staleTs,
            )
        }

        val recorded = recorder.stop(nowMs = t0 + 20_000L)!!
        assertEquals(20, recorded.workout.durationSeconds)
    }

    @Test
    fun defaultCooldown15Seconds_ignores14SecondsCoastingAfterManualStop() {
        val recorder = WorkoutRecorder()
        val t0 = 1_700_000_000_000L
        recorder.start(nowMs = t0)

        for (sec in 1..6) {
            recorder.onTelemetry(
                RowingTelemetry(
                    timestampMs = t0 + sec * 1_000L,
                    distanceMeters = sec * 5,
                    strokeCount = sec,
                    strokeRateSpm = 26f,
                    watts = 185,
                ),
                autoStart = true,
                isSimulated = false,
                nowMs = t0 + sec * 1_000L,
            )
        }
        val stopTs = t0 + 6_000L
        assertNotNull(recorder.stop(nowMs = stopTs))

        // 14s of flywheel coasting after manual stop must remain Idle under 15s default cooldown
        for (sec in 1..14) {
            val st = recorder.onTelemetry(
                RowingTelemetry(
                    timestampMs = stopTs + sec * 1_000L,
                    distanceMeters = 30 + sec,
                    strokeCount = 6,
                    strokeRateSpm = 12f,
                ),
                autoStart = true,
                isSimulated = false,
                nowMs = stopTs + sec * 1_000L,
            )
            assertTrue("Expected Idle during 15s default cooldown at +${sec}s", st is RecordingState.Idle)
        }

        // Static frame at +15s stays Idle
        val staticAt15 = recorder.onTelemetry(
            RowingTelemetry(
                timestampMs = stopTs + 15_000L,
                distanceMeters = 44,
                strokeCount = 6,
                strokeRateSpm = 0f,
            ),
            autoStart = true,
            isSimulated = false,
            nowMs = stopTs + 15_000L,
        )
        assertTrue(staticAt15 is RecordingState.Idle)
    }
}
