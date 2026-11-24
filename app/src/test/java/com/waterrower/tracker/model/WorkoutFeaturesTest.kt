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
}
