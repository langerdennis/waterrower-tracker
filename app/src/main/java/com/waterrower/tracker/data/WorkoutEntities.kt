package com.waterrower.tracker.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "workouts")
data class WorkoutEntity(
    @PrimaryKey val id: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val durationSeconds: Int,
    val totalDistanceMeters: Int,
    val avgPaceSecPer500m: Int,
    val avgStrokeRate: Float,
    val avgWatts: Int,
    val totalStrokes: Int,
    val totalCalories: Int,
    val avgHeartRateBpm: Int? = null,
    val isSimulated: Boolean = false,
    val syncedAtEpochMs: Long? = null,
)

@Entity(
    tableName = "workout_samples",
    foreignKeys = [
        ForeignKey(
            entity = WorkoutEntity::class,
            parentColumns = ["id"],
            childColumns = ["workoutId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["workoutId", "offsetSeconds"])],
)
data class WorkoutSampleEntity(
    @PrimaryKey(autoGenerate = true) val sampleId: Long = 0L,
    val workoutId: String,
    val offsetSeconds: Int,
    val distanceMeters: Int,
    val paceSecPer500m: Int,
    val strokeRateSpm: Float,
    val watts: Int,
    val heartRateBpm: Int? = null,
)
