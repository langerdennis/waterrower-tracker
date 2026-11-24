package com.waterrower.tracker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Rowing
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailDefaults
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.waterrower.tracker.data.RecordingState
import com.waterrower.tracker.data.WorkoutExporter
import com.waterrower.tracker.service.RowingForegroundService
import com.waterrower.tracker.ui.DashboardScreen
import com.waterrower.tracker.ui.HistoryScreen
import com.waterrower.tracker.ui.SettingsScreen
import com.waterrower.tracker.ui.WorkoutDetailScreen
import com.waterrower.tracker.ui.theme.LocalRowingColors
import com.waterrower.tracker.ui.theme.WaterRowerTheme
import kotlinx.coroutines.launch

private enum class AppTab(val label: String, val icon: ImageVector) {
    ROWING("Rudern", Icons.Rounded.Rowing),
    HISTORY("Historie", Icons.Rounded.History),
    SETTINGS("Einstellungen", Icons.Rounded.Settings),
}

private data class BlePermissionDialogState(
    val permanentlyDenied: Boolean,
)

class MainActivity : ComponentActivity() {

    private val app get() = application as WaterRowerApp
    private var pendingAfterPermissions: (() -> Unit)? = null
    private var hasRequestedNotificationPermission = false
    private var permissionDialogState by mutableStateOf<BlePermissionDialogState?>(null)
    private var isBackupBusy by mutableStateOf(false)

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        performBackupExport(uri)
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        performBackupImport(uri)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val notifDenied = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            results.containsKey(Manifest.permission.POST_NOTIFICATIONS) &&
            results[Manifest.permission.POST_NOTIFICATIONS] == false

