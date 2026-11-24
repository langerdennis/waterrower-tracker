package com.waterrower.tracker.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
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
        val root = JSONObject().apply {
            put("schemaVersion", 1)
            put("id", workout.id)
            put("startedAtEpochMs", workout.startedAtEpochMs)
            put("endedAtEpochMs", workout.endedAtEpochMs)
            put("durationSeconds", workout.durationSeconds)
            put("totalDistanceMeters", workout.totalDistanceMeters)
            put("avgPaceSecPer500m", workout.avgPaceSecPer500m)
            put("avgStrokeRate", workout.avgStrokeRate.toDouble())
            put("avgWatts", workout.avgWatts)
            put("totalStrokes", workout.totalStrokes)
            put("totalCalories", workout.totalCalories)
            put("avgHeartRateBpm", workout.avgHeartRateBpm ?: JSONObject.NULL)
            put("isSimulated", workout.isSimulated)
            put(
                "samples",
                JSONArray().apply {
                    samples.forEach { s ->
                        put(
                            JSONObject().apply {
                                put("offsetSeconds", s.offsetSeconds)
                                put("distanceMeters", s.distanceMeters)
                                put("paceSecPer500m", s.paceSecPer500m)
                                put("strokeRateSpm", s.strokeRateSpm.toDouble())
                                put("watts", s.watts)
                                put("heartRateBpm", s.heartRateBpm ?: JSONObject.NULL)
                            },
                        )
                    }
                },
            )
        }
        return root.toString(2)
    }

    fun buildCsv(workout: WorkoutEntity, samples: List<WorkoutSampleEntity>): String {
        val sb = StringBuilder()
        sb.appendLine("# WaterRower Workout ${workout.id}")
        sb.appendLine("offset_seconds,distance_meters,pace_sec_per_500m,stroke_rate_spm,watts,heart_rate_bpm")
        samples.forEach { s ->
            sb.appendLine(
                "${s.offsetSeconds},${s.distanceMeters},${s.paceSecPer500m},${s.strokeRateSpm},${s.watts},${s.heartRateBpm ?: ""}",
            )
        }
        return sb.toString()
    }

    fun shareWorkout(
        context: Context,
        workout: WorkoutEntity,
        samples: List<WorkoutSampleEntity>,
        asCsv: Boolean = false,
    ) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(workout.startedAtEpochMs))
        val ext = if (asCsv) "csv" else "json"
        val file = File(dir, "waterrower_$stamp.$ext")
        file.writeText(if (asCsv) buildCsv(workout, samples) else buildJson(workout, samples))

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
        context.startActivity(
            Intent.createChooser(shareIntent, "Workout exportieren").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
