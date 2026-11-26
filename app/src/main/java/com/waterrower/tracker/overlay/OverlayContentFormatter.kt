package com.waterrower.tracker.overlay

import com.waterrower.tracker.model.GhostDelta
import com.waterrower.tracker.model.ProgramProgressState
import com.waterrower.tracker.model.ProgramStep
import com.waterrower.tracker.model.RowingTelemetry
import com.waterrower.tracker.model.WorkoutGoal
import kotlin.math.roundToInt

data class OverlayBarModel(
    val userFraction: Float,
    val ghostFraction: Float? = null,
    val caption: String,
    val isGhostRace: Boolean = false,
    val isCompleted: Boolean = false,
)

/**
 * Pure-Kotlin formatting & projection engine for overlay modes that surface information
 * the WaterRower S4 LCD display cannot show (live ETA, distance projection, pace trend vs.
 * session average/target, live weekly goal %, visual Ghost race bar, and 500m split toasts).
 */
object OverlayContentFormatter {

    private const val MIN_VALID_PACE_SEC = 60
    private const val MAX_VALID_PACE_SEC = 480

    fun sessionAvgPaceSec(distMeters: Int, elapsedSec: Int): Int {
        if (distMeters < 20 || elapsedSec < 5) return 0
        val pace = ((elapsedSec * 500.0) / distMeters.toDouble()).roundToInt()
        return if (pace in MIN_VALID_PACE_SEC..MAX_VALID_PACE_SEC) pace else 0
    }

    fun effectivePaceSec(currentPaceSec: Int, distMeters: Int, elapsedSec: Int): Int {
        if (currentPaceSec in MIN_VALID_PACE_SEC..MAX_VALID_PACE_SEC) return currentPaceSec
        return sessionAvgPaceSec(distMeters, elapsedSec)
    }

    fun estimateEtaSeconds(remainingMeters: Int, paceSecPer500m: Int): Int? {
        if (remainingMeters <= 0 || paceSecPer500m !in MIN_VALID_PACE_SEC..MAX_VALID_PACE_SEC) return null
        return ((remainingMeters.toDouble() * paceSecPer500m.toDouble()) / 500.0)
            .roundToInt()
            .coerceAtLeast(1)
    }

    fun projectTotalMeters(
        currentMeters: Int,
        remainingSeconds: Int,
        paceSecPer500m: Int,
    ): Int? {
        if (remainingSeconds <= 0 || paceSecPer500m !in MIN_VALID_PACE_SEC..MAX_VALID_PACE_SEC) return null
        val addMeters = ((remainingSeconds.toDouble() * 500.0) / paceSecPer500m.toDouble()).roundToInt()
        return (currentMeters + addMeters).coerceAtLeast(currentMeters)
    }

    fun formatPaceDelta(currentPaceSec: Int, referencePaceSec: Int): String? {
        if (currentPaceSec !in MIN_VALID_PACE_SEC..MAX_VALID_PACE_SEC) return null
        if (referencePaceSec !in MIN_VALID_PACE_SEC..MAX_VALID_PACE_SEC) return null
        val diff = currentPaceSec - referencePaceSec
        return when {
            diff <= -2 -> "▲ ${-diff}s"
            diff >= 2 -> "▼ +${diff}s"
            else -> "● im Tempo"
        }
    }

