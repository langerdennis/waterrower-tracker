package com.waterrower.tracker.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports recorded workouts to JSON or CSV via Android Share Sheet.
 * Designed so a future backend sync can reuse the exact same JSON schema.
 */
object WorkoutExporter {

    fun buildJson(workout: WorkoutEntity, samples: List<WorkoutSampleEntity>): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"schemaVersion\": 1,")
        sb.appendLine("  \"id\": \"${escapeJson(workout.id)}\",")
        sb.appendLine("  \"startedAtEpochMs\": ${workout.startedAtEpochMs},")
        sb.appendLine("  \"endedAtEpochMs\": ${workout.endedAtEpochMs},")
        sb.appendLine("  \"durationSeconds\": ${workout.durationSeconds},")
        sb.appendLine("  \"totalDistanceMeters\": ${workout.totalDistanceMeters},")
        sb.appendLine("  \"avgPaceSecPer500m\": ${workout.avgPaceSecPer500m},")
        sb.appendLine("  \"avgStrokeRate\": ${formatJsonNumber(workout.avgStrokeRate.toDouble())},")
        sb.appendLine("  \"avgWatts\": ${workout.avgWatts},")
        sb.appendLine("  \"totalStrokes\": ${workout.totalStrokes},")
        sb.appendLine("  \"totalCalories\": ${workout.totalCalories},")
        sb.appendLine("  \"avgHeartRateBpm\": ${workout.avgHeartRateBpm ?: "null"},")
        sb.appendLine("  \"isSimulated\": ${workout.isSimulated},")
        if (samples.isEmpty()) {
            sb.appendLine("  \"samples\": []")
        } else {
            sb.appendLine("  \"samples\": [")
            samples.forEachIndexed { idx, s ->
                sb.appendLine("    {")
                sb.appendLine("      \"offsetSeconds\": ${s.offsetSeconds},")
                sb.appendLine("      \"distanceMeters\": ${s.distanceMeters},")
                sb.appendLine("      \"paceSecPer500m\": ${s.paceSecPer500m},")
                sb.appendLine("      \"strokeRateSpm\": ${formatJsonNumber(s.strokeRateSpm.toDouble())},")
                sb.appendLine("      \"watts\": ${s.watts},")
                sb.appendLine("      \"heartRateBpm\": ${s.heartRateBpm ?: "null"}")
                val trailing = if (idx < samples.lastIndex) "," else ""
                sb.appendLine("    }$trailing")
            }
            sb.appendLine("  ]")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun escapeJson(value: String): String = buildString(value.length) {
        for (ch in value) {
            when {
                ch == '\\' -> append("\\\\")
                ch == '"' -> append("\\\"")
                ch == '\b' -> append("\\b")
                ch == '\u000C' -> append("\\f")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch < ' ' -> append(String.format(Locale.US, "\\u%04x", ch.code))
                else -> append(ch)
            }
        }
    }

    private fun formatJsonNumber(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.') else "0"

    fun buildCsv(workout: WorkoutEntity, samples: List<WorkoutSampleEntity>): String {
        val sb = StringBuilder()
        sb.appendLine("offset_seconds,distance_meters,pace_sec_per_500m,stroke_rate_spm,watts,heart_rate_bpm")
        samples.forEach { s ->
            val safeSpm = if (s.strokeRateSpm.isFinite()) s.strokeRateSpm else 0f
            val spmFormatted = String.format(Locale.US, "%.1f", safeSpm)
            sb.appendLine(
                "${s.offsetSeconds},${s.distanceMeters},${s.paceSecPer500m},$spmFormatted,${s.watts},${s.heartRateBpm ?: ""}",
            )
        }
        return sb.toString()
    }

    suspend fun shareWorkout(
        context: Context,
        workout: WorkoutEntity,
        samples: List<WorkoutSampleEntity>,
        asCsv: Boolean = false,
    ): Result<Unit> = try {
        val chooserIntent = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(Date(workout.startedAtEpochMs))
            val ext = if (asCsv) "csv" else "json"
            val file = File(dir, "waterrower_$stamp.$ext")
            val content = if (asCsv) buildCsv(workout, samples) else buildJson(workout, samples)
            file.writeText(content)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val mime = if (asCsv) "text/csv" else "application/json"
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "WaterRower Workout $stamp")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Intent.createChooser(shareIntent, "Workout exportieren").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        withContext(Dispatchers.Main) {
            context.startActivity(chooserIntent)
        }
        Result.success(Unit)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }
}
