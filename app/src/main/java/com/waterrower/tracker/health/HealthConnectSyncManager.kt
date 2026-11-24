package com.waterrower.tracker.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import com.waterrower.tracker.data.WorkoutEntity
import com.waterrower.tracker.model.RowingTelemetry
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

enum class HealthConnectAvailability {
    AVAILABLE,
    UPDATE_OR_INSTALL_REQUIRED,
    UNAVAILABLE,
}

/**
 * Local on-device Android Health Connect writer for WaterRower sessions.
 * Pure rowing and hybrid (Row + KB/Mat) workouts are both recorded as
 * [ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE] with clear title & notes
 * so fitness apps always surface rowed meters alongside duration and calories.
 */
object HealthConnectSyncManager {

    val REQUIRED_PERMISSIONS: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
    )

    fun availability(context: Context): HealthConnectAvailability {
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> HealthConnectAvailability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectAvailability.UPDATE_OR_INSTALL_REQUIRED
            else -> HealthConnectAvailability.UNAVAILABLE
        }
    }

    suspend fun hasAllPermissions(context: Context): Boolean {
        if (availability(context) != HealthConnectAvailability.AVAILABLE) return false
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            granted.containsAll(REQUIRED_PERMISSIONS)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    fun shouldSyncWorkout(workout: WorkoutEntity): Boolean {
        if (workout.isSimulated) return false
        if (workout.durationSeconds <= 0) return false
        if (workout.endedAtEpochMs <= workout.startedAtEpochMs) return false
        return true
    }

    fun buildSessionTitle(workout: WorkoutEntity): String {
        val summary = workout.programSummary?.trim()
        return if (!summary.isNullOrBlank()) {
            "WaterRower: $summary"
        } else if (workout.totalDistanceMeters > 0) {
            "WaterRower: ${RowingTelemetry.formatDistance(workout.totalDistanceMeters)}"
        } else {
            "WaterRower Training"
        }
    }

    fun buildSessionNotes(workout: WorkoutEntity): String {
        val parts = mutableListOf<String>()
        workout.programSummary?.trim()?.takeIf { it.isNotBlank() }?.let { prog ->
            parts += "Programm: $prog"
        }
        if (workout.totalDistanceMeters > 0) {
            parts += RowingTelemetry.formatDistance(workout.totalDistanceMeters)
        }
        if (workout.avgPaceSecPer500m > 0) {
            parts += "Ø ${RowingTelemetry.formatPace(workout.avgPaceSecPer500m)} /500m"
        }
        if (workout.avgStrokeRate > 0f) {
            parts += String.format(Locale.US, "%.1f spm", workout.avgStrokeRate)
        }
        if (workout.avgWatts > 0) {
            parts += "${workout.avgWatts} W"
        }
        return parts.joinToString(" • ")
    }

    /**
     * Writes a completed real workout to Android Health Connect using deterministic
     * `clientRecordId` values so re-syncing the same workout updates rather than duplicates.
     */
    suspend fun syncWorkout(context: Context, workout: WorkoutEntity): Result<Long> = try {
        require(shouldSyncWorkout(workout)) {
            "Simulator- oder leere Workouts werden nicht zu Health Connect synchronisiert."
        }
        check(availability(context) == HealthConnectAvailability.AVAILABLE) {
            "Health Connect ist auf diesem Gerät nicht verfügbar."
        }
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        check(granted.containsAll(REQUIRED_PERMISSIONS)) {
            "Health Connect Schreibberechtigung fehlt."
        }

        val startInstant = Instant.ofEpochMilli(workout.startedAtEpochMs)
        val endInstant = Instant.ofEpochMilli(
            workout.endedAtEpochMs.coerceAtLeast(workout.startedAtEpochMs + 1_000L),
        )
        val zone = ZoneId.systemDefault()
        val startOffset = zone.rules.getOffset(startInstant)
        val endOffset = zone.rules.getOffset(endInstant)

        val rowerDevice = Device(
            type = Device.TYPE_UNKNOWN,
            manufacturer = "WaterRower",
            model = "S4 ComModule",
        )

        val records = mutableListOf<Record>()
        records += ExerciseSessionRecord(
            startTime = startInstant,
            startZoneOffset = startOffset,
            endTime = endInstant,
            endZoneOffset = endOffset,
            metadata = Metadata.activelyRecorded(
                clientRecordId = "waterrower-session-${workout.id}",
                device = rowerDevice,
            ),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
            title = buildSessionTitle(workout),
            notes = buildSessionNotes(workout),
        )

        if (workout.totalDistanceMeters > 0) {
            records += DistanceRecord(
                startTime = startInstant,
                startZoneOffset = startOffset,
                endTime = endInstant,
                endZoneOffset = endOffset,
                distance = Length.meters(workout.totalDistanceMeters.toDouble()),
                metadata = Metadata.activelyRecorded(
                    clientRecordId = "waterrower-distance-${workout.id}",
                    device = rowerDevice,
                ),
            )
        }

        if (workout.totalCalories > 0) {
            records += TotalCaloriesBurnedRecord(
                startTime = startInstant,
                startZoneOffset = startOffset,
                endTime = endInstant,
                endZoneOffset = endOffset,
                energy = Energy.kilocalories(workout.totalCalories.toDouble()),
                metadata = Metadata.activelyRecorded(
                    clientRecordId = "waterrower-calories-${workout.id}",
                    device = rowerDevice,
                ),
            )
        }

        client.insertRecords(records)
        Result.success(System.currentTimeMillis())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }
}
