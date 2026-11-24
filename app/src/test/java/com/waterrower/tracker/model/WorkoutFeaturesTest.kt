package com.waterrower.tracker.model

import com.waterrower.tracker.data.WorkoutEntity
import com.waterrower.tracker.data.WorkoutSampleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutFeaturesTest {

    @Test
    fun workoutGoal_computesProgressCompletionRemainingAndPaceZone() {
        val distGoal = WorkoutGoal(distanceTargetMeters = 2000, targetPaceSecPer500m = 125)
        assertTrue(distGoal.hasProgressTarget)
        assertEquals(0.5f, distGoal.progressFraction(1000, 250)!!, 0.001f)
        assertFalse(distGoal.isCompleted(1999, 500))
        assertTrue(distGoal.isCompleted(2000, 500))
        assertEquals("Ziel erreicht", distGoal.remainingSummary(2050, 510))
        assertTrue(distGoal.remainingSummary(1250, 300)!!.contains("750"))

        // Target pace = 125s (+/- 3s -> 122..128 is ON_TARGET)
        assertEquals(PaceZone.FASTER, distGoal.evaluatePaceZone(120))
        assertEquals(PaceZone.ON_TARGET, distGoal.evaluatePaceZone(122))
        assertEquals(PaceZone.ON_TARGET, distGoal.evaluatePaceZone(128))
        assertEquals(PaceZone.SLOWER, distGoal.evaluatePaceZone(132))
        assertEquals(PaceZone.NONE, distGoal.evaluatePaceZone(0))

        val durGoal = WorkoutGoal(durationTargetSeconds = 1200)
        assertEquals(0.25f, durGoal.progressFraction(700, 300)!!, 0.001f)
        assertFalse(durGoal.isCompleted(5000, 1199))
        assertTrue(durGoal.isCompleted(100, 1200))
        assertEquals("noch 15:00", durGoal.remainingSummary(700, 300))
    }

    @Test
    fun ghostRacer_interpolatesLeadDeficitAndFinishCleanly() {
        val workout = WorkoutEntity(
            id = "ghost-1",
            startedAtEpochMs = 1_700_000_000_000L,
            endedAtEpochMs = 1_700_000_100_000L,
            durationSeconds = 100,
            totalDistanceMeters = 400,
            avgPaceSecPer500m = 125,
            avgStrokeRate = 24f,
            avgWatts = 175,
            totalStrokes = 40,
            totalCalories = 25,
        )
        val samples = listOf(
            WorkoutSampleEntity(workoutId = "ghost-1", offsetSeconds = 50, distanceMeters = 200, paceSecPer500m = 125, strokeRateSpm = 24f, watts = 175),
            WorkoutSampleEntity(workoutId = "ghost-1", offsetSeconds = 100, distanceMeters = 400, paceSecPer500m = 125, strokeRateSpm = 24f, watts = 175),
        )
        val ref = GhostRacer.fromWorkout(workout, samples, "Ref")

        // At 25s, ghost is linearly at 100m
        val ahead = GhostRacer.evaluate(ref, elapsedSeconds = 25, currentDistanceMeters = 118)!!
        assertEquals(18, ahead.deltaMeters)
        assertEquals("+18 m", ahead.formattedCompact)
        assertFalse(ahead.ghostFinished)

        val behind = GhostRacer.evaluate(ref, elapsedSeconds = 75, currentDistanceMeters = 288)!!
        assertEquals(-12, behind.deltaMeters)
        assertEquals("-12 m", behind.formattedCompact)
        assertFalse(behind.ghostFinished)

        // Past ghost duration -> clamped to totalDistanceMeters and flagged finished
        val pastEnd = GhostRacer.evaluate(ref, elapsedSeconds = 120, currentDistanceMeters = 420)!!
        assertEquals(20, pastEnd.deltaMeters)
        assertTrue(pastEnd.ghostFinished)
    }

    @Test
    fun workoutAnalytics_computes500mSplitsAndPartialFinalSplit() {
        // Steady 4 m/s -> 500m every 125s -> 1250m in 312.5s (~313s)
        val samples = (1..313).map { sec ->
            WorkoutSampleEntity(
                workoutId = "w1",
                offsetSeconds = sec,
                distanceMeters = (sec * 4).coerceAtMost(1250),
                paceSecPer500m = 125,
                strokeRateSpm = 25f,
                watts = 180,
            )
        }

        val splits = WorkoutAnalytics.computeSplits500m(
            samples = samples,
            totalDistanceMeters = 1250,
            totalDurationSeconds = 313,
        )

        assertEquals(3, splits.size)
        assertEquals(500, splits[0].endMeters)
        assertFalse(splits[0].isPartial)
        assertEquals(125, splits[0].paceSecPer500m)
        assertEquals(25f, splits[0].avgStrokeRate, 0.1f)
        assertEquals(180, splits[0].avgWatts)

        assertEquals(1000, splits[1].endMeters)
        assertFalse(splits[1].isPartial)
        assertEquals(125, splits[1].paceSecPer500m)

        assertEquals(1250, splits[2].endMeters)
        assertEquals(250, splits[2].distanceMeters)
        assertTrue(splits[2].isPartial)
        assertTrue(splits[2].paceSecPer500m in 124..126)
    }

    @Test
    fun workoutAnalytics_computesWeeklyVolumeAndPersonalBests() {
        val now = 1_750_000_000_000L
        val dayMs = 24L * 60L * 60L * 1_000L

        val workouts = listOf(
            // 10 days ago: 5000m @ 2:06 (126s)
            WorkoutEntity(
                id = "old-5k",
                startedAtEpochMs = now - 10 * dayMs,
                endedAtEpochMs = now - 10 * dayMs + 1_260_000L,
                durationSeconds = 1260,
                totalDistanceMeters = 5000,
                avgPaceSecPer500m = 126,
                avgStrokeRate = 24f,
                avgWatts = 175,
                totalStrokes = 500,
                totalCalories = 260,
            ),
            // 2 days ago: 2000m @ 1:58 (118s)
            WorkoutEntity(
                id = "recent-2k",
                startedAtEpochMs = now - 2 * dayMs,
                endedAtEpochMs = now - 2 * dayMs + 472_000L,
                durationSeconds = 472,
                totalDistanceMeters = 2000,
                avgPaceSecPer500m = 118,
                avgStrokeRate = 27f,
                avgWatts = 215,
                totalStrokes = 210,
                totalCalories = 110,
            ),
            // 1 day ago: 1000m @ 1:55 (115s)
            WorkoutEntity(
                id = "recent-1k",
                startedAtEpochMs = now - 1 * dayMs,
                endedAtEpochMs = now - 1 * dayMs + 230_000L,
                durationSeconds = 230,
                totalDistanceMeters = 1000,
                avgPaceSecPer500m = 115,
                avgStrokeRate = 28f,
                avgWatts = 230,
                totalStrokes = 105,
                totalCalories = 60,
            ),
        )

        val bests = WorkoutAnalytics.computePersonalBests(workouts, nowEpochMs = now)
        assertEquals(3000, bests.weeklyDistanceMeters)
        assertEquals(2, bests.weeklyWorkoutCount)
        assertEquals(5000, bests.longestDistanceMeters)
        assertEquals(115, bests.best500mPlusPaceSec)
        assertEquals(118, bests.best2kPaceSec)
        assertEquals(126, bests.best5kPaceSec)

        val empty = WorkoutAnalytics.computePersonalBests(emptyList(), nowEpochMs = now)
        assertFalse(empty.hasAnyRecord)
        assertNull(empty.best2kPaceSec)
    }

    @Test
    fun programPreset_scalesRoundsAndMetersAcrossShortMediumLong() {
        ProgramPreset.entries.forEach { preset ->
            val shortPlan = preset.buildPlan(ProgramScale.SHORT)
            val mediumPlan = preset.buildPlan(ProgramScale.MEDIUM)
            val longPlan = preset.buildPlan(ProgramScale.LONG)

            assertTrue(shortPlan.totalTargetMeters < mediumPlan.totalTargetMeters)
            assertTrue(mediumPlan.totalTargetMeters < longPlan.totalTargetMeters)
            assertTrue(shortPlan.steps.size < mediumPlan.steps.size)
            assertTrue(mediumPlan.steps.size < longPlan.steps.size)
            assertTrue(shortPlan.estimatedMinutes > 0)
        }

        val kbMedium = ProgramPreset.ROW_KB_SWING.buildPlan(ProgramScale.MEDIUM)
        assertEquals(3000, kbMedium.totalTargetMeters)
        assertEquals(12, kbMedium.steps.size) // 6 rowing legs + 6 KB stations
        assertEquals(6, kbMedium.steps.count { it is ProgramStep.OffRowerStation })

        val pyramidShort = ProgramPreset.PYRAMID.buildPlan(ProgramScale.SHORT)
        assertEquals(2250, pyramidShort.totalTargetMeters) // 250+500+750+500+250
        assertEquals(9, pyramidShort.steps.size) // 5 rowing + 4 rests
    }

    @Test
    fun programRunner_drivesIntervalRestAndCompletionLifecycle() {
        val plan = ProgramPreset.INTERVALS_500.buildPlan(ProgramScale.SHORT) // 4x 500m + 90s rest
        val runner = ProgramRunner(flywheelGraceMs = 6_000L)
        var t = 1_000_000L

        val initial = runner.setPlan(plan, currentDistanceMeters = 0, nowMs = t)!!
        assertTrue(initial.currentStep is ProgramStep.RowingDistance)
        assertEquals(0f, initial.stepProgressFraction, 0.001f)

        // Halfway through Round 1
        t += 60_000L
        val mid = runner.onTick(nowMs = t, totalDistanceMeters = 250, strokeRateSpm = 26f, isRecordingActive = true)
        assertFalse(mid.playTone)
        assertEquals(0.5f, mid.state!!.stepProgressFraction, 0.001f)

        // Finish Round 1 -> transitions to TimedRest (90s) with cue tone
        t += 60_000L
        val enterRest = runner.onTick(nowMs = t, totalDistanceMeters = 500, strokeRateSpm = 26f, isRecordingActive = true)
        assertTrue(enterRest.playTone)
        assertNotNull(enterRest.cueMessage)
        assertTrue(enterRest.state!!.currentStep is ProgramStep.TimedRest)
        assertEquals(90, enterRest.state!!.restRemainingSeconds)
        assertEquals(1, enterRest.state!!.completedRowingRounds)

        // Flywheel coasts for 3 seconds (within 6s grace) -> must NOT skip rest
        t += 3_000L
        val coast = runner.onTick(nowMs = t, totalDistanceMeters = 512, strokeRateSpm = 14f, isRecordingActive = true)
        assertFalse(coast.playTone)
        assertTrue(coast.state!!.currentStep is ProgramStep.TimedRest)
        assertEquals(87, coast.state!!.restRemainingSeconds)

        // Rest timer finishes at 90s -> transitions cleanly to Round 2
        t += 87_000L
        val startR2 = runner.onTick(nowMs = t, totalDistanceMeters = 512, strokeRateSpm = 0f, isRecordingActive = true)
        assertTrue(startR2.playTone)
        val r2Step = startR2.state!!.currentStep as ProgramStep.RowingDistance
        assertEquals(2, r2Step.roundIndex)
        assertEquals(0, startR2.state!!.stepDistanceMeters)
        assertEquals("500m Intervalle (Kurz, 1/4 Runden)", startR2.state!!.storageSummary())
    }

    @Test
    fun programRunner_hybridOffRowerStationRespectsFlywheelGraceAndAutoAdvancesOnNextPull() {
        val plan = ProgramPreset.ROW_KB_SWING.buildPlan(ProgramScale.SHORT) // 4 rounds
        val runner = ProgramRunner(flywheelGraceMs = 6_000L)
        var t = 2_000_000L
        runner.setPlan(plan, currentDistanceMeters = 0, nowMs = t)

        // Complete Round 1 rowing (500m) -> enters OffRowerStation (15x 16 kg KB Swings)
        t += 120_000L
        val enterKb = runner.onTick(nowMs = t, totalDistanceMeters = 500, strokeRateSpm = 25f, isRecordingActive = true)
        assertTrue(enterKb.playTone)
        assertTrue(enterKb.state!!.currentStep is ProgramStep.OffRowerStation)

        // Within 6s grace window, flywheel still coasts +8m -> MUST stay on KB station
        t += 4_000L
        val stillKb = runner.onTick(nowMs = t, totalDistanceMeters = 508, strokeRateSpm = 10f, isRecordingActive = true)
        assertTrue(stillKb.state!!.currentStep is ProgramStep.OffRowerStation)

        // Flywheel comes to rest while user does KB swings (20s later)
        t += 20_000L
        val doingSwings = runner.onTick(nowMs = t, totalDistanceMeters = 508, strokeRateSpm = 0f, isRecordingActive = true)
        assertTrue(doingSwings.state!!.currentStep is ProgramStep.OffRowerStation)

        // User sits back down and pulls +6m -> auto-advances to Round 2 rowing!
        t += 3_000L
        val backOnRower = runner.onTick(nowMs = t, totalDistanceMeters = 514, strokeRateSpm = 22f, isRecordingActive = true)
        assertTrue(backOnRower.playTone)
        val r2 = backOnRower.state!!.currentStep as ProgramStep.RowingDistance
        assertEquals(2, r2.roundIndex)

        // Advance manually through the remaining steps to verify completion
        var latest = backOnRower.state!!
        while (!latest.isCompleted) {
            val res = runner.advanceStepManually(nowMs = t, totalDistanceMeters = 2000)
            latest = res.state!!
        }
        assertTrue(latest.isCompleted)
        assertEquals(1f, latest.stepProgressFraction, 0.001f)
        assertEquals("Row & KB Swing (Kurz)", latest.storageSummary())
    }

    @Test
    fun overlaySettings_parsesStylesAndEnablesAutoConnectAndOverlayByDefault() {
        assertEquals(com.waterrower.tracker.data.OverlayStyle.STANDARD, com.waterrower.tracker.data.OverlayStyle.fromId("standard"))
        assertEquals(com.waterrower.tracker.data.OverlayStyle.CINEMA, com.waterrower.tracker.data.OverlayStyle.fromId("cinema"))
        assertEquals(com.waterrower.tracker.data.OverlayStyle.TRANSPARENT, com.waterrower.tracker.data.OverlayStyle.fromId("transparent"))
        assertEquals(com.waterrower.tracker.data.OverlayStyle.STANDARD, com.waterrower.tracker.data.OverlayStyle.fromId("unknown"))
        assertEquals(com.waterrower.tracker.data.OverlayStyle.STANDARD, com.waterrower.tracker.data.OverlayStyle.fromId(null))

        val defaults = com.waterrower.tracker.data.OverlaySettings()
        assertEquals(com.waterrower.tracker.data.OverlayStyle.STANDARD, defaults.overlayStyle)
        assertFalse(defaults.compactOverlayMode)
        assertFalse(defaults.overlayPositionLocked)
        assertTrue(defaults.autoReconnectOnStart)
        assertTrue(defaults.autoShowOverlayOnConnect)
        assertEquals(0, defaults.weeklyGoalMeters)
    }
}
