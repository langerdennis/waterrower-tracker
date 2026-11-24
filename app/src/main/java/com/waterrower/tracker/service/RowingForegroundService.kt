package com.waterrower.tracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.waterrower.tracker.MainActivity
import com.waterrower.tracker.R
import com.waterrower.tracker.WaterRowerApp
import com.waterrower.tracker.data.RecordingState
import com.waterrower.tracker.model.ConnectionState
import com.waterrower.tracker.overlay.FloatingOverlayController
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground Service (connectedDevice) that keeps the BLE link to the S4 ComModule,
 * workout recording, and the floating overlay alive while using other apps fullscreen.
 */
class RowingForegroundService : LifecycleService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayController: FloatingOverlayController? = null

    private val hub get() = (application as WaterRowerApp).sessionHub
    private val prefs get() = (application as WaterRowerApp).preferences

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()

        overlayController = FloatingOverlayController(
            context = this,
            preferences = prefs,
            onOpenApp = {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(intent)
            },
            onToggleRecording = {
                if (hub.recordingState.value is RecordingState.Active) {
                    hub.stopAndSaveRecording()
                } else {
                    hub.startRecording()
                }
            },
            onCloseOverlay = {
                hideOverlay()
            },
        )

        lifecycleScope.launch {
            combine(
                hub.telemetry,
                hub.connectionState,
                hub.recordingState,
                prefs.settings,
            ) { telemetry, conn, rec, settings ->
                OverlaySnapshot(telemetry, conn, rec, settings)
            }.collect { snap ->
                mainHandler.post {
                    overlayController?.update(
                        telemetry = snap.telemetry,
                        connection = snap.connection,
                        recording = snap.recording,
                        settings = snap.settings,
                    )
                }
                updateNotification(snap)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val action = intent?.action ?: ACTION_START_SERVICE
        if (action == ACTION_STOP_ALL) {
            hideOverlay()
            hub.stopAndSaveRecording()
            hub.disconnect()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!promoteToForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_SHOW_OVERLAY -> showOverlay()
            ACTION_HIDE_OVERLAY -> hideOverlay()
            ACTION_TOGGLE_OVERLAY -> {
                if (overlayController?.isShowing == true) hideOverlay() else showOverlay()
            }
            ACTION_START_SERVICE -> Unit
        }

        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.post { overlayController?.hide() }
        hub.setOverlayVisible(false)
        super.onDestroy()
    }

    private fun promoteToForeground(): Boolean {
        val notification = buildNotification("Bereit zum Rudern")
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
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun showOverlay() {
        mainHandler.post {
            val shown = overlayController?.show() == true
            hub.setOverlayVisible(shown)
        }
    }

    private fun hideOverlay() {
        mainHandler.post {
            overlayController?.hide()
            hub.setOverlayVisible(false)
        }
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
        val text = when (val conn = snap.connection) {
            is ConnectionState.Connected -> {
                val t = snap.telemetry
                "${conn.deviceName}: ${t.formattedPace} /500m • ${t.formattedDistance} • ${t.formattedElapsed}"
            }
            is ConnectionState.Connecting -> "Verbinde mit ${conn.deviceName}…"
            is ConnectionState.Scanning -> "Suche WaterRower S4…"
            is ConnectionState.Error -> conn.message
            ConnectionState.Disconnected -> "Nicht verbunden"
        }
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
            .setContentTitle("WaterRower Tracker")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, "Overlay ein/aus", toggleOverlayIntent)
            .addAction(0, "Beenden", stopIntent)
            .build()
    }

    private data class OverlaySnapshot(
        val telemetry: com.waterrower.tracker.model.RowingTelemetry,
        val connection: ConnectionState,
        val recording: RecordingState,
        val settings: com.waterrower.tracker.data.OverlaySettings,
    )

    companion object {
        private const val CHANNEL_ID = "waterrower_tracking"
        private const val NOTIFICATION_ID = 1042

        const val ACTION_START_SERVICE = "com.waterrower.tracker.START_SERVICE"
        const val ACTION_SHOW_OVERLAY = "com.waterrower.tracker.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.waterrower.tracker.HIDE_OVERLAY"
        const val ACTION_TOGGLE_OVERLAY = "com.waterrower.tracker.TOGGLE_OVERLAY"
        const val ACTION_STOP_ALL = "com.waterrower.tracker.STOP_ALL"

        fun startService(context: Context, action: String = ACTION_START_SERVICE) {
            val intent = Intent(context, RowingForegroundService::class.java).apply {
                this.action = action
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
