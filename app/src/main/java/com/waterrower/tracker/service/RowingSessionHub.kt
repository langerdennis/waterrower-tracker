package com.waterrower.tracker.service

import android.content.Context
import com.waterrower.tracker.ble.BleRowerClient
import com.waterrower.tracker.ble.RowerClient
import com.waterrower.tracker.ble.SimulatedRowerClient
import com.waterrower.tracker.data.AppPreferences
import com.waterrower.tracker.data.RecordedWorkout
import com.waterrower.tracker.data.RecordingState
import com.waterrower.tracker.data.WorkoutDatabase
import com.waterrower.tracker.data.WorkoutEntity
import com.waterrower.tracker.data.WorkoutRecorder
import com.waterrower.tracker.data.WorkoutSampleEntity
import com.waterrower.tracker.model.BleDiagnostics
import com.waterrower.tracker.model.ConnectionState
import com.waterrower.tracker.model.GhostDelta
import com.waterrower.tracker.model.GhostRacer
import com.waterrower.tracker.model.GhostReference
import com.waterrower.tracker.model.RowingTelemetry
import com.waterrower.tracker.model.ScannedDevice
import com.waterrower.tracker.model.WorkoutGoal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BackupImportSummary(
    val importedCount: Int,
    val skippedDuplicates: Int,
)

