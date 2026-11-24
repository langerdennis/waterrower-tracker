package com.waterrower.tracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Rowing
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waterrower.tracker.service.RowingForegroundService
import com.waterrower.tracker.ui.DashboardScreen
import com.waterrower.tracker.ui.HistoryScreen
import com.waterrower.tracker.ui.SettingsScreen
import com.waterrower.tracker.ui.WorkoutDetailScreen
import com.waterrower.tracker.ui.theme.WaterRowerTheme

private enum class AppTab(val label: String, val icon: ImageVector) {
    ROWING("Rudern", Icons.Rounded.Rowing),
    HISTORY("Historie", Icons.Rounded.History),
    SETTINGS("Einstellungen", Icons.Rounded.Settings),
}

class MainActivity : ComponentActivity() {

    private val app get() = application as WaterRowerApp
    private var pendingAfterPermissions: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            pendingAfterPermissions?.invoke()
        } else {
            Toast.makeText(
                this,
                "Bluetooth-/Benachrichtigungs-Berechtigungen sind für das Tracking erforderlich.",
                Toast.LENGTH_LONG,
            ).show()
        }
        pendingAfterPermissions = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WaterRowerTheme {
                WaterRowerRoot(
                    activity = this,
                    onRequestBleAndRun = { action -> runWithBlePermissions(action) },
                    onToggleOverlay = { handleToggleOverlay() },
                )
            }
        }
    }

    private fun requiredBlePermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        return perms.toTypedArray()
    }

    private fun hasAllPermissions(perms: Array<String>): Boolean =
        perms.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun runWithBlePermissions(action: () -> Unit) {
        val needed = requiredBlePermissions()
        if (hasAllPermissions(needed)) {
            action()
        } else {
            pendingAfterPermissions = action
            permissionLauncher.launch(needed)
        }
    }

    private fun handleToggleOverlay() {
        val hub = app.sessionHub
        if (hub.isOverlayVisible.value) {
            RowingForegroundService.startService(this, RowingForegroundService.ACTION_HIDE_OVERLAY)
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Bitte erlaube „Über anderen Apps einblenden“ für WaterRower Tracker.",
                Toast.LENGTH_LONG,
            ).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
            return
        }

        runWithBlePermissions {
            RowingForegroundService.startService(this, RowingForegroundService.ACTION_SHOW_OVERLAY)
        }
    }
}

