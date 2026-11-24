package com.waterrower.tracker.model

import com.waterrower.tracker.data.WorkoutEntity
import com.waterrower.tracker.data.WorkoutSampleEntity
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

data class Split500m(
    val index: Int,
    val startMeters: Int,
    val endMeters: Int,
    val distanceMeters: Int,
    val durationSeconds: Int,
    val paceSecPer500m: Int,
    val avgStrokeRate: Float,
    val avgWatts: Int,
    val isPartial: Boolean,
)

data class PersonalBests(
    val weeklyDistanceMeters: Int = 0,
    val weeklyWorkoutCount: Int = 0,
    val longestDistanceMeters: Int = 0,
    val best500mPlusPaceSec: Int? = null,
    val best2kPaceSec: Int? = null,
    val best5kPaceSec: Int? = null,
) {
    val hasAnyRecord: Boolean
        get() = longestDistanceMeters > 0 ||
            best500mPlusPaceSec != null ||
            best2kPaceSec != null ||
            best5kPaceSec != null
}

data class WeeklyTrendBucket(
    val weekStartEpochMs: Long,
    val label: String,
    val distanceMeters: Int,
    val workoutCount: Int,
    val isCurrentWeek: Boolean,
)

data class WeeklyTrendSummary(
    val buckets: List<WeeklyTrendBucket> = emptyList(),
    val activeWeekStreak: Int = 0,
    val goalWeekStreak: Int = 0,
)

/**
 * Pure-Kotlin workout analytics computed on the fly from local Room records (zero schema changes).
 */
object WorkoutAnalytics {

    private const val SPLIT_METERS = 500
    private const val MIN_PARTIAL_SPLIT_METERS = 50
    private const val SEVEN_DAYS_MS = 7L * 24L * 60L * 60L * 1_000L

    /**
     * Computes 500m splits (plus an optional final partial split >= 50m) using linear interpolation
     * of crossing times across the workout's 1Hz samples.
     */
    fun computeSplits500m(
        samples: List<WorkoutSampleEntity>,
        totalDistanceMeters: Int,
        totalDurationSeconds: Int,
    ): List<Split500m> {
        if (samples.size < 2 || totalDistanceMeters < MIN_PARTIAL_SPLIT_METERS || totalDurationSeconds <= 0) {
            return emptyList()
        }

        val sorted = samples.sortedBy { it.offsetSeconds }
        val maxSampleDist = sorted.maxOf { it.distanceMeters }
        val effectiveTotalDist = maxOf(totalDistanceMeters, maxSampleDist)
        if (effectiveTotalDist < MIN_PARTIAL_SPLIT_METERS) return emptyList()

        // Build monotone distance->time waypoints starting at (0m, 0s)
        val waypoints = mutableListOf(0 to 0.0)
        for (s in sorted) {
            val d = s.distanceMeters.coerceAtLeast(waypoints.last().first)
            val t = s.offsetSeconds.toDouble().coerceAtLeast(waypoints.last().second)
            if (d > waypoints.last().first) {
                waypoints.add(d to t)
            }
        }
        if (effectiveTotalDist > waypoints.last().first && totalDurationSeconds.toDouble() >= waypoints.last().second) {
            waypoints.add(effectiveTotalDist to totalDurationSeconds.toDouble())
        }
        if (waypoints.size < 2) return emptyList()

        fun timeAtDistance(targetMeters: Int): Double {
            if (targetMeters <= 0) return 0.0
            if (targetMeters >= waypoints.last().first) return waypoints.last().second
            var lo = 0
            var hi = waypoints.lastIndex
            while (lo + 1 < hi) {
                val mid = (lo + hi) ushr 1
                if (waypoints[mid].first <= targetMeters) lo = mid else hi = mid
            }
            val (d0, t0) = waypoints[lo]
            val (d1, t1) = waypoints[hi]
            val dd = (d1 - d0).coerceAtLeast(1)
            val frac = ((targetMeters - d0).toDouble() / dd.toDouble()).coerceIn(0.0, 1.0)
            return t0 + (t1 - t0) * frac
        }

        val splits = mutableListOf<Split500m>()
        var startM = 0
        var splitIndex = 1

        while (startM < effectiveTotalDist) {
            val nextBoundary = startM + SPLIT_METERS
            val endM = minOf(nextBoundary, effectiveTotalDist)
            val segDist = endM - startM
            val isPartial = segDist < SPLIT_METERS
            if (isPartial && segDist < MIN_PARTIAL_SPLIT_METERS) break

            val tStart = timeAtDistance(startM)
            val tEnd = timeAtDistance(endM)
            val segSec = (tEnd - tStart).roundToInt().coerceAtLeast(1)
            val paceSec = ((segSec * 500.0) / segDist).roundToInt().coerceAtLeast(1)

            val segSamples = sorted.filter { it.distanceMeters in (startM + 1)..endM }
            val spmValues = segSamples.map { it.strokeRateSpm }.filter { it > 0f }
            val wattValues = segSamples.map { it.watts }.filter { it > 0 }

            val avgSpm = if (spmValues.isNotEmpty()) spmValues.average().toFloat() else 0f
            val avgWatts = if (wattValues.isNotEmpty()) wattValues.average().roundToInt() else 0

            splits.add(
                Split500m(
                    index = splitIndex,
                    startMeters = startM,
                    endMeters = endM,
                    distanceMeters = segDist,
                    durationSeconds = segSec,
                    paceSecPer500m = paceSec,
                    avgStrokeRate = avgSpm,
                    avgWatts = avgWatts,
                    isPartial = isPartial,
                ),
            )

            startM = endM
            splitIndex += 1
        }

        return splits
    }

