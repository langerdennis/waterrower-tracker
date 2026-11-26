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
import com.waterrower.tracker.health.HealthConnectSyncManager
import com.waterrower.tracker.model.ActiveProgramPlan
import com.waterrower.tracker.model.BleDiagnostics
import com.waterrower.tracker.model.ConnectionState
import com.waterrower.tracker.model.GhostDelta
import com.waterrower.tracker.model.GhostRacer
import com.waterrower.tracker.model.GhostReference
import com.waterrower.tracker.model.ProgramPreset
import com.waterrower.tracker.model.ProgramProgressState
import com.waterrower.tracker.model.ProgramRunner
import com.waterrower.tracker.model.ProgramScale
import com.waterrower.tracker.model.ProgramStep
import com.waterrower.tracker.model.ProgramTickResult
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
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recorderLock = Any()

    private val bleClient = BleRowerClient(context.applicationContext, scope)
    private val simulatedClient = SimulatedRowerClient(scope)
    private val recorder = WorkoutRecorder()
    private val programRunner = ProgramRunner()
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

    private val _activeProgram = MutableStateFlow<ProgramProgressState?>(null)
    val activeProgram: StateFlow<ProgramProgressState?> = _activeProgram.asStateFlow()

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

    private val _audioCueEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val audioCueEvents: SharedFlow<Unit> = _audioCueEvents.asSharedFlow()

    val workouts: Flow<List<WorkoutEntity>> = database.workoutDao().observeAllWorkouts()

    val savedWeeklyDistanceMeters: StateFlow<Int> = combine(
        workouts,
        isSimulatorMode,
    ) { allWorkouts, simMode ->
        val cutoff = System.currentTimeMillis() - SEVEN_DAYS_MS
        allWorkouts
            .filter { w -> w.startedAtEpochMs >= cutoff && (simMode || !w.isSimulated) }
            .sumOf { it.totalDistanceMeters }
    }.stateIn(scope, SharingStarted.Eagerly, 0)

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
                    var programTick: ProgramTickResult? = null
                    synchronized(recorderLock) {
                        hasReceivedTelemetryForConnection = true
                        val prevState = _recordingState.value
                        val updated = recorder.onTelemetry(
                            telemetry = frame,
                            autoStart = autoStart,
                            isSimulated = _isSimulatorMode.value,
                        )
                        _recordingState.value = updated
                        if (prevState is RecordingState.Idle && updated is RecordingState.Active) {
                            // Fresh workout started via auto-start -> align active program to 0m
                            _activeProgram.value = programRunner.resetForNewSession(
                                currentDistanceMeters = updated.distanceMeters,
                                nowMs = frame.timestampMs,
                            )
                        }
                        if (updated is RecordingState.Active) {
                            programTick = programRunner.onTick(
                                nowMs = frame.timestampMs,
                                totalDistanceMeters = updated.distanceMeters,
                                strokeRateSpm = frame.strokeRateSpm,
                                isRecordingActive = true,
                            )
                            _activeProgram.value = programTick?.state
                        }
                    }
                    handleProgramTickFeedback(programTick)
                }
            }
        }
        // 1s wall-clock ticker for smooth timed-rest countdowns + 5s idle auto-finish watchdog
        scope.launch {
            var tickCounter = 0
            while (isActive) {
                delay(1_000L)
                tickCounter += 1
                val rec = _recordingState.value
                if (rec !is RecordingState.Active) continue

                val activeProg = _activeProgram.value
                if (activeProg != null && !activeProg.isCompleted &&
                    activeProg.currentStep is ProgramStep.TimedRest
                ) {
                    val tick = synchronized(recorderLock) {
                        val currentRec = _recordingState.value as? RecordingState.Active
                        if (currentRec != null) {
                            val res = programRunner.onTick(
                                nowMs = System.currentTimeMillis(),
                                totalDistanceMeters = currentRec.distanceMeters,
                                strokeRateSpm = telemetry.value.strokeRateSpm,
                                isRecordingActive = true,
                            )
                            _activeProgram.value = res.state
                            res
                        } else {
                            null
                        }
                    }
                    handleProgramTickFeedback(tick)
                }

                if (tickCounter % 5 != 0) continue

                // While waiting on an active timed-rest or off-rower KB/Mat station, don't auto-finish
                // prematurely before the user finishes their off-rower set; respect longer timeout.
                val timeoutMinutes = preferences.settings.value.autoFinishIdleMinutes
                val timeoutMs = timeoutMinutes * 60_000L
                var summaryToSave: String? = null
                val result = synchronized(recorderLock) {
                    val check = recorder.checkIdleAutoFinish(autoFinishTimeoutMs = timeoutMs)
                    _recordingState.value = check.state
                    if (check.didAutoFinish) {
                        summaryToSave = _activeProgram.value?.storageSummary()
                        _activeProgram.value = programRunner.resetForNewSession()
                    }
                    check
                }
                if (result.didAutoFinish) {
                    val recorded = result.autoFinishedWorkout
                    if (recorded != null) {
                        val enriched = if (summaryToSave != null) {
                            recorded.copy(workout = recorded.workout.copy(programSummary = summaryToSave))
                        } else {
                            recorded
                        }
                        database.workoutDao().saveWorkoutWithSamples(enriched.workout, enriched.samples)
                        _lastSavedWorkoutId.value = enriched.workout.id
                        maybeAutoSyncHealthConnect(enriched.workout)
                        emitUiMessage(
                            "Workout nach Leerlauf automatisch gespeichert (${RowingTelemetry.formatDistance(enriched.workout.totalDistanceMeters)}).",
                        )
                    } else {
                        emitUiMessage("Leere Aufnahme nach Leerlauf beendet.")
                    }
                }
            }
        }
    }

    private fun handleProgramTickFeedback(tick: ProgramTickResult?) {
        if (tick == null) return
        if (tick.playTone) {
            _audioCueEvents.tryEmit(Unit)
        }
        tick.cueMessage?.let { emitUiMessage(it) }
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
            val started = recorder.start(
                initialTelemetry = initial,
                isSimulated = _isSimulatorMode.value,
            )
            _recordingState.value = started
            val dist = (started as? RecordingState.Active)?.distanceMeters ?: 0
            _activeProgram.value = programRunner.resetForNewSession(currentDistanceMeters = dist)
        }
    }

    fun togglePauseRecording() {
        synchronized(recorderLock) {
            _recordingState.value = recorder.togglePause()
        }
    }

    fun stopAndSaveRecording() {
        var programSummary: String? = null
        val recorded = synchronized(recorderLock) {
            programSummary = _activeProgram.value?.storageSummary()
            val result = recorder.stop()
            _recordingState.value = RecordingState.Idle
            _activeProgram.value = programRunner.resetForNewSession()
            result
        }
        if (recorded != null) {
            val enriched = if (programSummary != null) {
                recorded.copy(workout = recorded.workout.copy(programSummary = programSummary))
            } else {
                recorded
            }
            scope.launch {
                database.workoutDao().saveWorkoutWithSamples(enriched.workout, enriched.samples)
                _lastSavedWorkoutId.value = enriched.workout.id
                maybeAutoSyncHealthConnect(enriched.workout)
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

    fun selectProgram(preset: ProgramPreset?, scale: ProgramScale = ProgramScale.MEDIUM) {
        val plan: ActiveProgramPlan? = preset?.buildPlan(scale)
        synchronized(recorderLock) {
            val dist = (_recordingState.value as? RecordingState.Active)?.distanceMeters ?: 0
            _activeProgram.value = programRunner.setPlan(plan, currentDistanceMeters = dist)
        }
        if (plan != null) {
            emitUiMessage("Programm bereit: ${plan.titleWithScale} (${plan.detailSummary})")
        }
    }

    fun clearProgram() {
        synchronized(recorderLock) {
            _activeProgram.value = programRunner.setPlan(null)
        }
    }

    fun advanceProgramStep() {
        val tick = synchronized(recorderLock) {
            val dist = (_recordingState.value as? RecordingState.Active)?.distanceMeters ?: 0
            val res = programRunner.advanceStepManually(totalDistanceMeters = dist)
            _activeProgram.value = res.state
            res
        }
        handleProgramTickFeedback(tick)
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

    suspend fun syncWorkoutToHealthConnect(workout: WorkoutEntity): Result<Long> {
        val result = HealthConnectSyncManager.syncWorkout(appContext, workout)
        result.onSuccess { syncedAt ->
            database.workoutDao().markWorkoutSynced(workout.id, syncedAt)
        }
        return result
    }

    suspend fun syncAllUnsyncedToHealthConnect(): Result<Int> {
        val unsynced = database.workoutDao().getAllWorkoutsOnce().filter {
            it.syncedAtEpochMs == null && HealthConnectSyncManager.shouldSyncWorkout(it)
        }
        if (unsynced.isEmpty()) return Result.success(0)
        var syncedCount = 0
        var lastFailure: Throwable? = null
        for (w in unsynced) {
            val res = syncWorkoutToHealthConnect(w)
            if (res.isSuccess) {
                syncedCount += 1
            } else {
                lastFailure = res.exceptionOrNull()
                break
            }
        }
        return if (syncedCount > 0 || lastFailure == null) {
            Result.success(syncedCount)
        } else {
            Result.failure(lastFailure)
        }
    }

    private suspend fun maybeAutoSyncHealthConnect(workout: WorkoutEntity) {
        if (!preferences.settings.value.healthConnectSyncEnabled) return
        if (!HealthConnectSyncManager.shouldSyncWorkout(workout)) return
        syncWorkoutToHealthConnect(workout).onFailure { err ->
            emitUiMessage(
                "Health Connect Sync fehlgeschlagen: ${err.localizedMessage ?: "Berechtigung prüfen"}",
            )
        }
    }

    fun deleteWorkout(workoutId: String) {
        scope.launch {
            database.workoutDao().deleteWorkout(workoutId)
        }
    }

    companion object {
        private const val SEVEN_DAYS_MS = 7L * 24L * 60L * 60L * 1_000L
    }
}
