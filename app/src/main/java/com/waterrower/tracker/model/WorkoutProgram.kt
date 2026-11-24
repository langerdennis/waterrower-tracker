package com.waterrower.tracker.model

import kotlin.math.roundToInt

enum class ProgramScale(val label: String) {
    SHORT("Kurz"),
    MEDIUM("Mittel"),
    LONG("Lang"),
}

enum class ProgramPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val isHybrid: Boolean = false,
) {
    INTERVALS_500(
        id = "intervals_500",
        title = "500m Intervalle",
        subtitle = "500 m Rudern + 90 s Pause",
    ),
    INTERVALS_1000(
        id = "intervals_1000",
        title = "1.000m Intervalle",
        subtitle = "1.000 m Rudern + 2 Min. Pause",
    ),
    PYRAMID(
        id = "pyramid",
        title = "Pyramide",
        subtitle = "Steigende & fallende Distanzen + 60 s Pause",
    ),
    ROW_KB_SWING(
        id = "row_kb_swing",
        title = "Row & KB Swing",
        subtitle = "500 m Rudern + 15× 16 kg KB Swings",
        isHybrid = true,
    ),
    ROW_FULL_BODY(
        id = "row_full_body",
        title = "Row & Ganzkörper",
        subtitle = "500 m Rudern + 12× 16 kg Goblet Squats & 10× Liegestütze",
        isHybrid = true,
    ),
    ;

    fun buildPlan(scale: ProgramScale): ActiveProgramPlan {
        val steps = mutableListOf<ProgramStep>()
        val estMinutes: Int
        val totalMeters: Int
        val detailSummary: String

        when (this) {
            INTERVALS_500 -> {
                val rounds = when (scale) {
                    ProgramScale.SHORT -> 4
                    ProgramScale.MEDIUM -> 6
                    ProgramScale.LONG -> 8
                }
                totalMeters = rounds * 500
                estMinutes = rounds * 4
                detailSummary = "$rounds× 500 m (90 s Pause)"
                for (r in 1..rounds) {
                    steps += ProgramStep.RowingDistance(r, rounds, 500)
                    if (r < rounds) {
                        steps += ProgramStep.TimedRest(r, rounds, 90, "R${r + 1}/$rounds (500 m)")
                    }
                }
            }
            INTERVALS_1000 -> {
                val rounds = when (scale) {
                    ProgramScale.SHORT -> 3
                    ProgramScale.MEDIUM -> 4
                    ProgramScale.LONG -> 6
                }
                totalMeters = rounds * 1000
                estMinutes = rounds * 6
                detailSummary = "$rounds× 1.000 m (2 Min. Pause)"
                for (r in 1..rounds) {
                    steps += ProgramStep.RowingDistance(r, rounds, 1000)
                    if (r < rounds) {
                        steps += ProgramStep.TimedRest(r, rounds, 120, "R${r + 1}/$rounds (1.000 m)")
                    }
                }
            }
            PYRAMID -> {
                val distances = when (scale) {
                    ProgramScale.SHORT -> listOf(250, 500, 750, 500, 250)
                    ProgramScale.MEDIUM -> listOf(250, 500, 750, 1000, 750, 500, 250)
                    ProgramScale.LONG -> listOf(250, 500, 750, 1000, 1250, 1000, 750, 500, 250)
                }
                totalMeters = distances.sum()
                estMinutes = ((totalMeters / 500.0) * 2.2 + (distances.size - 1)).roundToInt()
                detailSummary = "${distances.size} Blöcke • ${RowingTelemetry.formatDistance(totalMeters)}"
                distances.forEachIndexed { idx, meters ->
                    val r = idx + 1
                    steps += ProgramStep.RowingDistance(r, distances.size, meters)
                    if (idx < distances.lastIndex) {
                        val nextM = distances[idx + 1]
                        steps += ProgramStep.TimedRest(
                            afterRound = r,
                            totalRounds = distances.size,
                            durationSeconds = 60,
                            nextDescription = "R${r + 1}/${distances.size} (${RowingTelemetry.formatDistance(nextM)})",
                        )
                    }
                }
            }
            ROW_KB_SWING -> {
                val rounds = when (scale) {
                    ProgramScale.SHORT -> 4
                    ProgramScale.MEDIUM -> 6
                    ProgramScale.LONG -> 8
                }
                totalMeters = rounds * 500
                estMinutes = rounds * 4
                detailSummary = "$rounds Runden • ${RowingTelemetry.formatDistance(totalMeters)} + ${rounds * 15}× 16 kg Swings"
                for (r in 1..rounds) {
                    steps += ProgramStep.RowingDistance(r, rounds, 500)
                    steps += ProgramStep.OffRowerStation(
                        roundIndex = r,
                        totalRounds = rounds,
                        exerciseLabel = "15× 16 kg KB Swings",
                        nextRoundLabel = if (r < rounds) "R${r + 1}/$rounds" else null,
                    )
                }
            }
            ROW_FULL_BODY -> {
                val rounds = when (scale) {
                    ProgramScale.SHORT -> 3
                    ProgramScale.MEDIUM -> 5
                    ProgramScale.LONG -> 7
                }
                totalMeters = rounds * 500
                estMinutes = rounds * 5
                detailSummary = "$rounds Runden • ${RowingTelemetry.formatDistance(totalMeters)} + Squats & Liegestütze"
                for (r in 1..rounds) {
                    steps += ProgramStep.RowingDistance(r, rounds, 500)
                    steps += ProgramStep.OffRowerStation(
                        roundIndex = r,
                        totalRounds = rounds,
                        exerciseLabel = "12× 16 kg Squats + 10× Liegestütze",
                        nextRoundLabel = if (r < rounds) "R${r + 1}/$rounds" else null,
                    )
                }
            }
        }

        return ActiveProgramPlan(
            preset = this,
            scale = scale,
            steps = steps,
            totalTargetMeters = totalMeters,
            estimatedMinutes = estMinutes,
            detailSummary = detailSummary,
        )
    }
}

