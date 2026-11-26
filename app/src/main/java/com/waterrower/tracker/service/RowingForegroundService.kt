package com.waterrower.tracker.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.waterrower.tracker.MainActivity
import com.waterrower.tracker.R
import com.waterrower.tracker.WaterRowerApp
import com.waterrower.tracker.data.RecordingState
import com.waterrower.tracker.model.ConnectionState
import com.waterrower.tracker.model.GhostDelta
import com.waterrower.tracker.model.ProgramProgressState
import com.waterrower.tracker.model.RowingTelemetry
import com.waterrower.tracker.model.WorkoutGoal
import com.waterrower.tracker.overlay.FloatingOverlayController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground Service (connectedDevice) that keeps the BLE link to the S4 ComModule,
 * workout recording, and the floating overlay alive while using other apps fullscreen.
 */
class RowingForegroundService : LifecycleService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayController: FloatingOverlayController? = null
    private var isForeground = false
    private var idleStopJob: Job? = null
    private var lastStartId: Int = -1
    private var completedGoalKey: String? = null
    private var wasConnectedForAutoOverlay: Boolean = false

    private var lastNotificationText: String? = null
    private var lastNotificationStateKey: String? = null
    private var lastNotificationUpdateMs: Long = 0L

    private val hub get() = (application as WaterRowerApp).sessionHub
    private val prefs get() = (application as WaterRowerApp).preferences

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()

        overlayController = FloatingOverlayController(
            context = this,
            preferences = prefs,
            onStartRecording = { hub.startRecording() },
            onTogglePause = { hub.togglePauseRecording() },
            onStopAndSave = { hub.stopAndSaveRecording() },
            onAdvanceProgramStep = { hub.advanceProgramStep() },
            onOpenApp = {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            },
            onCloseOverlay = {
                hideOverlay()
            },
        )

        lifecycleScope.launch {
            hub.audioCueEvents.collect {
                if (prefs.settings.value.goalSoundEnabled) {
                    playGoalChime()
                }
            }
        }

        lifecycleScope.launch {
            val ghostAndWeekly = combine(
                hub.ghostDelta,
                hub.ghostReference,
                hub.savedWeeklyDistanceMeters,
            ) { delta, ref, weeklyM ->
                Triple(delta, ref?.totalDistanceMeters, weeklyM)
            }
            val trainingExtras = combine(
                hub.activeGoal,
                hub.activeProgram,
                hub.batteryPercent,
                ghostAndWeekly,
            ) { goal, program, battery, gw ->
                TrainingExtras(
                    goal = goal,
                    ghost = gw.first,
                    ghostTotalMeters = gw.second,
                    program = program,
                    batteryPercent = battery,
                    savedWeeklyDistanceMeters = gw.third,
                )
            }
            combine(
                hub.telemetry,
                hub.connectionState,
                hub.recordingState,
                prefs.settings,
                combine(hub.isOverlayVisible, trainingExtras) { visible, te ->
                    visible to te
                },
            ) { telemetry, conn, rec, settings, extras ->
                OverlaySnapshot(
                    telemetry = telemetry,
                    connection = conn,
                    recording = rec,
                    settings = settings,
                    overlayVisible = extras.first,
                    goal = extras.second.goal,
                    ghostDelta = extras.second.ghost,
                    ghostTotalMeters = extras.second.ghostTotalMeters,
                    program = extras.second.program,
                    batteryPercent = extras.second.batteryPercent,
                    savedWeeklyDistanceMeters = extras.second.savedWeeklyDistanceMeters,
                )
            }.collect { snap ->
                val isConnected = snap.connection is ConnectionState.Connected
                if (isConnected && !wasConnectedForAutoOverlay) {
                    wasConnectedForAutoOverlay = true
                    if (snap.settings.autoShowOverlayOnConnect &&
                        !snap.overlayVisible &&
                        overlayController?.isShowing != true &&
                        Settings.canDrawOverlays(this@RowingForegroundService)
                    ) {
                        showOverlay()
                    }
                } else if (!isConnected) {
                    wasConnectedForAutoOverlay = false
                }

                runOnMain {
                    overlayController?.update(
                        telemetry = snap.telemetry,
                        connection = snap.connection,
                        recording = snap.recording,
                        settings = snap.settings,
                        goal = snap.goal,
                        ghostDelta = snap.ghostDelta,
                        ghostTotalMeters = snap.ghostTotalMeters,
                        program = snap.program,
                        batteryPercent = snap.batteryPercent,
                        savedWeeklyDistanceMeters = snap.savedWeeklyDistanceMeters,
                    )
                }
                checkGoalCompletionCue(snap)
                updateNotification(snap)
                updateIdleAutoStop(snap)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId

        val action = intent?.action ?: ACTION_START_SERVICE
        if (action == ACTION_STOP_ALL) {
            idleStopJob?.cancel()
            idleStopJob = null
            hideOverlay()
            hub.stopAndSaveRecording()
            hub.disconnect()
            isForeground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action != ACTION_HIDE_OVERLAY) {
            idleStopJob?.cancel()
            idleStopJob = null
        }

        if (!promoteToForeground()) {
            hub.setOverlayVisible(false)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_SHOW_OVERLAY -> showOverlay()
            ACTION_HIDE_OVERLAY -> hideOverlay()
            ACTION_TOGGLE_OVERLAY -> {
                if (overlayController?.isShowing == true) hideOverlay() else showOverlay()
            }
            ACTION_RESET_OVERLAY_POSITION -> {
                runOnMain { overlayController?.resetPosition() }
            }
            ACTION_START_SERVICE -> Unit
        }

        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        runOnMain { overlayController?.onConfigurationChanged() }
    }

    override fun onDestroy() {
        idleStopJob?.cancel()
        idleStopJob = null
        isForeground = false
        overlayController?.hide()
        hub.setOverlayVisible(false)
        super.onDestroy()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun promoteToForeground(): Boolean {
        if (isForeground) return true
        val initialText = lastNotificationText ?: "Bereit zum Rudern"
        val notification = buildNotification(initialText)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
            lastNotificationText = initialText
            lastNotificationUpdateMs = SystemClock.elapsedRealtime()
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Foreground service blocked by missing permission", e)
            hub.emitUiMessage("Hintergrunddienst erfordert Bluetooth-Berechtigung.")
            false
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException) {
                Log.e(TAG, "Foreground service start not allowed from background", e)
                hub.emitUiMessage("Hintergrunddienst kann derzeit nicht gestartet werden. Bitte App im Vordergrund öffnen.")
            } else {
                Log.e(TAG, "Failed to promote RowingForegroundService to foreground", e)
                hub.emitUiMessage("Tracking-Dienst konnte nicht gestartet werden.")
            }
            false
        }
    }

    private fun showOverlay() {
        runOnMain {
            val shown = overlayController?.show() == true
            hub.setOverlayVisible(shown)
            if (!shown) {
                hub.emitUiMessage("Overlay konnte nicht angezeigt werden. Bitte „Über anderen Apps einblenden“ prüfen.")
            }
        }
    }

    private fun hideOverlay() {
        runOnMain {
            overlayController?.hide()
            hub.setOverlayVisible(false)
        }
    }

    private fun updateIdleAutoStop(snap: OverlaySnapshot) {
        if (isServiceNeeded(snap.connection, snap.recording, snap.overlayVisible)) {
            idleStopJob?.cancel()
            idleStopJob = null
            return
        }
        if (idleStopJob?.isActive == true) return
        idleStopJob = lifecycleScope.launch {
            delay(IDLE_STOP_DELAY_MS)
            val stillNeeded = isServiceNeeded(
                connection = hub.connectionState.value,
                recording = hub.recordingState.value,
                overlayVisible = hub.isOverlayVisible.value || overlayController?.isShowing == true,
            )
            if (!stillNeeded) {
                val stopped = if (lastStartId != -1) {
                    stopSelfResult(lastStartId)
                } else {
                    stopSelf()
                    true
                }
                if (stopped) {
                    isForeground = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                }
            }
        }
    }

    private fun isServiceNeeded(
        connection: ConnectionState,
        recording: RecordingState,
        overlayVisible: Boolean,
    ): Boolean {
        return connection is ConnectionState.Connected ||
            connection is ConnectionState.Connecting ||
            connection is ConnectionState.Scanning ||
            recording is RecordingState.Active ||
            overlayVisible
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun updateNotification(snap: OverlaySnapshot) {
        if (!isForeground) return

        val stateKey = "${snap.connection::class.simpleName}:${snap.recording::class.simpleName}:" +
            ((snap.recording as? RecordingState.Active)?.let { "${it.isPaused}:${it.isAutoPaused}" } ?: "")

        val text = when (val conn = snap.connection) {
            is ConnectionState.Connected -> {
                val t = snap.telemetry
                when (val rec = snap.recording) {
                    is RecordingState.Active -> {
                        val pauseTag = when {
                            rec.isAutoPaused -> " (Auto-Pause)"
                            rec.isPaused -> " (Pausiert)"
                            else -> ""
                        }
                        val dist = RowingTelemetry.formatDistance(rec.distanceMeters)
                        val dur = RowingTelemetry.formatDuration(rec.elapsedSeconds)
                        "${conn.deviceName}$pauseTag: ${t.formattedPace} /500m • $dist • $dur"
                    }
                    RecordingState.Idle -> {
                        "${conn.deviceName}: ${t.formattedPace} /500m • ${t.formattedDistance} • ${t.formattedElapsed}"
                    }
                }
            }
            is ConnectionState.Connecting -> "Verbinde mit ${conn.deviceName}…"
            is ConnectionState.Scanning -> "Suche WaterRower S4…"
            is ConnectionState.Error -> conn.message
            ConnectionState.Disconnected -> "Nicht verbunden"
        }

        if (text == lastNotificationText) return

        val now = SystemClock.elapsedRealtime()
        val stateChanged = stateKey != lastNotificationStateKey
        if (!stateChanged && now - lastNotificationUpdateMs < NOTIFICATION_THROTTLE_MS) {
            return
        }

        lastNotificationText = text
        lastNotificationStateKey = stateKey
        lastNotificationUpdateMs = now

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(contentText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val toggleOverlayIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RowingForegroundService::class.java).apply {
                action = ACTION_TOGGLE_OVERLAY
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, RowingForegroundService::class.java).apply {
                action = ACTION_STOP_ALL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_rowing_notification)
            .setContentTitle("WaterRower")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Overlay ein/aus", toggleOverlayIntent)
            .addAction(0, "Beenden", stopIntent)
            .build()
    }

    private fun checkGoalCompletionCue(snap: OverlaySnapshot) {
        val rec = snap.recording as? RecordingState.Active ?: run {
            completedGoalKey = null
            return
        }
        val goal = snap.goal
        if (!goal.hasProgressTarget) {
            completedGoalKey = null
            return
        }
        val goalKey = "${rec.workoutId}:${goal.distanceTargetMeters}:${goal.durationTargetSeconds}"
        if (goal.isCompleted(rec.distanceMeters, rec.elapsedSeconds) && completedGoalKey != goalKey) {
            completedGoalKey = goalKey
            if (snap.settings.goalSoundEnabled) {
                playGoalChime()
            }
            hub.emitUiMessage("Ziel erreicht! (${goal.shortSummary()})")
        }
    }

    private fun playGoalChime() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75)
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 240)
            mainHandler.postDelayed({
                try {
                    tg.release()
                } catch (_: Exception) {
                }
            }, 350L)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to play goal completion tone", e)
        }
    }

    private data class TrainingExtras(
        val goal: WorkoutGoal,
        val ghost: GhostDelta?,
        val ghostTotalMeters: Int?,
        val program: ProgramProgressState?,
        val batteryPercent: Int?,
        val savedWeeklyDistanceMeters: Int,
    )

    private data class OverlaySnapshot(
        val telemetry: RowingTelemetry,
        val connection: ConnectionState,
        val recording: RecordingState,
        val settings: com.waterrower.tracker.data.OverlaySettings,
        val overlayVisible: Boolean,
        val goal: WorkoutGoal,
        val ghostDelta: GhostDelta?,
        val ghostTotalMeters: Int?,
        val program: ProgramProgressState?,
        val batteryPercent: Int?,
        val savedWeeklyDistanceMeters: Int,
    )

    companion object {
        private const val TAG = "RowingForegroundService"
        private const val CHANNEL_ID = "waterrower_tracking"
        private const val NOTIFICATION_ID = 1042
        private const val IDLE_STOP_DELAY_MS = 3_000L
        private const val NOTIFICATION_THROTTLE_MS = 2_000L

        const val ACTION_START_SERVICE = "com.waterrower.tracker.START_SERVICE"
        const val ACTION_SHOW_OVERLAY = "com.waterrower.tracker.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.waterrower.tracker.HIDE_OVERLAY"
        const val ACTION_TOGGLE_OVERLAY = "com.waterrower.tracker.TOGGLE_OVERLAY"
        const val ACTION_RESET_OVERLAY_POSITION = "com.waterrower.tracker.RESET_OVERLAY_POSITION"
        const val ACTION_STOP_ALL = "com.waterrower.tracker.STOP_ALL"

        fun startService(context: Context, action: String = ACTION_START_SERVICE): Boolean {
            val intent = Intent(context, RowingForegroundService::class.java).apply {
                this.action = action
            }
            return try {
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start RowingForegroundService for action=$action", e)
                val app = context.applicationContext as? WaterRowerApp
                app?.sessionHub?.emitUiMessage(
                    "Tracking-Dienst konnte nicht gestartet werden (${e.javaClass.simpleName}).",
                )
                false
            }
        }
    }
}