    /**
     * Computes weekly volume (trailing 7 days) and personal bests over the supplied workouts list.
     */
    fun computePersonalBests(
        workouts: List<WorkoutEntity>,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): PersonalBests {
        if (workouts.isEmpty()) return PersonalBests()

        val weekCutoff = nowEpochMs - SEVEN_DAYS_MS
        val recent = workouts.filter { it.startedAtEpochMs >= weekCutoff }

        val longest = workouts.maxOfOrNull { it.totalDistanceMeters } ?: 0
        val best500 = workouts
            .filter { it.totalDistanceMeters >= 500 && it.avgPaceSecPer500m in 60..600 }
            .minOfOrNull { it.avgPaceSecPer500m }
        val best2k = workouts
            .filter { it.totalDistanceMeters >= 2_000 && it.avgPaceSecPer500m in 60..600 }
            .minOfOrNull { it.avgPaceSecPer500m }
        val best5k = workouts
            .filter { it.totalDistanceMeters >= 5_000 && it.avgPaceSecPer500m in 60..600 }
            .minOfOrNull { it.avgPaceSecPer500m }

        return PersonalBests(
            weeklyDistanceMeters = recent.sumOf { it.totalDistanceMeters },
            weeklyWorkoutCount = recent.size,
            longestDistanceMeters = longest,
            best500mPlusPaceSec = best500,
            best2kPaceSec = best2k,
            best5kPaceSec = best5k,
        )
    }

    /**
     * Computes an N-week calendar-week (Monday-Sunday) distance trend plus active & weekly-goal
     * streaks. If the current week has not met the threshold yet, the streak still counts
     * consecutive weeks ending with the previous week so streaks never prematurely drop on Monday.
     */
    fun computeWeeklyTrend(
        workouts: List<WorkoutEntity>,
        weeklyGoalMeters: Int = 0,
        weeks: Int = 8,
        nowEpochMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): WeeklyTrendSummary {
        val count = weeks.coerceAtLeast(1)
        val currentWeekStart = startOfIsoWeekMs(nowEpochMs, timeZone)

        // Group all workouts by their ISO Monday week start
        val byWeek = HashMap<Long, Pair<Int, Int>>() // weekStart -> (meters, count)
        for (w in workouts) {
            val ws = startOfIsoWeekMs(w.startedAtEpochMs, timeZone)
            val prev = byWeek[ws]
            byWeek[ws] = if (prev == null) {
                w.totalDistanceMeters to 1
            } else {
                (prev.first + w.totalDistanceMeters) to (prev.second + 1)
            }
        }

        // Build chronological list of last N weeks ending with currentWeekStart
        val cal = Calendar.getInstance(timeZone, Locale.GERMANY).apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 4
            timeInMillis = currentWeekStart
            add(Calendar.WEEK_OF_YEAR, -(count - 1))
        }
        val buckets = ArrayList<WeeklyTrendBucket>(count)
        for (i in 0 until count) {
            val ws = cal.timeInMillis
            val kw = cal.get(Calendar.WEEK_OF_YEAR)
            val stats = byWeek[ws]
            buckets += WeeklyTrendBucket(
                weekStartEpochMs = ws,
                label = "KW$kw",
                distanceMeters = stats?.first ?: 0,
                workoutCount = stats?.second ?: 0,
                isCurrentWeek = i == count - 1,
            )
            cal.add(Calendar.WEEK_OF_YEAR, 1)
        }

        fun countStreak(predicate: (Pair<Int, Int>?) -> Boolean): Int {
            val c = Calendar.getInstance(timeZone, Locale.GERMANY).apply {
                firstDayOfWeek = Calendar.MONDAY
                minimalDaysInFirstWeek = 4
                timeInMillis = currentWeekStart
            }
            // If current week hasn't satisfied predicate yet, start checking from last week
            if (!predicate(byWeek[c.timeInMillis])) {
                c.add(Calendar.WEEK_OF_YEAR, -1)
            }
            var streak = 0
            while (streak < 520 && predicate(byWeek[c.timeInMillis])) {
                streak += 1
                c.add(Calendar.WEEK_OF_YEAR, -1)
            }
            return streak
        }

        val activeStreak = countStreak { it != null && it.second > 0 }
        val goalStreak = if (weeklyGoalMeters > 0) {
            countStreak { it != null && it.first >= weeklyGoalMeters }
        } else {
            0
        }

        return WeeklyTrendSummary(
            buckets = buckets,
            activeWeekStreak = activeStreak,
            goalWeekStreak = goalStreak,
        )
    }

    private fun startOfIsoWeekMs(epochMs: Long, timeZone: TimeZone): Long {
        val cal = Calendar.getInstance(timeZone, Locale.GERMANY).apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 4
            timeInMillis = epochMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val daysFromMonday = (dayOfWeek - Calendar.MONDAY + 7) % 7
        cal.add(Calendar.DAY_OF_MONTH, -daysFromMonday)
        return cal.timeInMillis
    }
}