/**
 * Single source of truth shared between the Compose UI and [RowingForegroundService].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RowingSessionHub(
    context: Context,
    private val database: WorkoutDatabase,
    private val preferences: AppPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recorderLock = Any()

    private val bleClient = BleRowerClient(context.applicationContext, scope)
    private val simulatedClient = SimulatedRowerClient(scope)
    private val recorder = WorkoutRecorder()
    private var hasReceivedTelemetryForConnection = false

    private val _isSimulatorMode = MutableStateFlow(false)
    val isSimulatorMode: StateFlow<Boolean> = _isSimulatorMode.asStateFlow()

    private val _activeClient = MutableStateFlow<RowerClient>(bleClient)
    private val activeClient: StateFlow<RowerClient> = _activeClient.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = activeClient
        .flatMapLatest { it.connectionState }
        .stateIn(scope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    val telemetry: StateFlow<RowingTelemetry> = activeClient
        .flatMapLatest { it.telemetry }
        .stateIn(scope, SharingStarted.Eagerly, RowingTelemetry(timestampMs = 0L))

    val batteryPercent: StateFlow<Int?> = activeClient
        .flatMapLatest { it.batteryPercent }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val scannedDevices: StateFlow<List<ScannedDevice>> = activeClient
        .flatMapLatest { it.scannedDevices }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val diagnostics: StateFlow<BleDiagnostics> = activeClient
        .flatMapLatest { it.diagnostics }
        .stateIn(scope, SharingStarted.Eagerly, BleDiagnostics())

    private val _recordingState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    private val _activeGoal = MutableStateFlow(WorkoutGoal())
    val activeGoal: StateFlow<WorkoutGoal> = _activeGoal.asStateFlow()

    private val _ghostReference = MutableStateFlow<GhostReference?>(null)
    val ghostReference: StateFlow<GhostReference?> = _ghostReference.asStateFlow()

    val ghostDelta: StateFlow<GhostDelta?> = combine(
        ghostReference,
        recordingState,
        telemetry,
    ) { ghost, rec, frame ->
        if (ghost == null) return@combine null
        val elapsed = when (rec) {
            is RecordingState.Active -> rec.elapsedSeconds
            RecordingState.Idle -> frame.elapsedSeconds
        }
        val dist = when (rec) {
            is RecordingState.Active -> rec.distanceMeters
            RecordingState.Idle -> frame.distanceMeters
        }
        if (elapsed <= 0 && dist <= 0 && rec is RecordingState.Idle) {
            null
        } else {
            GhostRacer.evaluate(ghost, elapsedSeconds = elapsed, currentDistanceMeters = dist)
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _isOverlayVisible = MutableStateFlow(false)
    val isOverlayVisible: StateFlow<Boolean> = _isOverlayVisible.asStateFlow()

    private val _lastSavedWorkoutId = MutableStateFlow<String?>(null)
    val lastSavedWorkoutId: StateFlow<String?> = _lastSavedWorkoutId.asStateFlow()

    private val _uiMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val uiMessages: SharedFlow<String> = _uiMessages.asSharedFlow()

    val workouts: Flow<List<WorkoutEntity>> = database.workoutDao().observeAllWorkouts()

    init {
        scope.launch {
            connectionState.collect { conn ->
                if (conn !is ConnectionState.Connected) {
                    synchronized(recorderLock) {
                        hasReceivedTelemetryForConnection = false
                    }
                }
            }
        }
        scope.launch {
            telemetry.collect { frame ->
                if (connectionState.value is ConnectionState.Connected && frame.timestampMs > 0L) {
                    val autoStart = preferences.settings.value.autoStartRecording
                    synchronized(recorderLock) {
                        hasReceivedTelemetryForConnection = true
                        val updated = recorder.onTelemetry(
                            telemetry = frame,
                            autoStart = autoStart,
                            isSimulated = _isSimulatorMode.value,
                        )
                        _recordingState.value = updated
                    }
                }
            }
        }
        // Periodic wall-clock watchdog for auto-pause & idle auto-finish even if BLE goes silent
        scope.launch {
            while (isActive) {
                delay(IDLE_WATCHDOG_INTERVAL_MS)
                if (_recordingState.value !is RecordingState.Active) continue
                val timeoutMinutes = preferences.settings.value.autoFinishIdleMinutes
                val timeoutMs = timeoutMinutes * 60_000L
                val result = synchronized(recorderLock) {
                    val check = recorder.checkIdleAutoFinish(autoFinishTimeoutMs = timeoutMs)
                    _recordingState.value = check.state
                    check
                }
                if (result.didAutoFinish) {
                    val recorded = result.autoFinishedWorkout
                    if (recorded != null) {
                        database.workoutDao().saveWorkoutWithSamples(recorded.workout, recorded.samples)
                        _lastSavedWorkoutId.value = recorded.workout.id
                        emitUiMessage(
                            "Workout nach Leerlauf automatisch gespeichert (${RowingTelemetry.formatDistance(recorded.workout.totalDistanceMeters)}).",
                        )
                    } else {
                        emitUiMessage("Leere Aufnahme nach Leerlauf beendet.")
                    }
                }
            }
        }
    }

    fun emitUiMessage(message: String) {
        if (message.isNotBlank()) {
            _uiMessages.tryEmit(message)
        }
    }

    fun setSimulatorMode(enabled: Boolean) {
        if (_isSimulatorMode.value == enabled) return
        _activeClient.value.disconnect()
        _activeClient.value.resetTelemetry()
        synchronized(recorderLock) {
            hasReceivedTelemetryForConnection = false
            recorder.resetIdleBaseline()
        }
        _isSimulatorMode.value = enabled
        _activeClient.value = if (enabled) simulatedClient else bleClient
        if (enabled) {
            simulatedClient.connect(SimulatedRowerClient.SIM_ADDRESS, SimulatedRowerClient.SIM_NAME)
        }
    }

    fun connectSimulator() {
        if (!_isSimulatorMode.value) return
        synchronized(recorderLock) {
            hasReceivedTelemetryForConnection = false
            recorder.resetIdleBaseline()
        }
        simulatedClient.connect(SimulatedRowerClient.SIM_ADDRESS, SimulatedRowerClient.SIM_NAME)
    }

    fun startScan() {
        if (_isSimulatorMode.value) {
            simulatedClient.startScan()
        } else {
            bleClient.startScan()
        }
    }

    fun stopScan() {
        activeClient.value.stopScan()
    }

    fun connect(address: String, deviceName: String) {
        if (!_isSimulatorMode.value) {
            preferences.saveLastDevice(address, deviceName)
        }
        synchronized(recorderLock) {
            hasReceivedTelemetryForConnection = false
            recorder.resetIdleBaseline()
        }
        activeClient.value.connect(address, deviceName)
    }

    fun disconnect() {
        synchronized(recorderLock) {
            hasReceivedTelemetryForConnection = false
            recorder.resetIdleBaseline()
        }
        activeClient.value.disconnect()
        activeClient.value.resetTelemetry()
    }

    fun startRecording() {
        synchronized(recorderLock) {
            val current = telemetry.value
            val initial = if (
                connectionState.value is ConnectionState.Connected &&
                hasReceivedTelemetryForConnection &&
                current.timestampMs > 0L
            ) {
                current
            } else {
                null
            }
            _recordingState.value = recorder.start(
                initialTelemetry = initial,
                isSimulated = _isSimulatorMode.value,
            )
        }
    }

    fun togglePauseRecording() {
        synchronized(recorderLock) {
            _recordingState.value = recorder.togglePause()
        }
    }

    fun stopAndSaveRecording() {
        val recorded = synchronized(recorderLock) {
            val result = recorder.stop()
            _recordingState.value = RecordingState.Idle
            result
        }
        if (recorded != null) {
            scope.launch {
                database.workoutDao().saveWorkoutWithSamples(recorded.workout, recorded.samples)
                _lastSavedWorkoutId.value = recorded.workout.id
            }
        }
    }

    fun setOverlayVisible(visible: Boolean) {
        _isOverlayVisible.value = visible
    }

    fun setWorkoutGoal(goal: WorkoutGoal) {
        _activeGoal.value = goal
    }

    fun clearWorkoutGoal() {
        _activeGoal.value = WorkoutGoal()
    }

    fun selectGhostWorkout(workoutId: String?) {
        if (workoutId == null) {
            _ghostReference.value = null
            return
        }
        scope.launch {
            val workout = database.workoutDao().getWorkoutById(workoutId)
            if (workout == null) {
                emitUiMessage("Referenz-Workout für Ghost nicht gefunden.")
                return@launch
            }
            val samples = database.workoutDao().getSamplesForWorkout(workoutId)
            val dateLabel = SimpleDateFormat("dd.MM.", Locale.GERMANY)
                .format(Date(workout.startedAtEpochMs))
            val label = "$dateLabel • ${RowingTelemetry.formatDistance(workout.totalDistanceMeters)} (${RowingTelemetry.formatPace(workout.avgPaceSecPer500m)})"
            val ref = GhostRacer.fromWorkout(workout, samples, label)
            if (ref.points.size < 2) {
                emitUiMessage("Dieses Workout hat zu wenig Verlaufsdaten für den Ghost-Racer.")
                return@launch
            }
            _ghostReference.value = ref
            emitUiMessage("Ghost-Racer aktiv: $label")
        }
    }

    fun clearGhost() {
        _ghostReference.value = null
    }

    suspend fun getWorkout(workoutId: String): WorkoutEntity? =
        database.workoutDao().getWorkoutById(workoutId)

    suspend fun getWorkoutSamples(workoutId: String): List<WorkoutSampleEntity> =
        database.workoutDao().getSamplesForWorkout(workoutId)

    suspend fun getAllWorkoutsForBackup(): List<RecordedWorkout> {
        val dao = database.workoutDao()
        val allWorkouts = dao.getAllWorkoutsOnce()
        val samplesByWorkout = dao.getAllSamplesOnce().groupBy { it.workoutId }
        return allWorkouts.map { w ->
            RecordedWorkout(
                workout = w,
                samples = samplesByWorkout[w.id].orEmpty(),
            )
        }
    }

    suspend fun importWorkouts(items: List<RecordedWorkout>): BackupImportSummary {
        val imported = database.workoutDao().importNewWorkouts(items)
        val skipped = (items.size - imported).coerceAtLeast(0)
        return BackupImportSummary(importedCount = imported, skippedDuplicates = skipped)
    }

    fun deleteWorkout(workoutId: String) {
        scope.launch {
            database.workoutDao().deleteWorkout(workoutId)
        }
    }

    companion object {
        private const val IDLE_WATCHDOG_INTERVAL_MS = 5_000L
    }
}