sealed interface ProgramStep {
    val roundIndex: Int
    val totalRounds: Int

    data class RowingDistance(
        override val roundIndex: Int,
        override val totalRounds: Int,
        val targetMeters: Int,
    ) : ProgramStep

    data class TimedRest(
        val afterRound: Int,
        override val totalRounds: Int,
        val durationSeconds: Int,
        val nextDescription: String,
    ) : ProgramStep {
        override val roundIndex: Int get() = afterRound
    }

    data class OffRowerStation(
        override val roundIndex: Int,
        override val totalRounds: Int,
        val exerciseLabel: String,
        val nextRoundLabel: String?,
    ) : ProgramStep
}

data class ActiveProgramPlan(
    val preset: ProgramPreset,
    val scale: ProgramScale,
    val steps: List<ProgramStep>,
    val totalTargetMeters: Int,
    val estimatedMinutes: Int,
    val detailSummary: String,
) {
    val titleWithScale: String get() = "${preset.title} (${scale.label})"
}

data class ProgramProgressState(
    val plan: ActiveProgramPlan,
    val stepIndex: Int,
    val currentStep: ProgramStep,
    val isCompleted: Boolean,
    val stepProgressFraction: Float,
    val stepDistanceMeters: Int = 0,
    val restRemainingSeconds: Int = 0,
    val completedRowingRounds: Int = 0,
) {
    fun storageSummary(): String {
        val totalRounds = plan.steps.count { it is ProgramStep.RowingDistance }
        return if (isCompleted) {
            "${plan.preset.title} (${plan.scale.label})"
        } else {
            "${plan.preset.title} (${plan.scale.label}, $completedRowingRounds/$totalRounds Runden)"
        }
    }
}

data class ProgramTickResult(
    val state: ProgramProgressState?,
    val cueMessage: String? = null,
    val playTone: Boolean = false,
)

/**
 * Pure-Kotlin state machine driving interval & hybrid Row+KB/Mat programs with hands-free
 * transitions (auto-completing rowing legs by distance, counting down timed rests, and
 * auto-advancing from off-rower stations on the user's next rowing stroke after flywheel spin-down).
 */
