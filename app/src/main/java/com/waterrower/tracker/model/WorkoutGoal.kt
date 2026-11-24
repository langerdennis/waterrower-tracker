package com.waterrower.tracker.model

enum class PaceZone {
    NONE,
    FASTER,
    ON_TARGET,
    SLOWER,
}

/**
 * Optional workout targets (distance, duration, and/or target pace) evaluated live
 * on the dashboard and inside the floating overlay.
 */
data class WorkoutGoal(
    val distanceTargetMeters: Int? = null,
    val durationTargetSeconds: Int? = null,
    val targetPaceSecPer500m: Int? = null,
) {
    val hasProgressTarget: Boolean
        get() = (distanceTargetMeters != null && distanceTargetMeters > 0) ||
            (durationTargetSeconds != null && durationTargetSeconds > 0)

    val hasAnyTarget: Boolean
        get() = hasProgressTarget || (targetPaceSecPer500m != null && targetPaceSecPer500m > 0)

    /**
     * Progress ratio in [0f, 1f], prioritizing distance target if both are set.
     * Returns null when neither a distance nor a duration goal is active.
     */
    fun progressFraction(distanceMeters: Int, elapsedSeconds: Int): Float? {
        val distTarget = distanceTargetMeters?.takeIf { it > 0 }
        if (distTarget != null) {
            return (distanceMeters.coerceAtLeast(0).toFloat() / distTarget.toFloat()).coerceIn(0f, 1f)
        }
        val durTarget = durationTargetSeconds?.takeIf { it > 0 }
        if (durTarget != null) {
            return (elapsedSeconds.coerceAtLeast(0).toFloat() / durTarget.toFloat()).coerceIn(0f, 1f)
        }
        return null
    }

    fun isCompleted(distanceMeters: Int, elapsedSeconds: Int): Boolean {
        val distTarget = distanceTargetMeters?.takeIf { it > 0 }
        val durTarget = durationTargetSeconds?.takeIf { it > 0 }
        return when {
            distTarget != null -> distanceMeters >= distTarget
            durTarget != null -> elapsedSeconds >= durTarget
            else -> false
        }
    }

    fun remainingSummary(distanceMeters: Int, elapsedSeconds: Int): String? {
        val distTarget = distanceTargetMeters?.takeIf { it > 0 }
        if (distTarget != null) {
            val rem = (distTarget - distanceMeters).coerceAtLeast(0)
            return if (rem == 0) "Ziel erreicht" else "noch ${RowingTelemetry.formatDistance(rem)}"
        }
        val durTarget = durationTargetSeconds?.takeIf { it > 0 }
        if (durTarget != null) {
            val rem = (durTarget - elapsedSeconds).coerceAtLeast(0)
            return if (rem == 0) "Ziel erreicht" else "noch ${RowingTelemetry.formatDuration(rem)}"
        }
        return null
    }

    /**
     * Evaluates instantaneous pace against [targetPaceSecPer500m] with a +/- [toleranceSec] window.
     * Lower seconds/500m = faster rowing.
     */
    fun evaluatePaceZone(currentPaceSec: Int, toleranceSec: Int = DEFAULT_PACE_TOLERANCE_SEC): PaceZone {
        val target = targetPaceSecPer500m?.takeIf { it in 60..600 } ?: return PaceZone.NONE
        if (currentPaceSec <= 0 || currentPaceSec >= 3600) return PaceZone.NONE
        return when {
            currentPaceSec < target - toleranceSec -> PaceZone.FASTER
            currentPaceSec > target + toleranceSec -> PaceZone.SLOWER
            else -> PaceZone.ON_TARGET
        }
    }

    fun shortSummary(): String {
        val parts = mutableListOf<String>()
        distanceTargetMeters?.takeIf { it > 0 }?.let { parts += RowingTelemetry.formatDistance(it) }
        durationTargetSeconds?.takeIf { it > 0 }?.let { parts += RowingTelemetry.formatDuration(it) }
        targetPaceSecPer500m?.takeIf { it > 0 }?.let { parts += "${RowingTelemetry.formatPace(it)} /500m" }
        return parts.joinToString(" • ")
    }

    companion object {
        const val DEFAULT_PACE_TOLERANCE_SEC = 3
    }
}
