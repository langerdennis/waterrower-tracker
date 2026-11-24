package com.waterrower.tracker.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports recorded workouts to JSON or CSV via Android Share Sheet,
 * and supports full local JSON backup & restore via SAF documents.
 * Designed so a future backend sync can reuse the exact same JSON schema.
 */
object WorkoutExporter {

    const val BACKUP_TYPE = "waterrower_full_backup"

    fun buildJson(workout: WorkoutEntity, samples: List<WorkoutSampleEntity>): String {
        return buildString {
            appendWorkoutJson(this, workout, samples, indent = "")
        }
    }

    fun buildBackupJson(
        items: List<RecordedWorkout>,
        exportedAtEpochMs: Long = System.currentTimeMillis(),
    ): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"schemaVersion\": 1,")
        sb.appendLine("  \"backupType\": \"$BACKUP_TYPE\",")
        sb.appendLine("  \"exportedAtEpochMs\": $exportedAtEpochMs,")
        sb.appendLine("  \"workoutCount\": ${items.size},")
        if (items.isEmpty()) {
            sb.appendLine("  \"workouts\": []")
        } else {
            sb.appendLine("  \"workouts\": [")
            items.forEachIndexed { idx, item ->
                appendWorkoutJson(sb, item.workout, item.samples, indent = "    ")
                val trailing = if (idx < items.lastIndex) "," else ""
                sb.appendLine(trailing)
            }
            sb.appendLine("  ]")
        }
        sb.append("}")
        return sb.toString()
    }

    /**
     * Parses either a full backup JSON (`workouts` array) or a single-workout export JSON.
     * Pure Kotlin parser so it runs identically on Android and JVM unit tests.
     */
    fun parseBackupJson(json: String): Result<List<RecordedWorkout>> = runCatching {
        val parsed = SimpleJsonParser(json).parseRoot()
        val root = parsed as? Map<*, *>
            ?: error("Ungültiges JSON-Format (erwartet ein Objekt).")

        val workoutNodes: List<Map<*, *>> = when {
            root.containsKey("workouts") -> {
                val list = root["workouts"] as? List<*>
                    ?: error("Feld 'workouts' muss ein Array sein.")
                list.map { entry ->
                    entry as? Map<*, *> ?: error("Ungültiger Workout-Eintrag im Backup.")
                }
            }
            root.containsKey("id") -> listOf(root)
            else -> error("Keine WaterRower-Backupdaten gefunden.")
        }

        workoutNodes.map { node -> parseWorkoutNode(node) }
    }

    private fun parseWorkoutNode(node: Map<*, *>): RecordedWorkout {
        val id = (node["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("Workout ohne gültige ID.")
        val startedAt = node.requireLong("startedAtEpochMs")
        val endedAt = node.optLong("endedAtEpochMs", startedAt).coerceAtLeast(startedAt)
        val duration = node.optInt("durationSeconds", 0).coerceAtLeast(0)
        val distance = node.optInt("totalDistanceMeters", 0).coerceAtLeast(0)
        val avgPace = node.optInt("avgPaceSecPer500m", 0).coerceAtLeast(0)
        val avgSpm = node.optFloat("avgStrokeRate", 0f).coerceAtLeast(0f)
        val avgWatts = node.optInt("avgWatts", 0).coerceAtLeast(0)
        val totalStrokes = node.optInt("totalStrokes", 0).coerceAtLeast(0)
        val totalCalories = node.optInt("totalCalories", 0).coerceAtLeast(0)
        val avgHr = node.optNullableInt("avgHeartRateBpm")?.takeIf { it > 0 }
        val isSimulated = (node["isSimulated"] as? Boolean) ?: false
        val syncedAt = node.optNullableLong("syncedAtEpochMs")
        val programSummary = (node["programSummary"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        val sampleNodes = (node["samples"] as? List<*>).orEmpty()
        val samples = sampleNodes.mapNotNull { raw ->
            val s = raw as? Map<*, *> ?: return@mapNotNull null
            WorkoutSampleEntity(
                sampleId = 0L,
                workoutId = id,
                offsetSeconds = s.optInt("offsetSeconds", 0).coerceAtLeast(0),
                distanceMeters = s.optInt("distanceMeters", 0).coerceAtLeast(0),
                paceSecPer500m = s.optInt("paceSecPer500m", 0).coerceAtLeast(0),
                strokeRateSpm = s.optFloat("strokeRateSpm", 0f).coerceAtLeast(0f),
                watts = s.optInt("watts", 0).coerceAtLeast(0),
                heartRateBpm = s.optNullableInt("heartRateBpm")?.takeIf { it > 0 },
            )
        }

        return RecordedWorkout(
            workout = WorkoutEntity(
                id = id,
                startedAtEpochMs = startedAt,
                endedAtEpochMs = endedAt,
                durationSeconds = duration,
                totalDistanceMeters = distance,
                avgPaceSecPer500m = avgPace,
                avgStrokeRate = avgSpm,
                avgWatts = avgWatts,
                totalStrokes = totalStrokes,
                totalCalories = totalCalories,
                avgHeartRateBpm = avgHr,
                isSimulated = isSimulated,
                syncedAtEpochMs = syncedAt,
                programSummary = programSummary,
            ),
            samples = samples,
        )
    }

    private fun appendWorkoutJson(
        sb: StringBuilder,
        workout: WorkoutEntity,
        samples: List<WorkoutSampleEntity>,
        indent: String,
    ) {
        val inner = "$indent  "
        sb.appendLine("$indent{")
        sb.appendLine("${inner}\"schemaVersion\": 1,")
        sb.appendLine("${inner}\"id\": \"${escapeJson(workout.id)}\",")
        sb.appendLine("${inner}\"startedAtEpochMs\": ${workout.startedAtEpochMs},")
        sb.appendLine("${inner}\"endedAtEpochMs\": ${workout.endedAtEpochMs},")
        sb.appendLine("${inner}\"durationSeconds\": ${workout.durationSeconds},")
        sb.appendLine("${inner}\"totalDistanceMeters\": ${workout.totalDistanceMeters},")
        sb.appendLine("${inner}\"avgPaceSecPer500m\": ${workout.avgPaceSecPer500m},")
        sb.appendLine("${inner}\"avgStrokeRate\": ${formatJsonNumber(workout.avgStrokeRate.toDouble())},")
        sb.appendLine("${inner}\"avgWatts\": ${workout.avgWatts},")
        sb.appendLine("${inner}\"totalStrokes\": ${workout.totalStrokes},")
        sb.appendLine("${inner}\"totalCalories\": ${workout.totalCalories},")
        sb.appendLine("${inner}\"avgHeartRateBpm\": ${workout.avgHeartRateBpm ?: "null"},")
        sb.appendLine("${inner}\"isSimulated\": ${workout.isSimulated},")
        val progVal = workout.programSummary?.let { "\"${escapeJson(it)}\"" } ?: "null"
        sb.appendLine("${inner}\"programSummary\": $progVal,")
        if (samples.isEmpty()) {
            sb.appendLine("${inner}\"samples\": []")
        } else {
            val sampleIndent = "$inner  "
            val fieldIndent = "$sampleIndent  "
            sb.appendLine("${inner}\"samples\": [")
            samples.forEachIndexed { idx, s ->
                sb.appendLine("$sampleIndent{")
                sb.appendLine("${fieldIndent}\"offsetSeconds\": ${s.offsetSeconds},")
                sb.appendLine("${fieldIndent}\"distanceMeters\": ${s.distanceMeters},")
                sb.appendLine("${fieldIndent}\"paceSecPer500m\": ${s.paceSecPer500m},")
                sb.appendLine("${fieldIndent}\"strokeRateSpm\": ${formatJsonNumber(s.strokeRateSpm.toDouble())},")
                sb.appendLine("${fieldIndent}\"watts\": ${s.watts},")
                sb.appendLine("${fieldIndent}\"heartRateBpm\": ${s.heartRateBpm ?: "null"}")
                val trailing = if (idx < samples.lastIndex) "," else ""
                sb.appendLine("$sampleIndent}$trailing")
            }
            sb.appendLine("${inner}]")
        }
        sb.append("$indent}")
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

    fun defaultBackupFilename(nowMs: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(nowMs))
        return "waterrower_backup_$stamp.json"
    }

    suspend fun writeBackupToUri(
        context: Context,
        uri: Uri,
        items: List<RecordedWorkout>,
    ): Result<Int> = try {
        withContext(Dispatchers.IO) {
            val content = buildBackupJson(items)
            val stream = context.contentResolver.openOutputStream(uri)
                ?: error("Datei konnte nicht zum Schreiben geöffnet werden.")
            stream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(content)
            }
        }
        Result.success(items.size)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }

    suspend fun readBackupFromUri(
        context: Context,
        uri: Uri,
    ): Result<List<RecordedWorkout>> = try {
        val raw = withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openInputStream(uri)
                ?: error("Datei konnte nicht gelesen werden.")
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        parseBackupJson(raw)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
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

    private fun Map<*, *>.requireLong(key: String): Long =
        optNullableLong(key) ?: error("Pflichtfeld '$key' fehlt oder ist ungültig.")

    private fun Map<*, *>.optNullableLong(key: String): Long? =
        (this[key] as? Number)?.toLong()

    private fun Map<*, *>.optLong(key: String, default: Long): Long =
        optNullableLong(key) ?: default

    private fun Map<*, *>.optNullableInt(key: String): Int? =
        (this[key] as? Number)?.toInt()

    private fun Map<*, *>.optInt(key: String, default: Int): Int =
        optNullableInt(key) ?: default

    private fun Map<*, *>.optFloat(key: String, default: Float): Float =
        (this[key] as? Number)?.toFloat()?.takeIf { it.isFinite() } ?: default

    /**
     * Minimal RFC-8259 JSON parser in pure Kotlin (no Android framework dependency).
     */
    @Suppress("UNCHECKED_CAST")
    internal fun parseJsonObject(raw: String): Map<String, Any?>? =
        SimpleJsonParser(raw).parseRoot() as? Map<String, Any?>

    private class SimpleJsonParser(private val input: String) {
        private var pos = 0

        fun parseRoot(): Any? {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (pos != input.length) {
                error("Unerwartete Zeichen am Ende des JSON (Position $pos).")
            }
            return value
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= input.length) error("Unerwartetes Ende des JSON.")
            return when (val ch = input[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> error("Unerwartetes Zeichen '$ch' an Position $pos.")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val map = LinkedHashMap<String, Any?>()
            if (peek('}')) {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                when {
                    peek(',') -> pos++
                    peek('}') -> {
                        pos++
                        return map
                    }
                    else -> error("Erwartet ',' oder '}' an Position $pos.")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            skipWhitespace()
            val list = ArrayList<Any?>()
            if (peek(']')) {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                when {
                    peek(',') -> pos++
                    peek(']') -> {
                        pos++
                        return list
                    }
                    else -> error("Erwartet ',' oder ']' an Position $pos.")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < input.length) {
                val ch = input[pos++]
                when {
                    ch == '"' -> return sb.toString()
                    ch == '\\' -> {
                        if (pos >= input.length) error("Unvollständige Escape-Sequenz.")
                        when (val esc = input[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > input.length) error("Ungültige Unicode-Escape-Sequenz.")
                                val hex = input.substring(pos, pos + 4)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> error("Ungültiges Escape-Zeichen '\\$esc'.")
                        }
                    }
                    ch < ' ' -> error("Unescaped Kontrollzeichen im JSON-String.")
                    else -> sb.append(ch)
                }
            }
            error("Nicht geschlossener JSON-String.")
        }

        private fun parseNumber(): Number {
            val start = pos
            if (peek('-')) pos++
            if (pos >= input.length || input[pos] !in '0'..'9') {
                error("Ungültige Zahl an Position $start.")
            }
            while (pos < input.length && input[pos] in '0'..'9') pos++
            var isFloat = false
            if (peek('.')) {
                isFloat = true
                pos++
                if (pos >= input.length || input[pos] !in '0'..'9') {
                    error("Ungültige Dezimalzahl an Position $start.")
                }
                while (pos < input.length && input[pos] in '0'..'9') pos++
            }
            if (peek('e') || peek('E')) {
                isFloat = true
                pos++
                if (peek('+') || peek('-')) pos++
                if (pos >= input.length || input[pos] !in '0'..'9') {
                    error("Ungültiger Exponent an Position $start.")
                }
                while (pos < input.length && input[pos] in '0'..'9') pos++
            }
            val token = input.substring(start, pos)
            return if (isFloat) {
                token.toDouble()
            } else {
                token.toLongOrNull() ?: token.toDouble()
            }
        }

        private fun parseLiteral(literal: String, result: Any?): Any? {
            if (!input.startsWith(literal, pos)) {
                error("Erwartet '$literal' an Position $pos.")
            }
            pos += literal.length
            return result
        }

        private fun skipWhitespace() {
            while (pos < input.length) {
                when (input[pos]) {
                    ' ', '\t', '\r', '\n' -> pos++
                    else -> return
                }
            }
        }

        private fun peek(expected: Char): Boolean = pos < input.length && input[pos] == expected

        private fun expect(expected: Char) {
            if (!peek(expected)) error("Erwartet '$expected' an Position $pos.")
            pos++
        }
    }
}