class ProgramRunner(
    private val flywheelGraceMs: Long = DEFAULT_FLYWHEEL_GRACE_MS,
) {
    private var plan: ActiveProgramPlan? = null
    private var stepIndex: Int = 0
    private var isCompleted: Boolean = false
    private var stepStartDistance: Int? = null
    private var stepStartTimeMs: Long = 0L
    private var hasSeenFlywheelStop: Boolean = false
    private var offRowerBaselineDistance: Int = 0
    private var completedRowingRounds: Int = 0

    fun setPlan(
        newPlan: ActiveProgramPlan?,
        currentDistanceMeters: Int = 0,
        nowMs: Long = System.currentTimeMillis(),
    ): ProgramProgressState? {
        plan = newPlan
        stepIndex = 0
        isCompleted = false
        completedRowingRounds = 0
        if (newPlan == null || newPlan.steps.isEmpty()) {
            stepStartDistance = null
            stepStartTimeMs = 0L
            return null
        }
        stepStartDistance = currentDistanceMeters
        stepStartTimeMs = nowMs
        hasSeenFlywheelStop = false
        offRowerBaselineDistance = currentDistanceMeters
        return buildState(currentDistanceMeters, nowMs)
    }

    fun currentState(
        currentDistanceMeters: Int = 0,
        nowMs: Long = System.currentTimeMillis(),
    ): ProgramProgressState? {
        if (plan == null) return null
        return buildState(currentDistanceMeters, nowMs)
    }

    fun onTick(
        nowMs: Long,
        totalDistanceMeters: Int,
        strokeRateSpm: Float,
        isRecordingActive: Boolean,
    ): ProgramTickResult {
        val activePlan = plan ?: return ProgramTickResult(null)
        if (isCompleted || !isRecordingActive) {
            return ProgramTickResult(buildState(totalDistanceMeters, nowMs))
        }

        val step = activePlan.steps.getOrNull(stepIndex) ?: return ProgramTickResult(null)
        if (stepStartDistance == null) {
            stepStartDistance = totalDistanceMeters
            stepStartTimeMs = nowMs
            offRowerBaselineDistance = totalDistanceMeters
        }

        // If distance reset lower than anchor (new session started), rebase cleanly
        if (totalDistanceMeters < stepStartDistance!!) {
            stepStartDistance = totalDistanceMeters
            offRowerBaselineDistance = totalDistanceMeters
        }

        return when (step) {
            is ProgramStep.RowingDistance -> {
                val segDist = (totalDistanceMeters - stepStartDistance!!).coerceAtLeast(0)
                if (segDist >= step.targetMeters) {
                    completedRowingRounds += 1
                    advanceToNextStep(activePlan, totalDistanceMeters, nowMs)
                } else {
                    ProgramTickResult(buildState(totalDistanceMeters, nowMs))
                }
            }
            is ProgramStep.TimedRest -> {
                val elapsedMs = (nowMs - stepStartTimeMs).coerceAtLeast(0L)
                val remainingSec = (step.durationSeconds - (elapsedMs / 1000L).toInt()).coerceAtLeast(0)
                val pastGrace = elapsedMs >= flywheelGraceMs
                if (!pastGrace || strokeRateSpm <= 0f) {
                    if (strokeRateSpm <= 0f) hasSeenFlywheelStop = true
                    offRowerBaselineDistance = totalDistanceMeters
                }
                val pulledEarly = pastGrace && hasSeenFlywheelStop &&
                    strokeRateSpm > 0f && totalDistanceMeters >= offRowerBaselineDistance + RESUME_PULL_METERS

                if (remainingSec == 0 || pulledEarly) {
                    advanceToNextStep(activePlan, totalDistanceMeters, nowMs)
                } else {
                    ProgramTickResult(buildState(totalDistanceMeters, nowMs))
                }
            }
            is ProgramStep.OffRowerStation -> {
                val elapsedMs = (nowMs - stepStartTimeMs).coerceAtLeast(0L)
                val pastGrace = elapsedMs >= flywheelGraceMs
                if (!pastGrace || strokeRateSpm <= 0f) {
                    if (strokeRateSpm <= 0f) hasSeenFlywheelStop = true
                    offRowerBaselineDistance = totalDistanceMeters
                }
                // Only auto-advance on next stroke if there IS another rowing round following
                val hasNextStep = stepIndex + 1 < activePlan.steps.size
                val pulledForNextRound = hasNextStep &&
                    pastGrace &&
                    hasSeenFlywheelStop &&
                    strokeRateSpm > 0f &&
                    totalDistanceMeters >= offRowerBaselineDistance + RESUME_PULL_METERS

                if (pulledForNextRound) {
                    advanceToNextStep(activePlan, totalDistanceMeters, nowMs)
                } else {
                    ProgramTickResult(buildState(totalDistanceMeters, nowMs))
                }
            }
        }
    }

    fun advanceStepManually(
        nowMs: Long = System.currentTimeMillis(),
        totalDistanceMeters: Int = 0,
    ): ProgramTickResult {
        val activePlan = plan ?: return ProgramTickResult(null)
        if (isCompleted) return ProgramTickResult(buildState(totalDistanceMeters, nowMs))
        val current = activePlan.steps.getOrNull(stepIndex)
        if (current is ProgramStep.RowingDistance) {
            completedRowingRounds += 1
        }
        return advanceToNextStep(activePlan, totalDistanceMeters, nowMs)
    }

    fun resetForNewSession(
        currentDistanceMeters: Int = 0,
        nowMs: Long = System.currentTimeMillis(),
    ): ProgramProgressState? {
        val activePlan = plan ?: return null
        return setPlan(activePlan, currentDistanceMeters, nowMs)
    }

    private fun advanceToNextStep(
        activePlan: ActiveProgramPlan,
        currentDistanceMeters: Int,
        nowMs: Long,
    ): ProgramTickResult {
        val nextIndex = stepIndex + 1
        if (nextIndex >= activePlan.steps.size) {
            isCompleted = true
            return ProgramTickResult(
                state = buildState(currentDistanceMeters, nowMs),
                cueMessage = "Programm geschafft: ${activePlan.titleWithScale}!",
                playTone = true,
            )
        }
        stepIndex = nextIndex
        stepStartDistance = currentDistanceMeters
        stepStartTimeMs = nowMs
        hasSeenFlywheelStop = false
        offRowerBaselineDistance = currentDistanceMeters

        val nextStep = activePlan.steps[nextIndex]
        val msg = when (nextStep) {
            is ProgramStep.RowingDistance ->
                "Runde ${nextStep.roundIndex}/${nextStep.totalRounds}: ${RowingTelemetry.formatDistance(nextStep.targetMeters)} rudern"
            is ProgramStep.TimedRest ->
                "Pause ${RowingTelemetry.formatDuration(nextStep.durationSeconds)} • danach ${nextStep.nextDescription}"
            is ProgramStep.OffRowerStation ->
                "Runde ${nextStep.roundIndex}/${nextStep.totalRounds}: ${nextStep.exerciseLabel}"
        }
        return ProgramTickResult(
            state = buildState(currentDistanceMeters, nowMs),
            cueMessage = msg,
            playTone = true,
        )
    }

    private fun buildState(currentDistanceMeters: Int, nowMs: Long): ProgramProgressState {
        val activePlan = plan!!
        val safeIdx = stepIndex.coerceIn(0, activePlan.steps.lastIndex)
        val step = activePlan.steps[safeIdx]
        val startDist = stepStartDistance ?: currentDistanceMeters
        val segDist = (currentDistanceMeters - startDist).coerceAtLeast(0)

        val progress = when {
            isCompleted -> 1f
            step is ProgramStep.RowingDistance ->
                (segDist.toFloat() / step.targetMeters.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            step is ProgramStep.TimedRest -> {
                val elapsedSec = ((nowMs - stepStartTimeMs).coerceAtLeast(0L) / 1000.0).toFloat()
                (elapsedSec / step.durationSeconds.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            }
            step is ProgramStep.OffRowerStation ->
                (safeIdx.toFloat() / activePlan.steps.size.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            else -> 0f
        }

        val restRemaining = if (step is ProgramStep.TimedRest && !isCompleted) {
            val elapsedSec = ((nowMs - stepStartTimeMs).coerceAtLeast(0L) / 1000L).toInt()
            (step.durationSeconds - elapsedSec).coerceAtLeast(0)
        } else {
            0
        }

        return ProgramProgressState(
            plan = activePlan,
            stepIndex = safeIdx,
            currentStep = step,
            isCompleted = isCompleted,
            stepProgressFraction = progress,
            stepDistanceMeters = segDist,
            restRemainingSeconds = restRemaining,
            completedRowingRounds = completedRowingRounds,
        )
    }

    companion object {
        const val DEFAULT_FLYWHEEL_GRACE_MS = 6_000L
        private const val RESUME_PULL_METERS = 5
    }
}