@Composable
private fun WaterRowerRoot(
    activity: MainActivity,
    onRequestBleAndRun: (() -> Unit) -> Unit,
    onToggleOverlay: () -> Unit,
) {
    val app = activity.application as WaterRowerApp
    val hub = app.sessionHub
    val prefs = app.preferences

    val connectionState by hub.connectionState.collectAsStateWithLifecycle()
    val telemetry by hub.telemetry.collectAsStateWithLifecycle()
    val batteryPercent by hub.batteryPercent.collectAsStateWithLifecycle()
    val scannedDevices by hub.scannedDevices.collectAsStateWithLifecycle()
    val diagnostics by hub.diagnostics.collectAsStateWithLifecycle()
    val recordingState by hub.recordingState.collectAsStateWithLifecycle()
    val isOverlayVisible by hub.isOverlayVisible.collectAsStateWithLifecycle()
    val isSimulatorMode by hub.isSimulatorMode.collectAsStateWithLifecycle()
    val settings by prefs.settings.collectAsStateWithLifecycle()
    val workouts by hub.workouts.collectAsStateWithLifecycle(initialValue = emptyList())

    var selectedTab by rememberSaveable { mutableStateOf(AppTab.ROWING) }
    var selectedWorkoutId by rememberSaveable { mutableStateOf<String?>(null) }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        if (useRail) {
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail {
                    AppTab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = selectedTab == tab,
                            onClick = {
                                selectedTab = tab
                                selectedWorkoutId = null
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    TabContent(
                        selectedTab = selectedTab,
                        selectedWorkoutId = selectedWorkoutId,
                        onSelectWorkout = { selectedWorkoutId = it },
                        onBackFromDetail = { selectedWorkoutId = null },
                        connectionState = connectionState,
                        telemetry = telemetry,
                        batteryPercent = batteryPercent,
                        scannedDevices = scannedDevices,
                        diagnostics = diagnostics,
                        recordingState = recordingState,
                        isOverlayVisible = isOverlayVisible,
                        isSimulatorMode = isSimulatorMode,
                        settings = settings,
                        workouts = workouts,
                        activity = activity,
                        onRequestBleAndRun = onRequestBleAndRun,
                        onToggleOverlay = onToggleOverlay,
                    )
                }
            }
        } else {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        AppTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = {
                                    selectedTab = tab
                                    selectedWorkoutId = null
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                },
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding)) {
                    TabContent(
                        selectedTab = selectedTab,
                        selectedWorkoutId = selectedWorkoutId,
                        onSelectWorkout = { selectedWorkoutId = it },
                        onBackFromDetail = { selectedWorkoutId = null },
                        connectionState = connectionState,
                        telemetry = telemetry,
                        batteryPercent = batteryPercent,
                        scannedDevices = scannedDevices,
                        diagnostics = diagnostics,
                        recordingState = recordingState,
                        isOverlayVisible = isOverlayVisible,
                        isSimulatorMode = isSimulatorMode,
                        settings = settings,
                        workouts = workouts,
                        activity = activity,
                        onRequestBleAndRun = onRequestBleAndRun,
                        onToggleOverlay = onToggleOverlay,
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun TabContent(
    selectedTab: AppTab,
    selectedWorkoutId: String?,
    onSelectWorkout: (String) -> Unit,
    onBackFromDetail: () -> Unit,
    connectionState: com.waterrower.tracker.model.ConnectionState,
    telemetry: com.waterrower.tracker.model.RowingTelemetry,
    batteryPercent: Int?,
    scannedDevices: List<com.waterrower.tracker.model.ScannedDevice>,
    diagnostics: com.waterrower.tracker.model.BleDiagnostics,
    recordingState: com.waterrower.tracker.data.RecordingState,
    isOverlayVisible: Boolean,
    isSimulatorMode: Boolean,
    settings: com.waterrower.tracker.data.OverlaySettings,
    workouts: List<com.waterrower.tracker.data.WorkoutEntity>,
    activity: MainActivity,
    onRequestBleAndRun: (() -> Unit) -> Unit,
    onToggleOverlay: () -> Unit,
) {
    val app = activity.application as WaterRowerApp
    val hub = app.sessionHub
    val prefs = app.preferences

    when (selectedTab) {
        AppTab.ROWING -> DashboardScreen(
            connectionState = connectionState,
            telemetry = telemetry,
            batteryPercent = batteryPercent,
            scannedDevices = scannedDevices,
            recordingState = recordingState,
            isOverlayVisible = isOverlayVisible,
            isSimulatorMode = isSimulatorMode,
            settings = settings,
            onStartScan = {
                onRequestBleAndRun {
                    RowingForegroundService.startService(activity)
                    hub.startScan()
                }
            },
            onStopScan = { hub.stopScan() },
            onConnectDevice = { address, name ->
                onRequestBleAndRun {
                    RowingForegroundService.startService(activity)
                    hub.connect(address, name)
                }
            },
            onDisconnect = { hub.disconnect() },
            onToggleSimulator = { enabled ->
                if (enabled) {
                    onRequestBleAndRun {
                        RowingForegroundService.startService(activity)
                        hub.setSimulatorMode(true)
                    }
                } else {
                    hub.setSimulatorMode(false)
                }
            },
            onToggleOverlay = onToggleOverlay,
            onStartRecording = { hub.startRecording() },
            onTogglePause = { hub.togglePauseRecording() },
            onStopAndSave = { hub.stopAndSaveRecording() },
        )
        AppTab.HISTORY -> {
            if (selectedWorkoutId != null) {
                WorkoutDetailScreen(
                    workoutId = selectedWorkoutId,
                    hub = hub,
                    onBack = onBackFromDetail,
                )
            } else {
                HistoryScreen(
                    workouts = workouts,
                    onSelectWorkout = onSelectWorkout,
                )
            }
        }
        AppTab.SETTINGS -> SettingsScreen(
            settings = settings,
            diagnostics = diagnostics,
            onOpacityChange = { prefs.setOpacity(it) },
            onScaleChange = { prefs.setScale(it) },
            onShowWattsInMiniChange = { prefs.setShowWattsInMini(it) },
            onAutoStartChange = { prefs.setAutoStartRecording(it) },
        )
    }
}