    fun formatSmartLine(
        currentPaceSec: Int,
        distMeters: Int,
        elapsedSec: Int,
        goal: WorkoutGoal,
        program: ProgramProgressState?,
        weeklyGoalMeters: Int,
        savedWeeklyDistanceMeters: Int,
        isRecordingActive: Boolean,
    ): String {
        val avgPace = sessionAvgPaceSec(distMeters, elapsedSec)
        val effPace = effectivePaceSec(currentPaceSec, distMeters, elapsedSec)
        val refPace = goal.targetPaceSecPer500m ?: avgPace
        val trend = formatPaceDelta(currentPaceSec, refPace)
        val liveWeeklyMeters = savedWeeklyDistanceMeters + if (isRecordingActive) distMeters else 0
        val weeklyPart = if (weeklyGoalMeters > 0) {
            val pct = ((liveWeeklyMeters.toDouble() / weeklyGoalMeters.toDouble()) * 100.0)
                .roundToInt()
                .coerceAtLeast(0)
            if (liveWeeklyMeters >= weeklyGoalMeters) "Woche ✓" else "Woche $pct%"
        } else {
            null
        }

        // 1. Active Program (always top priority — S4 cannot show interval/KB steps)
        if (program != null) {
            return when {
                program.isCompleted ->
                    "✓ ${program.plan.titleWithScale}  •  ${RowingTelemetry.formatDistance(distMeters)}"
                program.currentStep is ProgramStep.TimedRest -> {
                    val step = program.currentStep
                    "Pause ${RowingTelemetry.formatDuration(program.restRemainingSeconds)}  •  danach ${step.nextDescription}"
                }
                program.currentStep is ProgramStep.OffRowerStation -> {
                    val step = program.currentStep
                    val nextHint = step.nextRoundLabel?.let { "Ziehen für $it" } ?: "Fertig → Stop & Save"
                    "R${step.roundIndex}/${step.totalRounds}: ${step.exerciseLabel}  •  $nextHint"
                }
                program.currentStep is ProgramStep.RowingDistance -> {
                    val step = program.currentStep
                    val remM = (step.targetMeters - program.stepDistanceMeters).coerceAtLeast(0)
                    val etaPart = estimateEtaSeconds(remM, effPace)
                        ?.let { "  •  ETA ${RowingTelemetry.formatDuration(it)}" }
                        .orEmpty()
                    val trendPart = trend?.let { "  •  $it" }.orEmpty()
                    "R${step.roundIndex}/${step.totalRounds}  •  noch ${RowingTelemetry.formatDistance(remM)}$etaPart$trendPart"
                }
                else -> "Bereit"
            }
        }

        // 2. Distance Goal -> remaining + live ETA (+ pace trend)
        goal.distanceTargetMeters?.let { targetM ->
            val remM = (targetM - distMeters).coerceAtLeast(0)
            if (remM == 0) {
                return buildParts("✓ Ziel erreicht", weeklyPart)
            }
            val eta = estimateEtaSeconds(remM, effPace)?.let { "ETA ${RowingTelemetry.formatDuration(it)}" }
            return buildParts("noch ${RowingTelemetry.formatDistance(remM)}", eta, trend)
        }

        // 3. Duration Goal -> remaining + projected total distance (+ pace trend)
        goal.durationTargetSeconds?.let { targetSec ->
            val remSec = (targetSec - elapsedSec).coerceAtLeast(0)
            if (remSec == 0) {
                return buildParts("✓ Zeit-Ziel erreicht", weeklyPart)
            }
            val proj = projectTotalMeters(distMeters, remSec, effPace)
                ?.let { "≈ ${RowingTelemetry.formatDistance(it)}" }
            return buildParts("noch ${RowingTelemetry.formatDuration(remSec)}", proj, trend)
        }

        // 4. Free rowing -> only S4-exclusive context (avg pace + live trend + live weekly goal %)
        val avgPart = if (avgPace > 0) "Ø ${RowingTelemetry.formatPace(avgPace)}" else null
        val nextSplitRem = 500 - (distMeters % 500)
        val splitPart = if (avgPart == null && distMeters > 0) {
            "Split in ${nextSplitRem} m"
        } else {
            null
        }
        val combined = buildParts(avgPart ?: splitPart, trend, weeklyPart)
        return combined.ifBlank {
            if (isRecordingActive) "Aufnahme läuft" else "Bereit zum Rudern"
        }
    }

