package com.waterrower.tracker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waterrower.tracker.data.OverlaySettings
import com.waterrower.tracker.model.BleDiagnostics
import com.waterrower.tracker.ui.theme.LocalRowingColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: OverlaySettings,
    diagnostics: BleDiagnostics,
    onOpacityChange: (Float) -> Unit,
    onScaleChange: (Float) -> Unit,
    onShowWattsInMiniChange: (Boolean) -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
    onResetOverlayPosition: () -> Unit,
    onShowMessage: (String) -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val rowingColors = LocalRowingColors.current
    val clipboardManager = LocalClipboardManager.current
    val packetTimeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Einstellungen",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            // 1. Floating Overlay Appearance & Position
            Card(
                colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PictureInPictureAlt,
                            contentDescription = null,
                            tint = colorScheme.primary,
                        )
                        Text(
                            text = "Floating Overlay",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }

                    // Simulated fullscreen video backdrop for live overlay preview
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF0A1622),
                                        Color(0xFF152A3E),
                                        Color(0xFF0B1F2E),
                                    ),
                                ),
                            )
                            .border(
                                1.dp,
                                colorScheme.outlineVariant.copy(alpha = 0.6f),
                                MaterialTheme.shapes.medium,
                            )
                            .padding(16.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Live Preview Pill
                            Box(
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xFF0B121A).copy(alpha = settings.opacity))
                                    .border(
                                        1.dp,
                                        Color(0xFF38BDF8).copy(alpha = settings.opacity),
                                        RoundedCornerShape(16.dp),
                                    )
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(9.dp)
                                            .clip(CircleShape)
                                            .background(rowingColors.active),
                                    )
                                    val wattsPart = if (settings.showWattsInMini) "  •  185 W" else ""
                                    Text(
                                        text = "2:04 /500m  •  26 spm  •  1.420 m  •  06:12$wattsPart",
                                        color = Color(0xFFF8FAFC),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = (15f * settings.scale).sp,
                                        maxLines = 1,
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayCircleOutline,
                                    contentDescription = null,
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = "Vorschau über Video-App",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8),
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Deckkraft",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "${(settings.opacity * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                                color = colorScheme.primary,
                            )
                        }
                        Slider(
                            value = settings.opacity,
                            onValueChange = onOpacityChange,
                            valueRange = 0.35f..1.0f,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = "Schriftgröße",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "${(settings.scale * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelLarge,
                                color = colorScheme.primary,
                            )
                        }
                        Slider(
                            value = settings.scale,
                            onValueChange = onScaleChange,
                            valueRange = 0.8f..1.4f,
                        )
                    }

                    HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))

                    SettingToggleRow(
                        title = "Watt auch in der Mini-Leiste anzeigen",
                        subtitle = "Sonst sichtbar durch Antippen des Overlays",
                        checked = settings.showWattsInMini,
                        onCheckedChange = onShowWattsInMiniChange,
                    )

                    SettingToggleRow(
                        title = "Aufzeichnung automatisch starten",
                        subtitle = "Beginnt automatisch bei echter Ruderbewegung",
                        checked = settings.autoStartRecording,
                        onCheckedChange = onAutoStartChange,
                    )

                    HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(end = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = "Overlay-Position",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "Aktuell: X=${settings.overlayX} px, Y=${settings.overlayY} px",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = onResetOverlayPosition) {
                            Icon(
                                imageVector = Icons.Rounded.RestartAlt,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Overlay-Position zurücksetzen")
                        }
                    }
                }
            }

            // 2. Collapsible BLE Diagnostics for S4 Comms 61
            Card(
                colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { diagnosticsExpanded = !diagnosticsExpanded }
                            .padding(horizontal = 18.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.BugReport,
                                contentDescription = null,
                                tint = colorScheme.primary,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = "BLE-Diagnose (S4 Comms 61 / FTMS)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = "${diagnostics.packetCount} Pakete  •  FW ${diagnostics.firmwareRevision ?: "--"}" +
                                        if (diagnostics.truncatedPacketCount > 0L) {
                                            "  •  ${diagnostics.truncatedPacketCount} abgeschnitten"
                                        } else {
                                            ""
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Icon(
                            imageVector = if (diagnosticsExpanded) {
                                Icons.Rounded.ExpandLess
                            } else {
                                Icons.Rounded.ExpandMore
                            },
                            contentDescription = if (diagnosticsExpanded) "Einklappen" else "Aufklappen",
                            tint = colorScheme.onSurfaceVariant,
                        )
                    }

                    AnimatedVisibility(visible = diagnosticsExpanded) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))

                            DiagLine("Empfangene Pakete", "${diagnostics.packetCount}")
                            if (diagnostics.truncatedPacketCount > 0L) {
                                DiagLine("Abgeschnittene Pakete", "${diagnostics.truncatedPacketCount}")
                            }
                            DiagLine("Firmware", diagnostics.firmwareRevision ?: "--")
                            DiagLine("Letzte FTMS Flags", diagnostics.lastParsedFlagsHex.ifBlank { "--" })

                            if (diagnostics.discoveredServices.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Erkannte GATT-Services",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colorScheme.onSurfaceVariant,
                                    )
                                    diagnostics.discoveredServices.forEach { svc ->
                                        Text(
                                            text = "• $svc",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Letzte Pakete (Ringpuffer max. ${BleDiagnostics.MAX_RECENT_PACKETS})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colorScheme.onSurfaceVariant,
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(MaterialTheme.shapes.small)
                                        .background(colorScheme.surfaceContainerLowest)
                                        .border(
                                            1.dp,
                                            colorScheme.outlineVariant.copy(alpha = 0.5f),
                                            MaterialTheme.shapes.small,
                                        )
                                        .horizontalScroll(rememberScrollState())
                                        .padding(12.dp),
                                ) {
                                    if (diagnostics.recentPackets.isEmpty()) {
                                        Text(
                                            text = "Noch keine 0x2AD1-Pakete empfangen",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = colorScheme.onSurfaceVariant,
                                        )
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            diagnostics.recentPackets.asReversed().forEach { entry ->
                                                val ts = packetTimeFormat.format(Date(entry.timestampMs))
                                                val flagInfo = buildString {
                                                    if (entry.flagsHex.isNotBlank()) append(entry.flagsHex)
                                                    if (entry.flagsSummary.isNotBlank()) {
                                                        if (isNotEmpty()) append(' ')
                                                        append("(${entry.flagsSummary})")
                                                    }
                                                }.ifBlank { "--" }
                                                val truncTag = if (entry.isTruncated) " [TRUNC]" else ""
                                                Text(
                                                    text = "$ts  [${entry.source}]  $flagInfo  |  ${entry.rawHex}$truncTag",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = if (entry.isTruncated) {
                                                        colorScheme.error
                                                    } else {
                                                        colorScheme.onSurface
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(Modifier.height(2.dp))

                            FilledTonalButton(
                                onClick = {
                                    clipboardManager.setText(
                                        AnnotatedString(diagnostics.formatForClipboard()),
                                    )
                                    onShowMessage("BLE-Diagnose in die Zwischenablage kopiert.")
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Diagnose kopieren")
                            }
                        }
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
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DiagLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
