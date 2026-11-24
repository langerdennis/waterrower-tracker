package com.waterrower.tracker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waterrower.tracker.data.OverlaySettings
import com.waterrower.tracker.data.RecordingState
import com.waterrower.tracker.data.WorkoutEntity
import com.waterrower.tracker.model.ConnectionState
import com.waterrower.tracker.model.GhostDelta
import com.waterrower.tracker.model.GhostReference
import com.waterrower.tracker.model.PaceZone
import com.waterrower.tracker.model.ProgramPreset
import com.waterrower.tracker.model.ProgramProgressState
import com.waterrower.tracker.model.ProgramScale
import com.waterrower.tracker.model.ProgramStep
import com.waterrower.tracker.model.RowingTelemetry
import com.waterrower.tracker.model.ScannedDevice
import com.waterrower.tracker.model.WorkoutGoal
import com.waterrower.tracker.ui.theme.LocalRowingColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    connectionState: ConnectionState,
    telemetry: RowingTelemetry,
    batteryPercent: Int?,
    scannedDevices: List<ScannedDevice>,
    recordingState: RecordingState,
    isOverlayVisible: Boolean,
    isSimulatorMode: Boolean,
    settings: OverlaySettings,
    activeGoal: WorkoutGoal,
    activeProgram: ProgramProgressState?,
    ghostReference: GhostReference?,
    ghostDelta: GhostDelta?,
    recentWorkouts: List<WorkoutEntity>,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectDevice: (address: String, name: String) -> Unit,
    onConnectSimulator: () -> Unit,
    onDisableSimulator: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleOverlay: () -> Unit,
    onStartRecording: () -> Unit,
    onTogglePause: () -> Unit,
    onStopAndSave: () -> Unit,
    onUpdateGoal: (WorkoutGoal) -> Unit,
    onSelectProgram: (ProgramPreset?, ProgramScale) -> Unit,
    onAdvanceProgramStep: () -> Unit,
    onSelectGhostWorkout: (String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val view = LocalView.current
    val shouldKeepScreenOn = connectionState is ConnectionState.Connected ||
        recordingState is RecordingState.Active

    DisposableEffect(view, shouldKeepScreenOn) {
        view.keepScreenOn = shouldKeepScreenOn
        onDispose {
            view.keepScreenOn = false
        }
    }

    val colorScheme = MaterialTheme.colorScheme
    val rowingColors = LocalRowingColors.current
    var showGoalDialog by remember { mutableStateOf(false) }

    if (showGoalDialog) {
        GoalAndGhostDialog(
            initialGoal = activeGoal,
            initialPreset = activeProgram?.plan?.preset,
            initialScale = activeProgram?.plan?.scale ?: ProgramScale.MEDIUM,
            initialGhostId = ghostReference?.workoutId,
            recentWorkouts = recentWorkouts,
            onDismiss = { showGoalDialog = false },
            onApply = { newGoal, preset, scale, ghostId ->
                onUpdateGoal(newGoal)
                if (preset != activeProgram?.plan?.preset ||
                    (preset != null && scale != activeProgram?.plan?.scale)
                ) {
                    onSelectProgram(preset, scale)
                }
                if (ghostId != ghostReference?.workoutId) {
                    onSelectGhostWorkout(ghostId)
                }
                showGoalDialog = false
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Unified Compact Cockpit Control Card
        CockpitControlCard(
            connectionState = connectionState,
            batteryPercent = batteryPercent,
            recordingState = recordingState,
            isOverlayVisible = isOverlayVisible,
            isSimulatorMode = isSimulatorMode,
            hasActiveGoalOrGhost = activeGoal.hasAnyTarget || activeProgram != null || ghostReference != null,
            lastDeviceAddress = settings.lastDeviceAddress,
            lastDeviceName = settings.lastDeviceName,
            onStartScan = onStartScan,
            onStopScan = onStopScan,
            onConnectDevice = onConnectDevice,
            onConnectSimulator = onConnectSimulator,
            onDisableSimulator = onDisableSimulator,
            onDisconnect = onDisconnect,
            onToggleOverlay = onToggleOverlay,
            onStartRecording = onStartRecording,
            onTogglePause = onTogglePause,
            onStopAndSave = onStopAndSave,
            onOpenGoalDialog = { showGoalDialog = true },
            onOpenSettings = onOpenSettings,
        )

        // 2. Scanned Devices List (visible when scanning or devices discovered while disconnected)
        AnimatedVisibility(
            visible = scannedDevices.isNotEmpty() && connectionState !is ConnectionState.Connected,
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Gefundene Rudergeräte",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    scannedDevices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(colorScheme.surfaceContainerHigh)
                                .clickable { onConnectDevice(device.address, device.name) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        text = device.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (device.hasFtmsService) {
                                        Surface(
                                            color = colorScheme.primaryContainer,
                                            contentColor = colorScheme.onPrimaryContainer,
                                            shape = RoundedCornerShape(6.dp),
                                        ) {
                                            Text(
                                                text = "FTMS",
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "${device.address}  •  ${device.rssi} dBm",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = colorScheme.onSurfaceVariant,
                                )
                            }
                            FilledTonalButton(
                                onClick = { onConnectDevice(device.address, device.name) },
                            ) {
                                Text("Verbinden")
                            }
                        }
                    }
                }
            }
        }

        // 3. Live Metrics Cockpit (uses workout deltas during active recording)
        val liveDistanceMeters = when (recordingState) {
            is RecordingState.Active -> recordingState.distanceMeters
            RecordingState.Idle -> telemetry.distanceMeters
        }
        val liveElapsedSeconds = when (recordingState) {
            is RecordingState.Active -> recordingState.elapsedSeconds
            RecordingState.Idle -> telemetry.elapsedSeconds
        }
        val displayDistance = RowingTelemetry.formatDistance(liveDistanceMeters)
        val displayTime = RowingTelemetry.formatDuration(liveElapsedSeconds)
        val displayCalories = when (recordingState) {
            is RecordingState.Active -> recordingState.calories
            RecordingState.Idle -> telemetry.calories
        }
        val displayStrokes = when (recordingState) {
            is RecordingState.Active -> recordingState.strokeCount
            RecordingState.Idle -> telemetry.strokeCount
        }
        val spmFormatted = String.format(Locale.US, "%.1f", telemetry.strokeRateSpm)

        // Active Program, Goal & Ghost Banner (compact, shown only when configured)
        AnimatedVisibility(visible = activeGoal.hasAnyTarget || activeProgram != null || ghostReference != null) {
            ActiveGoalAndGhostCard(
                goal = activeGoal,
                program = activeProgram,
                ghostReference = ghostReference,
                ghostDelta = ghostDelta,
                distanceMeters = liveDistanceMeters,
                elapsedSeconds = liveElapsedSeconds,
                currentPaceSec = telemetry.paceSecondsPer500m,
                onEdit = { showGoalDialog = true },
                onAdvanceProgramStep = onAdvanceProgramStep,
                onClearAll = {
                    onUpdateGoal(WorkoutGoal())
                    onSelectProgram(null, ProgramScale.MEDIUM)
                    onSelectGhostWorkout(null)
                },
            )
        }

        val hasHeartRate = telemetry.heartRateBpm != null

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isWide = maxWidth >= 680.dp
            val isCompactHero = maxWidth < 420.dp

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Hero Row: Pace + Distance
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MetricCard(
                        label = "PACE (/500m)",
                        value = telemetry.formattedPace,
                        icon = Icons.Rounded.Speed,
                        accent = rowingColors.pace,
                        containerColor = colorScheme.primaryContainer.copy(alpha = 0.28f),
                        borderColor = rowingColors.pace.copy(alpha = 0.35f),
                        isHero = true,
                        isWideHero = isWide,
                        isCompactHero = isCompactHero,
                        modifier = Modifier.weight(1f),
                    )
                    MetricCard(
                        label = "DISTANZ",
                        value = displayDistance,
                        icon = Icons.Rounded.Straighten,
                        accent = rowingColors.distance,
                        containerColor = colorScheme.secondaryContainer.copy(alpha = 0.28f),
                        borderColor = rowingColors.distance.copy(alpha = 0.35f),
                        isHero = true,
                        isWideHero = isWide,
                        isCompactHero = isCompactHero,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MetricCard("ZEIT", displayTime, Icons.Rounded.Timer, modifier = Modifier.weight(1f))
                        MetricCard("SCHLAGZAHL (SPM)", spmFormatted, Icons.Rounded.Waves, modifier = Modifier.weight(1f))
                        MetricCard("LEISTUNG", "${telemetry.watts} W", Icons.Rounded.Bolt, modifier = Modifier.weight(1f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MetricCard(
                            "KALORIEN",
                            "$displayCalories kcal",
                            Icons.Rounded.LocalFireDepartment,
                            modifier = Modifier.weight(1f),
                        )
                        MetricCard("SCHLÄGE", "$displayStrokes", Icons.Rounded.Repeat, modifier = Modifier.weight(1f))
                        if (hasHeartRate) {
                            MetricCard(
                                label = "HERZFREQUENZ",
                                value = "${telemetry.heartRateBpm} bpm",
                                icon = Icons.Rounded.Favorite,
                                accent = rowingColors.heartRate,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MetricCard("ZEIT", displayTime, Icons.Rounded.Timer, modifier = Modifier.weight(1f))
                        MetricCard("SCHLAGZAHL (SPM)", spmFormatted, Icons.Rounded.Waves, modifier = Modifier.weight(1f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MetricCard("LEISTUNG", "${telemetry.watts} W", Icons.Rounded.Bolt, modifier = Modifier.weight(1f))
                        MetricCard(
                            "KALORIEN",
                            "$displayCalories kcal",
                            Icons.Rounded.LocalFireDepartment,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MetricCard("SCHLÄGE", "$displayStrokes", Icons.Rounded.Repeat, modifier = Modifier.weight(1f))
                        if (hasHeartRate) {
                            MetricCard(
                                label = "HERZFREQUENZ",
                                value = "${telemetry.heartRateBpm} bpm",
                                icon = Icons.Rounded.Favorite,
                                accent = rowingColors.heartRate,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CockpitControlCard(
    connectionState: ConnectionState,
    batteryPercent: Int?,
    recordingState: RecordingState,
    isOverlayVisible: Boolean,
    isSimulatorMode: Boolean,
    hasActiveGoalOrGhost: Boolean,
    lastDeviceAddress: String?,
    lastDeviceName: String?,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectDevice: (String, String) -> Unit,
    onConnectSimulator: () -> Unit,
    onDisableSimulator: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleOverlay: () -> Unit,
    onStartRecording: () -> Unit,
    onTogglePause: () -> Unit,
    onStopAndSave: () -> Unit,
    onOpenGoalDialog: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val rowingColors = LocalRowingColors.current

    val (statusText, statusColor, statusIcon) = when (connectionState) {
        is ConnectionState.Connected -> Triple(
            "Verbunden: ${connectionState.deviceName}",
            rowingColors.active,
            Icons.Rounded.BluetoothConnected,
        )
        is ConnectionState.Connecting -> Triple(
            "Verbinde mit ${connectionState.deviceName}…",
            rowingColors.warning,
            Icons.AutoMirrored.Rounded.BluetoothSearching,
        )
        ConnectionState.Scanning -> Triple(
            "Suche nach WaterRower S4 (FTMS)…",
            colorScheme.primary,
            Icons.AutoMirrored.Rounded.BluetoothSearching,
        )
        is ConnectionState.Error -> Triple(
            connectionState.message,
            colorScheme.error,
            Icons.Rounded.Bluetooth,
        )
        ConnectionState.Disconnected -> Triple(
            if (isSimulatorMode) "Simulator bereit (getrennt)" else "Nicht verbunden",
            colorScheme.onSurfaceVariant,
            Icons.Rounded.Bluetooth,
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Top Status, Badges & Settings shortcut Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(11.dp)
                                .clip(CircleShape)
                                .background(statusColor),
                        )
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    if (recordingState is RecordingState.Active) {
                        val pillBg = if (recordingState.isPaused) {
                            colorScheme.tertiaryContainer
                        } else {
                            colorScheme.secondaryContainer
                        }
                        val pillFg = if (recordingState.isPaused) {
                            colorScheme.onTertiaryContainer
                        } else {
                            colorScheme.onSecondaryContainer
                        }
                        val pillDot = if (recordingState.isPaused) {
                            rowingColors.warning
                        } else {
                            rowingColors.recording
                        }
                        val pillLabel = when {
                            recordingState.isAutoPaused -> "AUTO-PAUSE"
                            recordingState.isPaused -> "PAUSIERT"
                            else -> "REC • ${RowingTelemetry.formatDuration(recordingState.elapsedSeconds)}"
                        }
                        Surface(
                            color = pillBg,
                            contentColor = pillFg,
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(pillDot),
                                )
                                Text(
                                    text = pillLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }

                    if (batteryPercent != null) {
                        Surface(
                            color = colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.BatteryStd,
                                    contentDescription = "ComModule Akku",
                                    tint = if (batteryPercent <= 20) rowingColors.warning else rowingColors.active,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = "$batteryPercent%",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }

                    if (isSimulatorMode) {
                        Surface(
                            onClick = onDisableSimulator,
                            color = colorScheme.tertiaryContainer,
                            contentColor = colorScheme.onTertiaryContainer,
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.Science,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                )
                                Text(
                                    text = "Simulator",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = "Simulator beenden",
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }

                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "Einstellungen öffnen",
                        tint = colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.45f))

            // Unified Action Bar: Connection + Overlay + Goal/Ghost + Recording
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (connectionState) {
                    is ConnectionState.Connected, is ConnectionState.Connecting -> {
                        OutlinedButton(onClick = onDisconnect) {
                            Text("Trennen")
                        }
                    }
                    ConnectionState.Scanning -> {
                        OutlinedButton(onClick = onStopScan) {
                            Text("Scan stoppen")
                        }
                    }
                    else -> {
                        if (isSimulatorMode) {
                            Button(onClick = onConnectSimulator) {
                                Icon(Icons.Rounded.Science, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Simulator verbinden")
                            }
                        } else {
                            Button(onClick = onStartScan) {
                                Icon(Icons.AutoMirrored.Rounded.BluetoothSearching, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("S4 Comms suchen")
                            }
                            if (lastDeviceAddress != null && lastDeviceName != null) {
                                FilledTonalButton(
                                    onClick = { onConnectDevice(lastDeviceAddress, lastDeviceName) },
                                ) {
                                    Text("Erneut: $lastDeviceName")
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = onToggleOverlay,
                    colors = if (isOverlayVisible) {
                        ButtonDefaults.buttonColors(
                            containerColor = colorScheme.tertiary,
                            contentColor = colorScheme.onTertiary,
                        )
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = colorScheme.primary,
                            contentColor = colorScheme.onPrimary,
                        )
                    },
                ) {
                    Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isOverlayVisible) "Overlay ausblenden" else "Floating Overlay",
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (hasActiveGoalOrGhost) {
                    FilledTonalButton(onClick = onOpenGoalDialog) {
                        Icon(Icons.Rounded.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ziel / Programm")
                    }
                } else {
                    OutlinedButton(onClick = onOpenGoalDialog) {
                        Icon(Icons.Rounded.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ziel / Programm")
                    }
                }

                when (recordingState) {
                    RecordingState.Idle -> {
                        FilledTonalButton(onClick = onStartRecording) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Aufnahme starten")
                        }
                    }
                    is RecordingState.Active -> {
                        FilledTonalButton(onClick = onTogglePause) {
                            Icon(
                                if (recordingState.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                contentDescription = null,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (recordingState.isPaused) "Fortsetzen" else "Pause")
                        }
                        Button(
                            onClick = onStopAndSave,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colorScheme.secondary,
                                contentColor = colorScheme.onSecondary,
                            ),
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Stop & Speichern", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveGoalAndGhostCard(
    goal: WorkoutGoal,
    program: ProgramProgressState?,
    ghostReference: GhostReference?,
    ghostDelta: GhostDelta?,
    distanceMeters: Int,
    elapsedSeconds: Int,
    currentPaceSec: Int,
    onEdit: () -> Unit,
    onAdvanceProgramStep: () -> Unit,
    onClearAll: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val rowingColors = LocalRowingColors.current
    val goalProgress = goal.progressFraction(distanceMeters, elapsedSeconds)
    val goalCompleted = goal.isCompleted(distanceMeters, elapsedSeconds)
    val remaining = goal.remainingSummary(distanceMeters, elapsedSeconds)
    val paceZone = goal.evaluatePaceZone(currentPaceSec)

    val progress = program?.stepProgressFraction ?: goalProgress
    val isCompleted = program?.isCompleted == true || (program == null && goalCompleted)
    val programAccent = when {
        program == null -> colorScheme.primary
        program.isCompleted -> rowingColors.active
        program.currentStep is ProgramStep.TimedRest -> rowingColors.warning
        program.currentStep is ProgramStep.OffRowerStation -> colorScheme.tertiary
        else -> colorScheme.primary
    }

    Card(
        onClick = onEdit,
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            1.dp,
            when {
                isCompleted -> rowingColors.active.copy(alpha = 0.65f)
                program?.currentStep is ProgramStep.TimedRest -> rowingColors.warning.copy(alpha = 0.6f)
                program?.currentStep is ProgramStep.OffRowerStation -> colorScheme.tertiary.copy(alpha = 0.6f)
                paceZone == PaceZone.ON_TARGET -> rowingColors.active.copy(alpha = 0.55f)
                paceZone == PaceZone.SLOWER -> rowingColors.warning.copy(alpha = 0.55f)
                else -> colorScheme.outlineVariant.copy(alpha = 0.55f)
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (program != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Flag,
                                contentDescription = null,
                                tint = programAccent,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = program.plan.titleWithScale,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        val stepLabel = if (program.isCompleted) {
                            "✓ Fertig"
                        } else {
                            when (val step = program.currentStep) {
                                is ProgramStep.RowingDistance -> {
                                    val remM = (step.targetMeters - program.stepDistanceMeters).coerceAtLeast(0)
                                    "R${step.roundIndex}/${step.totalRounds} • noch ${RowingTelemetry.formatDistance(remM)}"
                                }
                                is ProgramStep.TimedRest ->
                                    "Pause ${RowingTelemetry.formatDuration(program.restRemainingSeconds)} • danach ${step.nextDescription}"
                                is ProgramStep.OffRowerStation ->
                                    "R${step.roundIndex}/${step.totalRounds}: ${step.exerciseLabel}"
                            }
                        }

                        Surface(
                            color = programAccent.copy(alpha = 0.16f),
                            contentColor = programAccent,
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text(
                                text = stepLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                        }
                    }

                    if (goal.hasAnyTarget) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Flag,
                                contentDescription = null,
                                tint = if (goalCompleted) rowingColors.active else colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                text = "Ziel: ${goal.shortSummary()}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    if (program == null && remaining != null) {
                        Surface(
                            color = if (goalCompleted) colorScheme.secondaryContainer else colorScheme.primaryContainer,
                            contentColor = if (goalCompleted) colorScheme.onSecondaryContainer else colorScheme.onPrimaryContainer,
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text(
                                text = remaining,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                        }
                    }

                    if (paceZone != PaceZone.NONE) {
                        val (zoneLabel, zoneColor) = when (paceZone) {
                            PaceZone.ON_TARGET -> "Pace im Ziel" to rowingColors.active
                            PaceZone.FASTER -> "Schneller als Ziel" to rowingColors.pace
                            PaceZone.SLOWER -> "Langsamer als Ziel" to rowingColors.warning
                            PaceZone.NONE -> "" to colorScheme.onSurfaceVariant
                        }
                        Surface(
                            color = zoneColor.copy(alpha = 0.16f),
                            contentColor = zoneColor,
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text(
                                text = zoneLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                        }
                    }

                    if (ghostReference != null) {
                        val ghostColor = when {
                            ghostDelta == null -> colorScheme.onSurfaceVariant
                            ghostDelta.deltaMeters > 0 -> rowingColors.active
                            ghostDelta.deltaMeters < 0 -> rowingColors.warning
                            else -> rowingColors.pace
                        }
                        val ghostText = if (ghostDelta != null) {
                            "Ghost: ${ghostDelta.formattedCompact}" + if (ghostDelta.ghostFinished) " (Ziel)" else ""
                        } else {
                            "Ghost: ${ghostReference.label}"
                        }
                        Surface(
                            color = ghostColor.copy(alpha = 0.16f),
                            contentColor = ghostColor,
                            shape = RoundedCornerShape(999.dp),
                        ) {
                            Text(
                                text = ghostText,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (program != null && !program.isCompleted) {
                        TextButton(onClick = onAdvanceProgramStep) {
                            Text("Nächster")
                        }
                    }
                    IconButton(
                        onClick = onClearAll,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Ziel, Programm & Ghost entfernen",
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            if (progress != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(999.dp)),
                        color = if (program != null) programAccent else if (isCompleted) rowingColors.active else colorScheme.primary,
                        trackColor = colorScheme.surfaceContainerHighest,
                        strokeCap = StrokeCap.Round,
                    )
                    Text(
                        text = "${(progress * 100f).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isCompleted) rowingColors.active else colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalAndGhostDialog(
    initialGoal: WorkoutGoal,
    initialPreset: ProgramPreset?,
    initialScale: ProgramScale,
    initialGhostId: String?,
    recentWorkouts: List<WorkoutEntity>,
    onDismiss: () -> Unit,
    onApply: (WorkoutGoal, ProgramPreset?, ProgramScale, String?) -> Unit,
) {
    var selectedPreset by remember { mutableStateOf(initialPreset) }
    var selectedScale by remember { mutableStateOf(initialScale) }
    var distTarget by remember { mutableStateOf(if (initialPreset != null) null else initialGoal.distanceTargetMeters) }
    var durTarget by remember { mutableStateOf(if (initialPreset != null) null else initialGoal.durationTargetSeconds) }
    var paceTarget by remember { mutableStateOf(initialGoal.targetPaceSecPer500m) }
    var selectedGhostId by remember { mutableStateOf(initialGhostId) }

    val dateFormat = remember { SimpleDateFormat("dd.MM.", Locale.GERMANY) }
    val ghostCandidates = remember(recentWorkouts) {
        recentWorkouts.filter { it.totalDistanceMeters >= 100 && it.durationSeconds >= 10 }.take(6)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Flag, contentDescription = null) },
        title = { Text("Ziel, Programm & Ghost") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 1. Curated Programs (Intervals & Hybrid Row + 16 kg KB/Mat) with Length Scaling
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Programm (Intervall & Hybrid)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = selectedPreset == null,
                            onClick = { selectedPreset = null },
                            label = { Text("Aus") },
                        )
                        ProgramPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = selectedPreset == preset,
                                onClick = {
                                    selectedPreset = preset
                                    distTarget = null
                                    durTarget = null
                                },
                                label = { Text(preset.title) },
                            )
                        }
                    }

                    if (selectedPreset != null) {
                        val preview = remember(selectedPreset, selectedScale) {
                            selectedPreset!!.buildPlan(selectedScale)
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ProgramScale.entries.forEach { scale ->
                                FilterChip(
                                    selected = selectedScale == scale,
                                    onClick = { selectedScale = scale },
                                    label = { Text(scale.label) },
                                )
                            }
                        }
                        Text(
                            text = "${preview.detailSummary} • ca. ${preview.estimatedMinutes} Min.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                        if (selectedPreset!!.isHybrid) {
                            Text(
                                text = "Nach der Übung einfach wieder ziehen – das Overlay startet automatisch die nächste Runde.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalDivider()

                // 2. Distance target (free workout)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Distanz-Ziel", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(null, 2000, 5000, 10000).forEach { meters ->
                            FilterChip(
                                selected = distTarget == meters && selectedPreset == null,
                                onClick = {
                                    distTarget = meters
                                    if (meters != null) {
                                        durTarget = null
                                        selectedPreset = null
                                    }
                                },
                                label = { Text(meters?.let { RowingTelemetry.formatDistance(it) } ?: "Aus") },
                            )
                        }
                    }
                    if (distTarget != null) {
                        StepperRow(
                            label = RowingTelemetry.formatDistance(distTarget!!),
                            onDecrement = { distTarget = (distTarget!! - 500).coerceAtLeast(500) },
                            onIncrement = { distTarget = (distTarget!! + 500).coerceAtMost(50_000) },
                        )
                    }
                }

                // 3. Duration target (free workout)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Zeit-Ziel", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(null, 20 * 60, 30 * 60, 45 * 60).forEach { secs ->
                            FilterChip(
                                selected = durTarget == secs && selectedPreset == null,
                                onClick = {
                                    durTarget = secs
                                    if (secs != null) {
                                        distTarget = null
                                        selectedPreset = null
                                    }
                                },
                                label = { Text(secs?.let { "${it / 60} Min" } ?: "Aus") },
                            )
                        }
                    }
                    if (durTarget != null) {
                        StepperRow(
                            label = "${durTarget!! / 60} Min",
                            onDecrement = { durTarget = (durTarget!! - 5 * 60).coerceAtLeast(5 * 60) },
                            onIncrement = { durTarget = (durTarget!! + 5 * 60).coerceAtMost(180 * 60) },
                        )
                    }
                }

                // 4. Target Pace (/500m)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Ziel-Pace (/500m, ±3 s)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(null, 120, 125, 130, 135).forEach { paceSec ->
                            FilterChip(
                                selected = paceTarget == paceSec,
                                onClick = { paceTarget = paceSec },
                                label = { Text(paceSec?.let { RowingTelemetry.formatPace(it) } ?: "Aus") },
                            )
                        }
                    }
                    if (paceTarget != null) {
                        StepperRow(
                            label = "${RowingTelemetry.formatPace(paceTarget!!)} /500m",
                            onDecrement = { paceTarget = (paceTarget!! - 5).coerceAtLeast(80) },
                            onIncrement = { paceTarget = (paceTarget!! + 5).coerceAtMost(240) },
                        )
                    }
                }

                HorizontalDivider()

                // 5. Ghost Racer
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Ghost-Racer (gegen frühere Einheit)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (ghostCandidates.isEmpty()) {
                        Text(
                            text = "Noch keine gespeicherte Einheit vorhanden.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            FilterChip(
                                selected = selectedGhostId == null,
                                onClick = { selectedGhostId = null },
                                label = { Text("Kein Ghost") },
                            )
                            ghostCandidates.forEach { w ->
                                val chipText =
                                    "${dateFormat.format(Date(w.startedAtEpochMs))} • ${RowingTelemetry.formatDistance(w.totalDistanceMeters)}"
                                FilterChip(
                                    selected = selectedGhostId == w.id,
                                    onClick = { selectedGhostId = w.id },
                                    label = { Text(chipText) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onApply(
                        WorkoutGoal(
                            distanceTargetMeters = if (selectedPreset != null) null else distTarget,
                            durationTargetSeconds = if (selectedPreset != null) null else durTarget,
                            targetPaceSecPer500m = paceTarget,
                        ),
                        selectedPreset,
                        selectedScale,
                        selectedGhostId,
                    )
                },
            ) {
                Text("Übernehmen")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Abbrechen")
            }
        },
    )
}

@Composable
private fun StepperRow(
    label: String,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(onClick = onDecrement) {
            Icon(Icons.Rounded.Remove, contentDescription = "Weniger", modifier = Modifier.size(16.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedButton(onClick = onIncrement) {
            Icon(Icons.Rounded.Add, contentDescription = "Mehr", modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.onSurface,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    borderColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
    isHero: Boolean = false,
    isWideHero: Boolean = false,
    isCompactHero: Boolean = false,
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 18.dp,
                vertical = if (isHero) 18.dp else 14.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isHero || accent != colorScheme.onSurface) accent else colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSurfaceVariant,
                    letterSpacing = 0.8.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = value,
                style = when {
                    isHero && isWideHero -> MaterialTheme.typography.displayLarge
                    isHero && isCompactHero -> MaterialTheme.typography.headlineMedium
                    isHero -> MaterialTheme.typography.displayMedium
                    else -> MaterialTheme.typography.headlineMedium
                },
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