    fun computeBarModel(
        currentPaceSec: Int,
        distMeters: Int,
        elapsedSec: Int,
        goal: WorkoutGoal,
        program: ProgramProgressState?,
        ghostDelta: GhostDelta?,
        ghostTotalMeters: Int?,
        weeklyGoalMeters: Int,
        savedWeeklyDistanceMeters: Int,
        isRecordingActive: Boolean,
    ): OverlayBarModel {
        val effPace = effectivePaceSec(currentPaceSec, distMeters, elapsedSec)

        // 1. Active Program Rest / Off-Rower takes priority so user never misses KB swings or rest
        if (program != null && !program.isCompleted) {
            when (val step = program.currentStep) {
                is ProgramStep.TimedRest -> {
                    return OverlayBarModel(
                        userFraction = program.stepProgressFraction,
                        caption = "Pause ${RowingTelemetry.formatDuration(program.restRemainingSeconds)}",
                    )
                }
                is ProgramStep.OffRowerStation -> {
                    return OverlayBarModel(
                        userFraction = program.stepProgressFraction,
                        caption = "R${step.roundIndex}/${step.totalRounds}: ${step.exerciseLabel}",
                    )
                }
                is ProgramStep.RowingDistance -> {
                    val remM = (step.targetMeters - program.stepDistanceMeters).coerceAtLeast(0)
                    val eta = estimateEtaSeconds(remM, effPace)?.let { " • ${RowingTelemetry.formatDuration(it)}" }.orEmpty()
                    return OverlayBarModel(
                        userFraction = program.stepProgressFraction,
                        caption = "R${step.roundIndex}/${step.totalRounds} • ${RowingTelemetry.formatDistance(remM)}$eta",
                    )
                }
            }
        }
        if (program?.isCompleted == true) {
            return OverlayBarModel(
                userFraction = 1f,
                caption = "✓ ${program.plan.preset.title}",
                isCompleted = true,
            )
        }

        // 2. Live Ghost Race Track Bar (user marker vs. ghost marker moving left -> right)
        if (ghostDelta != null && ghostTotalMeters != null && ghostTotalMeters > 0) {
            val scaleMax = maxOf(ghostTotalMeters, distMeters, ghostDelta.ghostDistanceMeters, 1)
            val userFrac = (distMeters.toFloat() / scaleMax.toFloat()).coerceIn(0f, 1f)
            val ghostFrac = (ghostDelta.ghostDistanceMeters.toFloat() / scaleMax.toFloat()).coerceIn(0f, 1f)
            return OverlayBarModel(
                userFraction = userFrac,
                ghostFraction = ghostFrac,
                caption = ghostDelta.formattedCompact,
                isGhostRace = true,
                isCompleted = distMeters >= ghostTotalMeters,
            )
        }

        // 3. Free Distance / Duration Goal Bar
        val goalFrac = goal.progressFraction(distMeters, elapsedSec)
        if (goalFrac != null) {
            val pct = (goalFrac * 100f).roundToInt()
            val completed = goal.isCompleted(distMeters, elapsedSec)
            val caption = if (completed) {
                "✓ Ziel"
            } else {
                val eta = goal.distanceTargetMeters?.let { targetM ->
                    estimateEtaSeconds((targetM - distMeters).coerceAtLeast(0), effPace)
                        ?.let { " • ${RowingTelemetry.formatDuration(it)}" }
                } ?: goal.durationTargetSeconds?.let { targetSec ->
                    val rem = (targetSec - elapsedSec).coerceAtLeast(0)
                    " • ${RowingTelemetry.formatDuration(rem)}"
                }.orEmpty()
                "$pct%$eta"
            }
            return OverlayBarModel(
                userFraction = goalFrac,
                caption = caption,
                isCompleted = completed,
            )
        }

        // 4. Weekly Goal Bar (if configured)
        if (weeklyGoalMeters > 0) {
            val liveWeekly = savedWeeklyDistanceMeters + if (isRecordingActive) distMeters else 0
            val frac = (liveWeekly.toFloat() / weeklyGoalMeters.toFloat()).coerceIn(0f, 1f)
            val pct = (frac * 100f).roundToInt()
            val reached = liveWeekly >= weeklyGoalMeters
            return OverlayBarModel(
                userFraction = frac,
                caption = if (reached) "Woche ✓" else "Woche $pct%",
                isCompleted = reached,
            )
        }

        // 5. Fallback: 500m Split Lap Bar filling every 500 m
        val inSplit = distMeters % 500
        val frac = (inSplit.toFloat() / 500f).coerceIn(0f, 1f)
        return OverlayBarModel(
            userFraction = frac,
            caption = "$inSplit / 500 m",
        )
    }

    fun formatSplitMilestoneToast(
        milestoneMeters: Int,
        splitDurationSec: Int,
        sessionAvgPaceSec: Int,
    ): String {
        val splitPace = splitDurationSec.coerceIn(1, 3600)
        val deltaPart = if (sessionAvgPaceSec in MIN_VALID_PACE_SEC..MAX_VALID_PACE_SEC &&
            splitPace in MIN_VALID_PACE_SEC..MAX_VALID_PACE_SEC
        ) {
            val diff = splitPace - sessionAvgPaceSec
            when {
                diff <= -1 -> " (${diff}s vs Ø)"
                diff >= 1 -> " (+${diff}s vs Ø)"
                else -> " (= Ø)"
            }
        } else {
            ""
        }
        return "✓ ${RowingTelemetry.formatDistance(milestoneMeters)}  •  Split ${RowingTelemetry.formatPace(splitPace)}$deltaPart"
    }

    private fun buildParts(vararg parts: String?): String =
        parts.filterNotNull().filter { it.isNotBlank() }.joinToString("  •  ")
}
