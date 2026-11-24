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
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayCircleOutline
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waterrower.tracker.data.AppPreferences
import com.waterrower.tracker.data.OverlaySettings
import com.waterrower.tracker.data.OverlayStyle
import com.waterrower.tracker.health.HealthConnectAvailability
import com.waterrower.tracker.model.BleDiagnostics
import com.waterrower.tracker.model.ConnectionState
import com.waterrower.tracker.ui.theme.LocalRowingColors
import com.waterrower.tracker.update.ReleaseInfo
import com.waterrower.tracker.update.UpdateUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val AUTO_FINISH_OPTIONS = listOf(0, 2, 3, 5)
private val WEEKLY_GOAL_OPTIONS = listOf(0, 10_000, 15_000, 20_000, 30_000)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: OverlaySettings,
    diagnostics: BleDiagnostics,
    connectionState: ConnectionState,
    isSimulatorMode: Boolean,
    workoutCount: Int,
    unsyncedRealWorkoutCount: Int,
    isBackupBusy: Boolean,
    healthConnectAvailability: HealthConnectAvailability,
    isHealthConnectBusy: Boolean,
    currentVersionName: String,
    updateState: UpdateUiState,
    onOpacityChange: (Float) -> Unit,
    onScaleChange: (Float) -> Unit,
    onOverlayStyleChange: (OverlayStyle) -> Unit,
    onCompactOverlayChange: (Boolean) -> Unit,
    onOverlayLockChange: (Boolean) -> Unit,
    onShowWattsInMiniChange: (Boolean) -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
    onAutoFinishMinutesChange: (Int) -> Unit,
    onGoalSoundChange: (Boolean) -> Unit,
    onAutoReconnectChange: (Boolean) -> Unit,
    onAutoShowOverlayChange: (Boolean) -> Unit,
    onWeeklyGoalMetersChange: (Int) -> Unit,
    onToggleHealthConnectSync: (Boolean) -> Unit,
    onSyncAllHealthConnect: () -> Unit,
    onResetOverlayPosition: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onDownloadAndInstallUpdate: (ReleaseInfo) -> Unit,
    onInstallDownloadedUpdate: () -> Unit,
    onToggleSimulator: (Boolean) -> Unit,
    onConnectSimulator: () -> Unit,
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

            // 1. Floating Overlay Appearance, Cinema Mode & Automation
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
                            text = "Floating Overlay & Start",
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
                            // Live Preview Pill reflecting Standard / Cinema / Transparent + Compact
                            val previewBg = when (settings.overlayStyle) {
                                OverlayStyle.TRANSPARENT -> Color.Transparent
                                OverlayStyle.CINEMA -> Color(0xFF05070A).copy(alpha = (settings.opacity * 0.72f).coerceIn(0.11f, 0.78f))
                                OverlayStyle.STANDARD -> Color(0xFF0B121A).copy(alpha = settings.opacity)
                            }
                            val previewBorder = when (settings.overlayStyle) {
                                OverlayStyle.TRANSPARENT -> Color.Transparent
                                OverlayStyle.CINEMA -> Color(0xFF38BDF8).copy(alpha = (settings.opacity * 0.35f).coerceIn(0.08f, 0.38f))
                                OverlayStyle.STANDARD -> Color(0xFF38BDF8).copy(alpha = settings.opacity)
                            }
                            val previewTextColor = when (settings.overlayStyle) {
                                OverlayStyle.STANDARD -> Color(0xFFF8FAFC)
                                OverlayStyle.CINEMA -> Color(0xFFCBD5E1).copy(alpha = (settings.opacity.coerceIn(0.45f, 1.0f) * 0.85f))
                                OverlayStyle.TRANSPARENT -> Color(0xFFE2E8F0).copy(alpha = (settings.opacity.coerceIn(0.4f, 1.0f) * 0.92f))
                            }
                            val previewDotColor = if (settings.overlayStyle == OverlayStyle.STANDARD) {
                                rowingColors.active
                            } else {
                                rowingColors.active.copy(alpha = (settings.opacity.coerceIn(0.35f, 1.0f) * 0.72f))
                            }
                            val previewShadow = when (settings.overlayStyle) {
                                OverlayStyle.TRANSPARENT -> Shadow(
                                    color = Color.Black.copy(alpha = 0.88f),
                                    offset = Offset(0f, 1.5f),
                                    blurRadius = 6f,
                                )
                                OverlayStyle.CINEMA -> Shadow(
                                    color = Color.Black.copy(alpha = 0.55f),
                                    offset = Offset(0f, 1f),
                                    blurRadius = 3f,
                                )
                                OverlayStyle.STANDARD -> null
                            }

                            Box(
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(previewBg)
                                    .border(1.dp, previewBorder, RoundedCornerShape(16.dp))
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
                                            .background(previewDotColor),
                                    )
                                    val previewText = if (settings.compactOverlayMode) {
                                        "2:04  •  1.420 m"
                                    } else {
                                        val wattsPart = if (settings.showWattsInMini) "  •  185 W" else ""
                                        "2:04 /500m  •  26 spm  •  1.420 m  •  06:12$wattsPart"
                                    }
                                    Text(
                                        text = previewText,
                                        style = TextStyle(
                                            color = previewTextColor,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = (15f * settings.scale).sp,
                                            shadow = previewShadow,
                                        ),
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
                                    text = "Vorschau über Video-App (${settings.overlayStyle.label}" +
                                        (if (settings.compactOverlayMode) " • Kompakt" else "") +
                                        (if (settings.overlayPositionLocked) " • Gesperrt" else "") + ")",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF94A3B8),
                                )
                            }
                        }
                    }

                    // Overlay Style Chips (Standard / Kino / Transparent)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Overlay-Stil",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = when (settings.overlayStyle) {
                                OverlayStyle.STANDARD -> "Dunkle Pille mit klarem Rahmen und kontrastreicher Schrift."
                                OverlayStyle.CINEMA -> "Gedimmter Kino-Look – kaum ablenkend über dunklen Filmszenen."
                                OverlayStyle.TRANSPARENT -> "Kein Kasten – schwebender Text mit Schatten wie ein Untertitel."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OverlayStyle.entries.forEach { style ->
                                FilterChip(
                                    selected = settings.overlayStyle == style,
                                    onClick = { onOverlayStyleChange(style) },
                                    label = { Text(style.label) },
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
                            valueRange = AppPreferences.MIN_OPACITY..1.0f,
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
                        title = "Ultra-Kompakt-Modus (Kino)",
                        subtitle = "Zeigt nur Pace & Distanz/Rest – auch direkt im Overlay umschaltbar",
                        checked = settings.compactOverlayMode,
                        onCheckedChange = onCompactOverlayChange,
                    )

                    SettingToggleRow(
                        title = "Overlay-Position sperren",
                        subtitle = "Verhindert versehentliches Verschieben beim Bedienen von Video-Apps",
                        checked = settings.overlayPositionLocked,
                        onCheckedChange = onOverlayLockChange,
                    )

                    SettingToggleRow(
                        title = "Watt auch in der Mini-Leiste anzeigen",
                        subtitle = "Im Voll-Modus sichtbar, sonst beim Antippen des Overlays",
                        checked = settings.showWattsInMini,
                        onCheckedChange = onShowWattsInMiniChange,
                    )

                    HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))

                    SettingToggleRow(
                        title = "Beim Start automatisch verbinden",
                        subtitle = "Verbindet beim Öffnen direkt mit dem letzten bekannten S4 ComModule",
                        checked = settings.autoReconnectOnStart,
                        onCheckedChange = onAutoReconnectChange,
                    )

                    SettingToggleRow(
                        title = "Overlay automatisch bei Verbindung",
                        subtitle = "Blendet das Floating Overlay sofort ein, sobald der WaterRower verbunden ist",
                        checked = settings.autoShowOverlayOnConnect,
                        onCheckedChange = onAutoShowOverlayChange,
                    )

                    SettingToggleRow(
                        title = "Aufzeichnung automatisch starten",
                        subtitle = "Beginnt automatisch bei echter Ruderbewegung",
                        checked = settings.autoStartRecording,
                        onCheckedChange = onAutoStartChange,
                    )

                    SettingToggleRow(
                        title = "Signalton bei Ziel- & Rundenende",
                        subtitle = "Kurzer Ton beim Erreichen von Zielen und Programm-Wechseln",
                        checked = settings.goalSoundEnabled,
                        onCheckedChange = onGoalSoundChange,
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Automatisch beenden nach Leerlauf",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (settings.autoFinishIdleMinutes == 0) {
                                "Deaktiviert – laufende Aufnahmen werden manuell gespeichert."
                            } else {
                                "Speichert eine Aufnahme automatisch nach ${settings.autoFinishIdleMinutes} Min. Auto-Pause."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AUTO_FINISH_OPTIONS.forEach { mins ->
                                FilterChip(
                                    selected = settings.autoFinishIdleMinutes == mins,
                                    onClick = { onAutoFinishMinutesChange(mins) },
                                    label = { Text(if (mins == 0) "Aus" else "$mins Min") },
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Wochenziel (letzte 7 Tage)",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = if (settings.weeklyGoalMeters == 0) {
                                "Deaktiviert – optionaler Fortschrittsbalken in der Historie."
                            } else {
                                "Ziel: ${settings.weeklyGoalMeters / 1000} km pro Woche (sichtbar in der Historie)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            WEEKLY_GOAL_OPTIONS.forEach { meters ->
                                FilterChip(
                                    selected = settings.weeklyGoalMeters == meters,
                                    onClick = { onWeeklyGoalMetersChange(meters) },
                                    label = { Text(if (meters == 0) "Aus" else "${meters / 1000} km") },
                                )
                            }
                        }
                    }

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

            // 2. Local Full JSON Backup & Restore
            Card(
                colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Storage,
                            contentDescription = null,
                            tint = colorScheme.primary,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Lokale Datensicherung",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = "$workoutCount gespeicherte Einheiten • Duplikatfreier JSON-Import per UUID",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        FilledTonalButton(
                            onClick = onExportBackup,
                            enabled = !isBackupBusy && workoutCount > 0,
                        ) {
                            if (isBackupBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.FileDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("Backup speichern (JSON)")
                        }

                        OutlinedButton(
                            onClick = onImportBackup,
                            enabled = !isBackupBusy,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FileUpload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Backup wiederherstellen")
                        }
                    }

                    HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))

                    val hcSubtitle = when (healthConnectAvailability) {
                        HealthConnectAvailability.AVAILABLE ->
                            "Speichert echte Einheiten (inkl. Kombi-Programme als Rudergerät) lokal in Android Health Connect."
                        HealthConnectAvailability.UPDATE_OR_INSTALL_REQUIRED ->
                            "Health Connect App auf diesem Gerät installieren oder aktualisieren."
                        HealthConnectAvailability.UNAVAILABLE ->
                            "Health Connect ist auf diesem Gerät nicht verfügbar."
                    }
                    SettingToggleRow(
                        title = "Mit Health Connect synchronisieren",
                        subtitle = hcSubtitle,
                        checked = settings.healthConnectSyncEnabled &&
                            healthConnectAvailability == HealthConnectAvailability.AVAILABLE,
                        onCheckedChange = { enabled ->
                            if (healthConnectAvailability == HealthConnectAvailability.AVAILABLE) {
                                onToggleHealthConnectSync(enabled)
                            } else {
                                onShowMessage(hcSubtitle)
                            }
                        },
                    )

                    if (settings.healthConnectSyncEnabled &&
                        healthConnectAvailability == HealthConnectAvailability.AVAILABLE &&
                        unsyncedRealWorkoutCount > 0
                    ) {
                        OutlinedButton(
                            onClick = onSyncAllHealthConnect,
                            enabled = !isHealthConnectBusy,
                        ) {
                            if (isHealthConnectBusy) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("$unsyncedRealWorkoutCount offene Einheiten synchronisieren")
                        }
                    }
                }
            }

            // 3. In-App GitHub Release Self-Updater
            Card(
                colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SystemUpdate,
                            contentDescription = null,
                            tint = colorScheme.primary,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "App-Updates (GitHub)",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = "Installierte Version: v$currentVersionName • Offline, bis du prüfst",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    when (val state = updateState) {
                        UpdateUiState.Idle -> {
                            FilledTonalButton(onClick = onCheckForUpdate) {
                                Text("Nach Updates suchen")
                            }
                        }
                        UpdateUiState.Checking -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Text(
                                    text = "Prüfe GitHub Releases…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        is UpdateUiState.UpToDate -> {
                            Text(
                                text = "Alles aktuell (${state.latestTag} ist die neueste Version).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = rowingColors.active,
                                fontWeight = FontWeight.Medium,
                            )
                            OutlinedButton(onClick = onCheckForUpdate) {
                                Text("Erneut prüfen")
                            }
                        }
                        is UpdateUiState.Available -> {
                            val sizeSuffix = state.release.formattedSizeMb
                                .takeIf { it.isNotBlank() }
                                ?.let { " ($it)" }
                                .orEmpty()
                            Text(
                                text = "Neue Version verfügbar: ${state.release.tagName}$sizeSuffix",
                                style = MaterialTheme.typography.titleSmall,
                                color = colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (state.release.notes.isNotBlank()) {
                                val shortNotes = remember(state.release.notes) {
                                    state.release.notes.lines()
                                        .filter { it.isNotBlank() }
                                        .take(4)
                                        .joinToString("\n")
                                }
                                Text(
                                    text = shortNotes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant,
                                )
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(onClick = { onDownloadAndInstallUpdate(state.release) }) {
                                    Text("Herunterladen & installieren")
                                }
                                OutlinedButton(onClick = onCheckForUpdate) {
                                    Text("Erneut prüfen")
                                }
                            }
                        }
                        is UpdateUiState.Downloading -> {
                            val pct = (state.progress * 100f).roundToInt()
                            Text(
                                text = "Lade ${state.release.tagName} herunter… $pct%",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(999.dp)),
                                strokeCap = StrokeCap.Round,
                            )
                        }
                        is UpdateUiState.ReadyToInstall -> {
                            Text(
                                text = if (state.needsInstallPermission) {
                                    "${state.release.tagName} ist bereit. Erlaube einmalig „Apps aus dieser Quelle installieren“ und tippe dann auf Installieren."
                                } else {
                                    "${state.release.tagName} wurde heruntergeladen und ist bereit zur Installation."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = onInstallDownloadedUpdate) {
                                Text("Jetzt installieren")
                            }
                        }
                        is UpdateUiState.Error -> {
                            Text(
                                text = state.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.error,
                            )
                            OutlinedButton(onClick = onCheckForUpdate) {
                                Text("Erneut versuchen")
                            }
                        }
                    }
                }
            }

            // 4. Collapsible Developer Tools & BLE Diagnostics
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
                                    text = "Entwickler & BLE-Diagnose",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                val simNote = if (isSimulatorMode) "Simulator aktiv  •  " else ""
                                Text(
                                    text = "$simNote${diagnostics.packetCount} Pakete  •  FW ${diagnostics.firmwareRevision ?: "--"}" +
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

                            SettingToggleRow(
                                title = "Simulator-Modus (Debug)",
                                subtitle = "Erzeugt simulierte Ruderdaten ohne WaterRower S4",
                                checked = isSimulatorMode,
                                onCheckedChange = onToggleSimulator,
                            )

                            if (isSimulatorMode && connectionState !is ConnectionState.Connected) {
                                FilledTonalButton(onClick = onConnectSimulator) {
                                    Icon(
                                        imageVector = Icons.Rounded.Science,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Simulator neu verbinden")
                                }
                            }

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