        if (hasCoreBlePermissions()) {
            val action = pendingAfterPermissions
            pendingAfterPermissions = null
            permissionDialogState = null
            action?.invoke()
            if (notifDenied) {
                app.sessionHub.emitUiMessage(
                    "Tracking aktiv – Benachrichtigungen sind deaktiviert und können in den App-Einstellungen aktiviert werden.",
                )
            }
        } else {
            val permanentlyDenied = coreBlePermissions().any { perm ->
                ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED &&
                    !shouldShowRequestPermissionRationale(perm)
            }
            if (permanentlyDenied) {
                pendingAfterPermissions = null
            }
            permissionDialogState = BlePermissionDialogState(permanentlyDenied = permanentlyDenied)
        }
    }

    private val optionalNotificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            app.sessionHub.emitUiMessage(
                "Benachrichtigungen sind optional – Bluetooth-Tracking funktioniert weiterhin.",
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hasRequestedNotificationPermission =
            savedInstanceState?.getBoolean(KEY_REQUESTED_NOTIFICATION_PERMISSION, false) ?: false

        setContent {
            val darkTheme = isSystemInDarkTheme()
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    },
                    navigationBarStyle = if (darkTheme) {
                        SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                    },
                )
                onDispose {}
            }

            WaterRowerTheme(darkTheme = darkTheme) {
                WaterRowerRoot(
                    activity = this,
                    permissionDialogState = permissionDialogState,
                    isBackupBusy = isBackupBusy,
                    onDismissPermissionDialog = {
                        pendingAfterPermissions = null
                        permissionDialogState = null
                    },
                    onRetryBlePermissions = {
                        permissionDialogState = null
                        permissionLauncher.launch(coreBlePermissions())
                    },
                    onOpenAppSettings = {
                        pendingAfterPermissions = null
                        permissionDialogState = null
                        openAppSettings()
                    },
                    onRequestBleAndRun = { action -> runWithBlePermissions(action) },
                    onStartSimulator = { startOrReconnectSimulator(reconnect = false) },
                    onReconnectSimulator = { startOrReconnectSimulator(reconnect = true) },
                    onToggleOverlay = { handleToggleOverlay() },
                    onResetOverlayPosition = { handleResetOverlayPosition() },
                    onExportBackup = { handleLaunchBackupExport() },
                    onImportBackup = { handleLaunchBackupImport() },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_REQUESTED_NOTIFICATION_PERMISSION, hasRequestedNotificationPermission)
    }

    private fun handleLaunchBackupExport() {
        if (isBackupBusy) return
        exportBackupLauncher.launch(WorkoutExporter.defaultBackupFilename())
    }

    private fun handleLaunchBackupImport() {
        if (isBackupBusy) return
        importBackupLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
    }

    private fun performBackupExport(uri: Uri) {
        if (isBackupBusy) return
        isBackupBusy = true
        lifecycleScope.launch {
            try {
                val items = app.sessionHub.getAllWorkoutsForBackup()
                val result = WorkoutExporter.writeBackupToUri(this@MainActivity, uri, items)
                result.onSuccess { count ->
                    app.sessionHub.emitUiMessage(
                        if (count == 1) {
                            "Backup gespeichert (1 Workout)."
                        } else {
                            "Backup gespeichert ($count Workouts)."
                        },
                    )
                }.onFailure { err ->
                    app.sessionHub.emitUiMessage(
                        "Backup fehlgeschlagen: ${err.localizedMessage ?: "Unbekannter Fehler"}",
                    )
                }
            } finally {
                isBackupBusy = false
            }
        }
    }

    private fun performBackupImport(uri: Uri) {
        if (isBackupBusy) return
        isBackupBusy = true
        lifecycleScope.launch {
            try {
                val parsed = WorkoutExporter.readBackupFromUri(this@MainActivity, uri)
                parsed.onSuccess { items ->
                    if (items.isEmpty()) {
                        app.sessionHub.emitUiMessage("Die gewählte Backup-Datei enthält keine Workouts.")
                    } else {
                        val summary = app.sessionHub.importWorkouts(items)
                        val msg = buildString {
                            append(
                                if (summary.importedCount == 1) {
                                    "1 Workout wiederhergestellt"
                                } else {
                                    "${summary.importedCount} Workouts wiederhergestellt"
                                },
                            )
                            if (summary.skippedDuplicates > 0) {
                                append(" (${summary.skippedDuplicates} bereits vorhanden)")
                            }
                            append(".")
                        }
                        app.sessionHub.emitUiMessage(msg)
                    }
                }.onFailure { err ->
                    app.sessionHub.emitUiMessage(
                        "Import fehlgeschlagen: ${err.localizedMessage ?: "Ungültige Backup-Datei"}",
                    )
                }
            } finally {
                isBackupBusy = false
            }
        }
    }

    private fun coreBlePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasCoreBlePermissions(): Boolean =
        coreBlePermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private fun runWithBlePermissions(action: () -> Unit) {
        if (hasCoreBlePermissions()) {
            action()
            // Request optional notification permission non-blockingly at most once
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !hasRequestedNotificationPermission &&
                !hasNotificationPermission()
            ) {
                hasRequestedNotificationPermission = true
                optionalNotificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            pendingAfterPermissions = action
            val toRequest = buildList {
                addAll(coreBlePermissions())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !hasRequestedNotificationPermission &&
                    !hasNotificationPermission()
                ) {
                    hasRequestedNotificationPermission = true
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
            permissionLauncher.launch(toRequest)
        }
    }

    private fun startOrReconnectSimulator(reconnect: Boolean) {
        val hub = app.sessionHub
        // Allow in-app simulator without blocking on BLE permissions; start FGS if already permitted
        if (hasCoreBlePermissions()) {
            RowingForegroundService.startService(this)
        }
        if (reconnect) {
            hub.connectSimulator()
        } else {
            hub.setSimulatorMode(true)
        }
    }

    private fun openAppSettings() {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        )
        startActivity(intent)
    }

    private fun handleToggleOverlay() {
        val hub = app.sessionHub
        if (hub.isOverlayVisible.value) {
            RowingForegroundService.startService(this, RowingForegroundService.ACTION_HIDE_OVERLAY)
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            hub.emitUiMessage("Bitte erlaube „Über anderen Apps einblenden“ für WaterRower.")
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

    private fun handleResetOverlayPosition() {
        app.preferences.resetOverlayPosition()
        if (app.sessionHub.isOverlayVisible.value) {
            RowingForegroundService.startService(
                this,
                RowingForegroundService.ACTION_RESET_OVERLAY_POSITION,
            )
        }
        app.sessionHub.emitUiMessage("Overlay-Position wurde zurückgesetzt.")
    }

    companion object {
        private const val KEY_REQUESTED_NOTIFICATION_PERMISSION = "requested_notification_permission"
    }
}

@Composable
private fun WaterRowerRoot(
    activity: MainActivity,
    permissionDialogState: BlePermissionDialogState?,
    isBackupBusy: Boolean,
    onDismissPermissionDialog: () -> Unit,
    onRetryBlePermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRequestBleAndRun: (() -> Unit) -> Unit,
    onStartSimulator: () -> Unit,
    onReconnectSimulator: () -> Unit,
    onToggleOverlay: () -> Unit,
    onResetOverlayPosition: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
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
    val activeGoal by hub.activeGoal.collectAsStateWithLifecycle()
    val activeProgram by hub.activeProgram.collectAsStateWithLifecycle()
    val ghostReference by hub.ghostReference.collectAsStateWithLifecycle()
    val ghostDelta by hub.ghostDelta.collectAsStateWithLifecycle()
    val isOverlayVisible by hub.isOverlayVisible.collectAsStateWithLifecycle()
    val isSimulatorMode by hub.isSimulatorMode.collectAsStateWithLifecycle()
    val settings by prefs.settings.collectAsStateWithLifecycle()
    val workouts by hub.workouts.collectAsStateWithLifecycle(initialValue = emptyList())

    var selectedTab by rememberSaveable { mutableStateOf(AppTab.ROWING) }
    var selectedWorkoutId by rememberSaveable { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = remember(snackbarHostState, uiScope) {
        { msg ->
            uiScope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(msg)
            }
        }
    }

    LaunchedEffect(hub) {
        hub.uiMessages.collect { message ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    if (permissionDialogState != null) {
        AlertDialog(
            onDismissRequest = onDismissPermissionDialog,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Bluetooth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = { Text("Bluetooth-Berechtigung erforderlich") },
            text = {
                Text(
                    text = if (permissionDialogState.permanentlyDenied) {
                        "Für die Verbindung zum WaterRower S4 Comms 61 und den Tracking-Dienst wird die Bluetooth-Berechtigung benötigt. Bitte aktiviere sie in den App-Einstellungen."
                    } else {
                        "Für die Suche nach deinem WaterRower S4 Comms 61 und den Hintergrund-Tracking-Dienst wird die Bluetooth-Berechtigung benötigt."
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = if (permissionDialogState.permanentlyDenied) {
                        onOpenAppSettings
                    } else {
                        onRetryBlePermissions
                    },
                ) {
                    Text(
                        if (permissionDialogState.permanentlyDenied) {
                            "App-Einstellungen öffnen"
                        } else {
                            "Berechtigung anfragen"
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissPermissionDialog) {
                    Text("Abbrechen")
                }
            },
        )
    }

    val openSettingsTab: () -> Unit = {
        selectedTab = AppTab.SETTINGS
        selectedWorkoutId = null
    }
    val startGhostFromDetail: (String) -> Unit = { workoutId ->
        hub.selectGhostWorkout(workoutId)
        selectedWorkoutId = null
        selectedTab = AppTab.ROWING
    }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        val rowingColors = LocalRowingColors.current
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp

            if (useRail) {
                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        windowInsets = NavigationRailDefaults.windowInsets,
                        header = {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Rowing,
                                        contentDescription = "WaterRower",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                                if (recordingState is RecordingState.Active) {
                                    val dotColor = if ((recordingState as RecordingState.Active).isPaused) {
                                        rowingColors.warning
                                    } else {
                                        rowingColors.recording
                                    }
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(11.dp)
                                            .clip(CircleShape)
                                            .background(dotColor),
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        },
                    ) {
                        listOf(AppTab.ROWING, AppTab.HISTORY).forEach { tab ->
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
                        Spacer(Modifier.weight(1f))
                        NavigationRailItem(
                            selected = selectedTab == AppTab.SETTINGS,
                            onClick = openSettingsTab,
                            icon = { Icon(AppTab.SETTINGS.icon, contentDescription = AppTab.SETTINGS.label) },
                            label = { Text(AppTab.SETTINGS.label) },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    VerticalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    Scaffold(
                        modifier = Modifier.weight(1f),
                        containerColor = MaterialTheme.colorScheme.background,
                        snackbarHost = { SnackbarHost(snackbarHostState) },
                        contentWindowInsets = WindowInsets.safeDrawing.only(
                            WindowInsetsSides.Vertical + WindowInsetsSides.End,
                        ),
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding),
                        ) {
                            TabContent(
                                selectedTab = selectedTab,
                                selectedWorkoutId = selectedWorkoutId,
                                onSelectWorkout = { selectedWorkoutId = it },
                                onBackFromDetail = { selectedWorkoutId = null },
                                onOpenSettings = openSettingsTab,
                                onStartGhostFromDetail = startGhostFromDetail,
                                connectionState = connectionState,
                                telemetry = telemetry,
                                batteryPercent = batteryPercent,
                                scannedDevices = scannedDevices,
                                diagnostics = diagnostics,
                                recordingState = recordingState,
                                activeGoal = activeGoal,
                                activeProgram = activeProgram,
                                ghostReference = ghostReference,
                                ghostDelta = ghostDelta,
                                isOverlayVisible = isOverlayVisible,
                                isSimulatorMode = isSimulatorMode,
                                isBackupBusy = isBackupBusy,
                                settings = settings,
                                workouts = workouts,
                                activity = activity,
                                onRequestBleAndRun = onRequestBleAndRun,
                                onStartSimulator = onStartSimulator,
                                onReconnectSimulator = onReconnectSimulator,
                                onToggleOverlay = onToggleOverlay,
                                onResetOverlayPosition = onResetOverlayPosition,
                                onExportBackup = onExportBackup,
                                onImportBackup = onImportBackup,
                                onShowMessage = showMessage,
                            )
                        }
                    }
                }
            } else {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    contentWindowInsets = WindowInsets.safeDrawing,
                    bottomBar = {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            windowInsets = NavigationBarDefaults.windowInsets,
                        ) {
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
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                    ) {
                        TabContent(
                            selectedTab = selectedTab,
                            selectedWorkoutId = selectedWorkoutId,
                            onSelectWorkout = { selectedWorkoutId = it },
                            onBackFromDetail = { selectedWorkoutId = null },
                            onOpenSettings = openSettingsTab,
                            onStartGhostFromDetail = startGhostFromDetail,
                            connectionState = connectionState,
                            telemetry = telemetry,
                            batteryPercent = batteryPercent,
                            scannedDevices = scannedDevices,
                            diagnostics = diagnostics,
                            recordingState = recordingState,
                            activeGoal = activeGoal,
                            activeProgram = activeProgram,
                            ghostReference = ghostReference,
                            ghostDelta = ghostDelta,
                            isOverlayVisible = isOverlayVisible,
                            isSimulatorMode = isSimulatorMode,
                            isBackupBusy = isBackupBusy,
                            settings = settings,
                            workouts = workouts,
                            activity = activity,
                            onRequestBleAndRun = onRequestBleAndRun,
                            onStartSimulator = onStartSimulator,
                            onReconnectSimulator = onReconnectSimulator,
                            onToggleOverlay = onToggleOverlay,
                            onResetOverlayPosition = onResetOverlayPosition,
                            onExportBackup = onExportBackup,
                            onImportBackup = onImportBackup,
                            onShowMessage = showMessage,
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
    onOpenSettings: () -> Unit,
    onStartGhostFromDetail: (String) -> Unit,
    connectionState: com.waterrower.tracker.model.ConnectionState,
    telemetry: com.waterrower.tracker.model.RowingTelemetry,
    batteryPercent: Int?,
    scannedDevices: List<com.waterrower.tracker.model.ScannedDevice>,
    diagnostics: com.waterrower.tracker.model.BleDiagnostics,
    recordingState: RecordingState,
    activeGoal: com.waterrower.tracker.model.WorkoutGoal,
    activeProgram: com.waterrower.tracker.model.ProgramProgressState?,
    ghostReference: com.waterrower.tracker.model.GhostReference?,
    ghostDelta: com.waterrower.tracker.model.GhostDelta?,
    isOverlayVisible: Boolean,
    isSimulatorMode: Boolean,
    isBackupBusy: Boolean,
    settings: com.waterrower.tracker.data.OverlaySettings,
    workouts: List<com.waterrower.tracker.data.WorkoutEntity>,
    activity: MainActivity,
    onRequestBleAndRun: (() -> Unit) -> Unit,
    onStartSimulator: () -> Unit,
    onReconnectSimulator: () -> Unit,
    onToggleOverlay: () -> Unit,
    onResetOverlayPosition: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onShowMessage: (String) -> Unit,
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
            activeGoal = activeGoal,
            activeProgram = activeProgram,
            ghostReference = ghostReference,
            ghostDelta = ghostDelta,
            recentWorkouts = workouts,
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
            onConnectSimulator = onReconnectSimulator,
            onDisableSimulator = { hub.setSimulatorMode(false) },
            onDisconnect = { hub.disconnect() },
            onToggleOverlay = onToggleOverlay,
            onStartRecording = { hub.startRecording() },
            onTogglePause = { hub.togglePauseRecording() },
            onStopAndSave = { hub.stopAndSaveRecording() },
            onUpdateGoal = { hub.setWorkoutGoal(it) },
            onSelectProgram = { preset, scale -> hub.selectProgram(preset, scale) },
            onAdvanceProgramStep = { hub.advanceProgramStep() },
            onSelectGhostWorkout = { hub.selectGhostWorkout(it) },
            onOpenSettings = onOpenSettings,
        )
        AppTab.HISTORY -> {
            if (selectedWorkoutId != null) {
                WorkoutDetailScreen(
                    workoutId = selectedWorkoutId,
                    hub = hub,
                    onBack = onBackFromDetail,
                    onStartGhost = onStartGhostFromDetail,
                    onShowMessage = onShowMessage,
                )
            } else {
                HistoryScreen(
                    workouts = workouts,
                    onSelectWorkout = onSelectWorkout,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
        AppTab.SETTINGS -> SettingsScreen(
            settings = settings,
            diagnostics = diagnostics,
            connectionState = connectionState,
            isSimulatorMode = isSimulatorMode,
            workoutCount = workouts.size,
            isBackupBusy = isBackupBusy,
            onOpacityChange = { prefs.setOpacity(it) },
            onScaleChange = { prefs.setScale(it) },
            onShowWattsInMiniChange = { prefs.setShowWattsInMini(it) },
            onAutoStartChange = { prefs.setAutoStartRecording(it) },
            onAutoFinishMinutesChange = { prefs.setAutoFinishIdleMinutes(it) },
            onGoalSoundChange = { prefs.setGoalSoundEnabled(it) },
            onResetOverlayPosition = onResetOverlayPosition,
            onExportBackup = onExportBackup,
            onImportBackup = onImportBackup,
            onToggleSimulator = { enabled ->
                if (enabled) {
                    onStartSimulator()
                } else {
                    hub.setSimulatorMode(false)
                }
            },
            onConnectSimulator = onReconnectSimulator,
            onShowMessage = onShowMessage,
        )
    }
}
