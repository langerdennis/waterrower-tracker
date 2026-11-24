package com.waterrower.tracker.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waterrower.tracker.data.OverlaySettings
import com.waterrower.tracker.data.RecordingState
import com.waterrower.tracker.model.ConnectionState
import com.waterrower.tracker.model.RowingTelemetry
import com.waterrower.tracker.model.ScannedDevice
import com.waterrower.tracker.ui.theme.AmberWarn
import com.waterrower.tracker.ui.theme.CoralDanger
import com.waterrower.tracker.ui.theme.EmeraldActive
import com.waterrower.tracker.ui.theme.SkyAccent
import com.waterrower.tracker.ui.theme.SlateSurface
import com.waterrower.tracker.ui.theme.SlateSurfaceVariant
import com.waterrower.tracker.ui.theme.TextSecondary
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
    onDisconnect: () -> Unit,
    onToggleSimulator: (Boolean) -> Unit,
    onToggleOverlay: () -> Unit,
    onStartRecording: () -> Unit,
    onTogglePause: () -> Unit,
    onStopAndSave: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. Connection & Controls Card
        ConnectionHeaderCard(
            connectionState = connectionState,
            batteryPercent = batteryPercent,
            isSimulatorMode = isSimulatorMode,
            lastDeviceAddress = settings.lastDeviceAddress,
            lastDeviceName = settings.lastDeviceName,
            onStartScan = onStartScan,
            onStopScan = onStopScan,
            onConnectDevice = onConnectDevice,
            onDisconnect = onDisconnect,
            onToggleSimulator = onToggleSimulator,
        )

        // 2. Scanned Devices List (visible when scanning or devices discovered while disconnected)
        if (scannedDevices.isNotEmpty() && connectionState !is ConnectionState.Connected) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Gefundene Rudergeräte",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    scannedDevices.forEach { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(SlateSurfaceVariant)
                                .clickable { onConnectDevice(device.address, device.name) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(
                                    text = device.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "${device.address}  •  ${device.rssi} dBm${if (device.hasFtmsService) "  •  FTMS 0x1826" else ""}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
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

        // 3. Primary Actions: Floating Overlay + Workout Recording
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = onToggleOverlay,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isOverlayVisible) AmberWarn else SkyAccent,
                        contentColor = Color(0xFF04131E),
                    ),
                ) {
                    Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (isOverlayVisible) "Floating Overlay ausblenden" else "Floating Overlay starten",
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
                                containerColor = EmeraldActive,
                                contentColor = Color(0xFF052E16),
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

        // 4. Live Metrics Grid (adaptive 2 or 3 columns for tablets)
        val displayDistance = when (recordingState) {
            is RecordingState.Active -> RowingTelemetry.formatDistance(recordingState.distanceMeters)
            RecordingState.Idle -> telemetry.formattedDistance
        }
        val displayTime = when (recordingState) {
            is RecordingState.Active -> RowingTelemetry.formatDuration(recordingState.elapsedSeconds)
            RecordingState.Idle -> telemetry.formattedElapsed
        }
        val spmFormatted = String.format(Locale.US, "%.1f", telemetry.strokeRateSpm)

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val isWide = maxWidth >= 680.dp

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Hero Row: Pace + Distance
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MetricCard(
                        label = "PACE (/500m)",
                        value = telemetry.formattedPace,
                        accent = SkyAccent,
                        isHero = true,
                        modifier = Modifier.weight(1f),
                    )
                    MetricCard(
                        label = "DISTANZ",
                        value = displayDistance,
                        accent = EmeraldActive,
                        isHero = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MetricCard("ZEIT", displayTime, modifier = Modifier.weight(1f))
                        MetricCard("SCHLAGZAHL (SPM)", spmFormatted, modifier = Modifier.weight(1f))
                        MetricCard("LEISTUNG", "${telemetry.watts} W", modifier = Modifier.weight(1f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MetricCard("KALORIEN", "${telemetry.calories} kcal", modifier = Modifier.weight(1f))
                        MetricCard("SCHLÄGE", "${telemetry.strokeCount}", modifier = Modifier.weight(1f))
                        MetricCard(
                            label = "HERZFREQUENZ",
                            value = telemetry.heartRateBpm?.let { "$it bpm" } ?: "--",
                            accent = if (telemetry.heartRateBpm != null) CoralDanger else TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MetricCard("ZEIT", displayTime, modifier = Modifier.weight(1f))
                        MetricCard("SCHLAGZAHL (SPM)", spmFormatted, modifier = Modifier.weight(1f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MetricCard("LEISTUNG", "${telemetry.watts} W", modifier = Modifier.weight(1f))
                        MetricCard("KALORIEN", "${telemetry.calories} kcal", modifier = Modifier.weight(1f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MetricCard("SCHLÄGE", "${telemetry.strokeCount}", modifier = Modifier.weight(1f))
                        MetricCard(
                            label = "HERZFREQUENZ",
                            value = telemetry.heartRateBpm?.let { "$it bpm" } ?: "--",
                            accent = if (telemetry.heartRateBpm != null) CoralDanger else TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionHeaderCard(
    connectionState: ConnectionState,
    batteryPercent: Int?,
    isSimulatorMode: Boolean,
    lastDeviceAddress: String?,
    lastDeviceName: String?,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnectDevice: (String, String) -> Unit,
    onDisconnect: () -> Unit,
    onToggleSimulator: (Boolean) -> Unit,
) {
    val (statusText, statusColor, statusIcon) = when (connectionState) {
        is ConnectionState.Connected -> Triple(
            "Verbunden: ${connectionState.deviceName}",
            EmeraldActive,
            Icons.Rounded.BluetoothConnected,
        )
        is ConnectionState.Connecting -> Triple(
            "Verbinde mit ${connectionState.deviceName}…",
            AmberWarn,
            Icons.Rounded.BluetoothSearching,
        )
        ConnectionState.Scanning -> Triple(
            "Suche nach WaterRower S4 (FTMS)…",
            SkyAccent,
            Icons.Rounded.BluetoothSearching,
        )
        is ConnectionState.Error -> Triple(
            connectionState.message,
            CoralDanger,
            Icons.Rounded.Bluetooth,
        )
        ConnectionState.Disconnected -> Triple(
            "Nicht verbunden",
            TextSecondary,
            Icons.Rounded.Bluetooth,
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                    )
                    Icon(statusIcon, contentDescription = null, tint = statusColor)
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                if (batteryPercent != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Rounded.BatteryStd,
                            contentDescription = "ComModule Akku",
                            tint = if (batteryPercent <= 20) AmberWarn else EmeraldActive,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            text = "$batteryPercent%",
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
                        Button(
                            onClick = onStartScan,
                            enabled = !isSimulatorMode,
                        ) {
                            Icon(Icons.Rounded.BluetoothSearching, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("S4 Comms suchen")
                        }
                        if (!isSimulatorMode && lastDeviceAddress != null && lastDeviceName != null) {
                            FilledTonalButton(
                                onClick = { onConnectDevice(lastDeviceAddress, lastDeviceName) },
                            ) {
                                Text("Erneut: $lastDeviceName")
                            }
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Rounded.Science,
                        contentDescription = null,
                        tint = if (isSimulatorMode) SkyAccent else TextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = "Simulator",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSimulatorMode) SkyAccent else TextSecondary,
                    )
                    Switch(
                        checked = isSimulatorMode,
                        onCheckedChange = onToggleSimulator,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.onSurface,
    isHero: Boolean = false,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = 18.dp,
                vertical = if (isHero) 20.dp else 14.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (label == "HERZFREQUENZ") {
                    Icon(
                        Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                    letterSpacing = 0.8.sp,
                )
            }
            Text(
                text = value,
                style = if (isHero) {
                    MaterialTheme.typography.displayMedium
                } else {
                    MaterialTheme.typography.headlineMedium
                },
                color = accent,
                maxLines = 1,
            )
        }
    }
}
