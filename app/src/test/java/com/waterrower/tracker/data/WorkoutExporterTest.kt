package com.waterrower.tracker.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutExporterTest {

    private val sampleWorkout = WorkoutEntity(
        id = "wk-001",
        startedAtEpochMs = 1_700_000_000_000L,
        endedAtEpochMs = 1_700_000_600_000L,
        durationSeconds = 600,
        totalDistanceMeters = 2500,
        avgPaceSecPer500m = 120,
        avgStrokeRate = 25.5f,
        avgWatts = 195,
        totalStrokes = 255,
        totalCalories = 140,
        avgHeartRateBpm = 144,
        isSimulated = false,
        syncedAtEpochMs = null,
    )

    private val samples = listOf(
        WorkoutSampleEntity(
            sampleId = 1L,
            workoutId = "wk-001",
            offsetSeconds = 1,
            distanceMeters = 4,
            paceSecPer500m = 125,
            strokeRateSpm = 24.5f,
            watts = 180,
            heartRateBpm = 138,
        ),
        WorkoutSampleEntity(
            sampleId = 2L,
            workoutId = "wk-001",
            offsetSeconds = 2,
            distanceMeters = 8,
            paceSecPer500m = 122,
            strokeRateSpm = 25.0f,
            watts = 190,
            heartRateBpm = null,
        ),
    )

    @Test
    fun buildCsv_startsDirectlyWithRfc4180HeaderWithoutCommentLine() {
        val csv = WorkoutExporter.buildCsv(sampleWorkout, samples)
        val lines = csv.trim().lines()

        assertEquals(3, lines.size)
        assertFalse("CSV must not contain a leading # comment", lines[0].startsWith("#"))
        assertEquals(
            "offset_seconds,distance_meters,pace_sec_per_500m,stroke_rate_spm,watts,heart_rate_bpm",
            lines[0],
        )
        assertEquals("1,4,125,24.5,180,138", lines[1])
        assertEquals("2,8,122,25.0,190,", lines[2])
    }

    @Test
    fun buildJson_containsSchemaVersionWorkoutSummaryAndSamples() {
        val json = WorkoutExporter.buildJson(sampleWorkout, samples)

        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertTrue(json.contains("\"id\": \"wk-001\""))
        assertTrue(json.contains("\"totalDistanceMeters\": 2500"))
        assertTrue(json.contains("\"avgStrokeRate\": 25.5"))
        assertTrue(json.contains("\"avgHeartRateBpm\": 144"))
        assertTrue(json.contains("\"offsetSeconds\": 1"))
        assertTrue(json.contains("\"heartRateBpm\": 138"))
        assertTrue(json.contains("\"offsetSeconds\": 2"))
        assertTrue(json.contains("\"heartRateBpm\": null"))
    }

    @Test
    fun buildJson_escapesRfc8259ControlCharacters() {
        val ctrlWorkout = sampleWorkout.copy(
            id = "id-\b-\u000C-\u0000-\u0001-\u001F-end",
        )
        val json = WorkoutExporter.buildJson(ctrlWorkout, emptyList())

        assertTrue(
            "Expected RFC 8259 escaped control chars in JSON, got: $json",
            json.contains("\"id\": \"id-\\b-\\f-\\u0000-\\u0001-\\u001f-end\""),
        )
        assertFalse(json.contains('\u0000'))
        assertFalse(json.contains('\u0001'))
        assertFalse(json.contains('\u001F'))
        assertFalse(json.contains('\b'))
        assertFalse(json.contains('\u000C'))
    }

    @Test
    fun buildCsv_sanitizesNonFiniteStrokeRateToZero() {
        val badSamples = listOf(
            samples[0].copy(offsetSeconds = 1, strokeRateSpm = Float.NaN),
            samples[1].copy(offsetSeconds = 2, strokeRateSpm = Float.POSITIVE_INFINITY),
            samples[1].copy(offsetSeconds = 3, strokeRateSpm = Float.NEGATIVE_INFINITY),
        )
        val csv = WorkoutExporter.buildCsv(sampleWorkout, badSamples)
        val lines = csv.trim().lines()

        assertEquals(4, lines.size)
        assertEquals("1,4,125,0.0,180,138", lines[1])
        assertEquals("2,8,122,0.0,190,", lines[2])
        assertEquals("3,8,122,0.0,190,", lines[3])
        assertFalse(csv.contains("NaN"))
        assertFalse(csv.contains("Infinity"))
    }
}
