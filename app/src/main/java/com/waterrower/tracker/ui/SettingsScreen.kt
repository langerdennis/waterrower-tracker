package com.waterrower.tracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waterrower.tracker.data.OverlaySettings
import com.waterrower.tracker.model.BleDiagnostics
import com.waterrower.tracker.ui.theme.SkyAccent
import com.waterrower.tracker.ui.theme.SlateSurface
import com.waterrower.tracker.ui.theme.TextPrimary
import com.waterrower.tracker.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: OverlaySettings,
    diagnostics: BleDiagnostics,
    onOpacityChange: (Float) -> Unit,
    onScaleChange: (Float) -> Unit,
    onShowWattsInMiniChange: (Boolean) -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 1. Floating Overlay Appearance
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = "Floating Overlay (über Nuvio)",
                    style = MaterialTheme.typography.titleLarge,
                )

                // Live Preview Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF0B121A).copy(alpha = settings.opacity))
                        .border(1.dp, SkyAccent.copy(alpha = settings.opacity), RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    val wattsPart = if (settings.showWattsInMini) "  •  185 W" else ""
                    Text(
                        text = "2:04 /500m  •  26 spm  •  1.420 m  •  06:12$wattsPart",
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = (15f * settings.scale).sp,
                    )
                }

                Column {
                    Text(
                        text = "Deckkraft: ${(settings.opacity * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = settings.opacity,
                        onValueChange = onOpacityChange,
                        valueRange = 0.35f..1.0f,
                    )
                }

                Column {
                    Text(
                        text = "Schriftgröße: ${(settings.scale * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Slider(
                        value = settings.scale,
                        onValueChange = onScaleChange,
                        valueRange = 0.8f..1.4f,
                    )
                }

                SettingToggleRow(
                    title = "Watt auch in der Mini-Leiste anzeigen",
                    subtitle = "Sonst sichtbar durch Antippen des Overlays",
                    checked = settings.showWattsInMini,
                    onCheckedChange = onShowWattsInMiniChange,
                )

                SettingToggleRow(
                    title = "Aufzeichnung automatisch starten",
                    subtitle = "Beginnt automatisch, sobald du am Rower ziehst",
                    checked = settings.autoStartRecording,
                    onCheckedChange = onAutoStartChange,
                )
            }
        }

        // 2. BLE Diagnostics for S4 Comms 61
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "BLE-Diagnose (S4 Comms 61 / FTMS)",
                    style = MaterialTheme.typography.titleLarge,
                )
                DiagLine("Empfangene 0x2AD1-Pakete", "${diagnostics.packetCount}")
                DiagLine("Firmware", diagnostics.firmwareRevision ?: "--")
                DiagLine("Letzte FTMS Flags", diagnostics.lastParsedFlagsHex.ifBlank { "--" })
                DiagLine("Letztes Rohpaket (Hex)", diagnostics.lastRawHex.ifBlank { "--" })
                if (diagnostics.discoveredServices.isNotEmpty()) {
                    Text(
                        text = "Erkannte GATT-Services:",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                    )
                    diagnostics.discoveredServices.forEach { svc ->
                        Text(
                            text = "• $svc",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DiagLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
