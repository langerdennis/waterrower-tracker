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
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waterrower.tracker.data.OverlaySettings
import com.waterrower.tracker.data.RecordingState
import com.waterrower.tracker.model.ConnectionState
import com.waterrower.tracker.model.RowingTelemetry
import com.waterrower.tracker.model.ScannedDevice
import com.waterrower.tracker.ui.theme.LocalRowingColors
import java.util.Locale

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
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectDevice: (address: String, name: String) -> Unit,
    onConnectSimulator: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleSimulator: (Boolean) -> Unit,
    onToggleOverlay: () -> Unit,
    onStartRecording: () -> Unit,
    onTogglePause: () -> Unit,
    onStopAndSave: () -> Unit,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. Unified Compact Cockpit Control Card (fits all 8 live metrics above the fold on tablets)
        CockpitControlCard(
            connectionState = connectionState,
            batteryPercent = batteryPercent,
            recordingState = recordingState,
            isOverlayVisible = isOverlayVisible,
            isSimulatorMode = isSimulatorMode,
            lastDeviceAddress = settings.lastDeviceAddress,
            lastDeviceName = settings.lastDeviceName,
            onStartScan = onStartScan,
            onStopScan = onStopScan,
            onConnectDevice = onConnectDevice,
            onConnectSimulator = onConnectSimulator,
            onDisconnect = onDisconnect,
            onToggleSimulator = onToggleSimulator,
            onToggleOverlay = onToggleOverlay,
            onStartRecording = onStartRecording,
            onTogglePause = onTogglePause,
            onStopAndSave = onStopAndSave,
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
        val displayDistance = when (recordingState) {
            is RecordingState.Active -> RowingTelemetry.formatDistance(recordingState.distanceMeters)
            RecordingState.Idle -> telemetry.formattedDistance
        }
        val displayTime = when (recordingState) {
            is RecordingState.Active -> RowingTelemetry.formatDuration(recordingState.elapsedSeconds)
            RecordingState.Idle -> telemetry.formattedElapsed
        }
        val displayCalories = when (recordingState) {
            is RecordingState.Active -> recordingState.calories
            RecordingState.Idle -> telemetry.calories
        }
        val displayStrokes = when (recordingState) {
            is RecordingState.Active -> recordingState.strokeCount
            RecordingState.Idle -> telemetry.strokeCount
        }
        val spmFormatted = String.format(Locale.US, "%.1f", telemetry.strokeRateSpm)

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
    lastDeviceAddress: String?,
    lastDeviceName: String?,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectDevice: (String, String) -> Unit,
    onConnectSimulator: () -> Unit,
    onDisconnect: () -> Unit,
    onToggleSimulator: (Boolean) -> Unit,
    onToggleOverlay: () -> Unit,
    onStartRecording: () -> Unit,
    onTogglePause: () -> Unit,
    onStopAndSave: () -> Unit,
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
            // Top Status & Badges Row
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
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

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
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

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Science,
                            contentDescription = null,
                            tint = if (isSimulatorMode) colorScheme.primary else colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = "Simulator",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSimulatorMode) colorScheme.primary else colorScheme.onSurfaceVariant,
                        )
                        Switch(
                            checked = isSimulatorMode,
                            onCheckedChange = onToggleSimulator,
                        )
                    }
                }
            }

            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.45f))

            // Unified Action Bar: Connection + Overlay + Recording
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
