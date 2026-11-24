package com.waterrower.tracker.service

import android.content.Context
import com.waterrower.tracker.ble.BleRowerClient
import com.waterrower.tracker.ble.RowerClient
import com.waterrower.tracker.ble.SimulatedRowerClient
import com.waterrower.tracker.data.AppPreferences
import com.waterrower.tracker.data.RecordingState
import com.waterrower.tracker.data.WorkoutDatabase
import com.waterrower.tracker.data.WorkoutEntity
import com.waterrower.tracker.data.WorkoutRecorder
import com.waterrower.tracker.data.WorkoutSampleEntity
import com.waterrower.tracker.model.BleDiagnostics
import com.waterrower.tracker.model.ConnectionState
import com.waterrower.tracker.model.RowingTelemetry
import com.waterrower.tracker.model.ScannedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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

    private val bleClient = BleRowerClient(context.applicationContext, scope)
    private val simulatedClient = SimulatedRowerClient(scope)
    private val recorder = WorkoutRecorder()

    private val _isSimulatorMode = MutableStateFlow(false)
    val isSimulatorMode: StateFlow<Boolean> = _isSimulatorMode.asStateFlow()

    private val _activeClient = MutableStateFlow<RowerClient>(bleClient)
    private val activeClient: StateFlow<RowerClient> = _activeClient.asStateFlow()

    val connectionState: StateFlow<ConnectionState> = activeClient
        .flatMapLatest { it.connectionState }
        .stateIn(scope, SharingStarted.Eagerly, ConnectionState.Disconnected)

    val telemetry: StateFlow<RowingTelemetry> = activeClient
        .flatMapLatest { it.telemetry }
        .stateIn(scope, SharingStarted.Eagerly, RowingTelemetry())

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

    private val _isOverlayVisible = MutableStateFlow(false)
    val isOverlayVisible: StateFlow<Boolean> = _isOverlayVisible.asStateFlow()

    private val _lastSavedWorkoutId = MutableStateFlow<String?>(null)
    val lastSavedWorkoutId: StateFlow<String?> = _lastSavedWorkoutId.asStateFlow()

    val workouts: Flow<List<WorkoutEntity>> = database.workoutDao().observeAllWorkouts()

    init {
        scope.launch {
            telemetry.collect { frame ->
                if (connectionState.value is ConnectionState.Connected) {
                    val autoStart = preferences.settings.value.autoStartRecording
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

    fun setSimulatorMode(enabled: Boolean) {
        if (_isSimulatorMode.value == enabled) return
        _activeClient.value.disconnect()
        _isSimulatorMode.value = enabled
        _activeClient.value = if (enabled) simulatedClient else bleClient
        if (enabled) {
            simulatedClient.connect("SIM:00:00:00:00:61", "S4 Comms 61 (Simulator)")
        }
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
        activeClient.value.connect(address, deviceName)
    }

    fun disconnect() {
        activeClient.value.disconnect()
    }

    fun startRecording() {
        val current = telemetry.value
        _recordingState.value = recorder.start(
            initialTelemetry = if (connectionState.value is ConnectionState.Connected) current else null,
            isSimulated = _isSimulatorMode.value,
        )
    }

    fun togglePauseRecording() {
        _recordingState.value = recorder.togglePause()
    }

    fun stopAndSaveRecording() {
        val recorded = recorder.stop()
        _recordingState.value = RecordingState.Idle
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

    suspend fun getWorkout(workoutId: String): WorkoutEntity? =
        database.workoutDao().getWorkoutById(workoutId)

    suspend fun getWorkoutSamples(workoutId: String): List<WorkoutSampleEntity> =
        database.workoutDao().getSamplesForWorkout(workoutId)

    fun deleteWorkout(workoutId: String) {
        scope.launch {
            database.workoutDao().deleteWorkout(workoutId)
        }
    }
}
