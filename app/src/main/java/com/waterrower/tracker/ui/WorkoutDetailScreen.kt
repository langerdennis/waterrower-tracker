package com.waterrower.tracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waterrower.tracker.data.WorkoutEntity
import com.waterrower.tracker.data.WorkoutExporter
import com.waterrower.tracker.data.WorkoutSampleEntity
import com.waterrower.tracker.model.RowingTelemetry
import com.waterrower.tracker.model.Split500m
import com.waterrower.tracker.model.WorkoutAnalytics
import com.waterrower.tracker.service.RowingSessionHub
import com.waterrower.tracker.ui.theme.LocalRowingColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkoutDetailScreen(
    workoutId: String,
    hub: RowingSessionHub,
    onBack: () -> Unit,
    onStartGhost: (String) -> Unit,
    onShowMessage: (String) -> Unit,
) {
    BackHandler(enabled = true, onBack = onBack)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme
    val rowingColors = LocalRowingColors.current

    var workout by remember { mutableStateOf<WorkoutEntity?>(null) }
    var samples by remember { mutableStateOf<List<WorkoutSampleEntity>>(emptyList()) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var exportingFormat by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(workoutId) {
        workout = hub.getWorkout(workoutId)
        samples = hub.getWorkoutSamples(workoutId)
    }

    val current = workout
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy • HH:mm", Locale.GERMANY) }

    fun triggerExport(asCsv: Boolean) {
        val target = current ?: return
        if (exportingFormat != null) return
        exportingFormat = if (asCsv) "csv" else "json"
        scope.launch {
            try {
                val result = WorkoutExporter.shareWorkout(context, target, samples, asCsv = asCsv)
                result.onFailure { err ->
                    onShowMessage("Export fehlgeschlagen: ${err.localizedMessage ?: "Unbekannter Fehler"}")
                }
            } finally {
                exportingFormat = null
            }
        }
    }

    if (showDeleteDialog && current != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = colorScheme.error,
                )
            },
            title = { Text("Workout löschen?") },
            text = {
                Text(
                    "Möchtest du die Einheit vom ${dateFormat.format(Date(current.startedAtEpochMs))} " +
                        "(${RowingTelemetry.formatDistance(current.totalDistanceMeters)}) dauerhaft löschen?",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteDialog = false
                        hub.deleteWorkout(current.id)
                        onShowMessage("Workout gelöscht.")
                        onBack()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colorScheme.error,
                        contentColor = colorScheme.onError,
                    ),
                ) {
                    Text("Löschen")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Abbrechen")
                }
            },
        )
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 960.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Responsive Header & Actions
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Zurück")
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = current?.let { dateFormat.format(Date(it.startedAtEpochMs)) } ?: "Workout",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            current?.programSummary?.let { prog ->
                                Surface(
                                    color = colorScheme.primaryContainer,
                                    contentColor = colorScheme.onPrimaryContainer,
                                    shape = RoundedCornerShape(999.dp),
                                ) {
                                    Text(
                                        text = prog,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            if (current?.isSimulated == true) {
                                Surface(
                                    color = colorScheme.tertiaryContainer,
                                    contentColor = colorScheme.onTertiaryContainer,
                                    shape = RoundedCornerShape(999.dp),
                                ) {
                                    Text(
                                        text = "Simulator",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                if (current != null) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (samples.size >= 2) {
                            FilledTonalButton(onClick = { onStartGhost(current.id) }) {
                                Icon(Icons.Rounded.Flag, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Als Ghost")
                            }
                        }
                        FilledTonalButton(
                            onClick = { triggerExport(asCsv = false) },
                            enabled = exportingFormat == null,
                        ) {
                            if (exportingFormat == "json") {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("JSON")
                        }
                        FilledTonalButton(
                            onClick = { triggerExport(asCsv = true) },
                            enabled = exportingFormat == null,
                        ) {
                            if (exportingFormat == "csv") {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.width(6.dp))
                            Text("CSV")
                        }
                        OutlinedButton(
                            onClick = { showDeleteDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = colorScheme.error),
                            border = BorderStroke(1.dp, colorScheme.error.copy(alpha = 0.5f)),
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Löschen")
                        }
                    }
                }
            }

            if (current != null) {
                val summaryItems = buildList {
                    add(Triple("DISTANZ", RowingTelemetry.formatDistance(current.totalDistanceMeters), rowingColors.distance))
                    add(Triple("DAUER", RowingTelemetry.formatDuration(current.durationSeconds), colorScheme.onSurface))
                    add(Triple("Ø PACE", "${RowingTelemetry.formatPace(current.avgPaceSecPer500m)} /500m", rowingColors.pace))
                    add(Triple("Ø SPM", String.format(Locale.US, "%.1f", current.avgStrokeRate), colorScheme.onSurface))
                    add(Triple("Ø WATT", "${current.avgWatts} W", colorScheme.onSurface))
                    add(Triple("KALORIEN", "${current.totalCalories} kcal", colorScheme.onSurface))
                    add(Triple("SCHLÄGE", "${current.totalStrokes}", colorScheme.onSurface))
                    current.avgHeartRateBpm?.let { hr ->
                        add(Triple("Ø PULS", "$hr bpm", rowingColors.heartRate))
                    }
                }

                // Responsive Summary Metrics Card (4 cols >= 640dp, 2 cols < 640dp)
                Card(
                    colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                    ) {
                        val columns = if (maxWidth >= 600.dp) 4 else 2
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            summaryItems.chunked(columns).forEach { rowItems ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    rowItems.forEach { (label, value, color) ->
                                        DetailMetric(
                                            label = label,
                                            value = value,
                                            color = color,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    repeat(columns - rowItems.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                val splits = remember(samples, current.totalDistanceMeters, current.durationSeconds) {
                    WorkoutAnalytics.computeSplits500m(
                        samples = samples,
                        totalDistanceMeters = current.totalDistanceMeters,
                        totalDurationSeconds = current.durationSeconds,
                    )
                }
                if (splits.isNotEmpty()) {
                    Splits500mCard(splits = splits)
                }

                if (samples.size >= 2) {
                    val endDurationLabel = RowingTelemetry.formatDuration(current.durationSeconds)

                    // 1. Pace-Verlauf (/500m) — inverted Y axis so faster (lower sec/500m) plots higher
                    val paceValues = samples.map { s ->
                        if (s.paceSecPer500m in 1..3599) s.paceSecPer500m.toFloat() else null
                    }
                    if (paceValues.count { it != null } >= 2) {
                        SampleChartCard(
                            title = "Pace-Verlauf (/500m)",
                            values = paceValues,
                            lineColor = rowingColors.pace,
                            invertY = true,
                            endTimeLabel = endDurationLabel,
                            formatValue = { RowingTelemetry.formatPace(it.roundToInt()) },
                            summaryFormatter = { minV, maxV ->
                                "Best ${RowingTelemetry.formatPace(minV.roundToInt())}  •  Langsamste ${RowingTelemetry.formatPace(maxV.roundToInt())}"
                            },
                        )
                    }

                    // 2. Schlagzahl-Verlauf (SPM)
                    val spmValues = samples.map { it.strokeRateSpm }
                    SampleChartCard(
                        title = "Schlagzahl-Verlauf (SPM)",
                        values = spmValues,
                        lineColor = colorScheme.primary,
                        invertY = false,
                        endTimeLabel = endDurationLabel,
                        formatValue = { String.format(Locale.US, "%.1f", it) },
                        summaryFormatter = { minV, maxV ->
                            "Min ${String.format(Locale.US, "%.1f", minV)} spm  •  Max ${String.format(Locale.US, "%.1f", maxV)} spm"
                        },
                    )

                    // 3. Leistungs-Verlauf (Watt)
                    val wattValues = samples.map { it.watts.toFloat() }
                    if (wattValues.any { it > 0f }) {
                        SampleChartCard(
                            title = "Leistungs-Verlauf (Watt)",
                            values = wattValues,
                            lineColor = rowingColors.distance,
                            invertY = false,
                            endTimeLabel = endDurationLabel,
                            formatValue = { "${it.roundToInt()} W" },
                            summaryFormatter = { minV, maxV ->
                                "Min ${minV.roundToInt()} W  •  Max ${maxV.roundToInt()} W"
                            },
                        )
                    }

                    // 4. Herzfrequenz-Verlauf (BPM) when >= 2 valid HR samples exist
                    val hrValues = samples.map { s ->
                        s.heartRateBpm?.takeIf { it > 0 }?.toFloat()
                    }
                    if (hrValues.count { it != null } >= 2) {
                        SampleChartCard(
                            title = "Herzfrequenz-Verlauf (BPM)",
                            values = hrValues,
                            lineColor = rowingColors.heartRate,
                            invertY = false,
                            endTimeLabel = endDurationLabel,
                            formatValue = { "${it.roundToInt()} bpm" },
                            summaryFormatter = { minV, maxV ->
                                "Min ${minV.roundToInt()} bpm  •  Max ${maxV.roundToInt()} bpm"
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Splits500mCard(splits: List<Split500m>) {
    val colorScheme = MaterialTheme.colorScheme
    val rowingColors = LocalRowingColors.current
    val bestFullPace = remember(splits) {
        splits.filter { !it.isPartial }.minOfOrNull { it.paceSecPer500m }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "500m-Splits",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "DISTANZ",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1.2f),
                )
                Text(
                    text = "PACE",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Ø SPM",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.8f),
                )
                Text(
                    text = "Ø WATT",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(0.8f),
                )
            }

            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.5f))

            splits.forEachIndexed { idx, split ->
                val isBest = !split.isPartial && bestFullPace != null && split.paceSecPer500m == bestFullPace
                val label = if (split.isPartial) {
                    "${RowingTelemetry.formatDistance(split.endMeters)} (${split.distanceMeters} m)"
                } else {
                    RowingTelemetry.formatDistance(split.endMeters)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isBest) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (split.isPartial) colorScheme.onSurfaceVariant else colorScheme.onSurface,
                        modifier = Modifier.weight(1.2f),
                    )
                    Text(
                        text = RowingTelemetry.formatPace(split.paceSecPer500m),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isBest) rowingColors.active else rowingColors.pace,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (split.avgStrokeRate > 0f) {
                            String.format(Locale.US, "%.1f", split.avgStrokeRate)
                        } else {
                            "--"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(0.8f),
                    )
                    Text(
                        text = if (split.avgWatts > 0) "${split.avgWatts} W" else "--",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(0.8f),
                    )
                }
                if (idx < splits.lastIndex) {
                    HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.25f))
                }
            }
        }
    }
}

@Composable
private fun DetailMetric(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SampleChartCard(
    title: String,
    values: List<Float?>,
    lineColor: Color,
    invertY: Boolean,
    endTimeLabel: String,
    formatValue: (Float) -> String,
    summaryFormatter: (minVal: Float, maxVal: Float) -> String,
) {
    val nonNullValues = values.filterNotNull()
    if (nonNullValues.size < 2) return

    val minVal = nonNullValues.minOrNull() ?: 0f
    val rawMax = nonNullValues.maxOrNull() ?: minVal
    val axisMax = rawMax.coerceAtLeast(minVal + 1f)
    val midVal = (minVal + axisMax) / 2f
    val topAxisLabel = if (invertY) formatValue(minVal) else formatValue(axisMax)
    val midAxisLabel = formatValue(midVal)
    val bottomAxisLabel = if (invertY) formatValue(axisMax) else formatValue(minVal)

    val colorScheme = MaterialTheme.colorScheme
    val gridColor = colorScheme.outlineVariant.copy(alpha = 0.55f)

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
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = summaryFormatter(minVal, rawMax),
                    style = MaterialTheme.typography.labelMedium,
                    color = colorScheme.onSurfaceVariant,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(172.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(
                    modifier = Modifier.height(172.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    Text(
                        text = topAxisLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = midAxisLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = bottomAxisLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }

                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(172.dp),
                ) {
                    val w = size.width
                    val h = size.height
                    val padV = 10.dp.toPx()
                    val usableH = (h - padV * 2f).coerceAtLeast(1f)
                    val range = (axisMax - minVal).coerceAtLeast(1f)
                    val maxIdx = (values.size - 1).coerceAtLeast(1)

                    // Horizontal guide lines
                    listOf(0f, 0.5f, 1f).forEach { frac ->
                        val yLine = padV + usableH * frac
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, yLine),
                            end = Offset(w, yLine),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }

                    // Split into contiguous non-null segments so idle/invalid gaps break cleanly
                    val segments = mutableListOf<List<Offset>>()
                    var currentSegment = mutableListOf<Offset>()

                    values.forEachIndexed { idx, v ->
                        if (v == null) {
                            if (currentSegment.isNotEmpty()) {
                                segments.add(currentSegment)
                                currentSegment = mutableListOf()
                            }
                        } else {
                            val x = (idx.toFloat() / maxIdx) * w
                            val norm = ((v - minVal) / range).coerceIn(0f, 1f)
                            val yFrac = if (invertY) norm else (1f - norm)
                            val y = padV + yFrac * usableH
                            currentSegment.add(Offset(x, y))
                        }
                    }
                    if (currentSegment.isNotEmpty()) {
                        segments.add(currentSegment)
                    }

                    val fillBrush = Brush.verticalGradient(
                        colors = listOf(
                            lineColor.copy(alpha = 0.28f),
                            lineColor.copy(alpha = 0.02f),
                        ),
                        startY = padV,
                        endY = h,
                    )
                    val strokeStyle = Stroke(
                        width = 3.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    )

                    segments.forEach { pts ->
                        if (pts.size >= 2) {
                            val linePath = Path().apply {
                                moveTo(pts.first().x, pts.first().y)
                                for (i in 1 until pts.size) {
                                    lineTo(pts[i].x, pts[i].y)
                                }
                            }
                            val areaPath = Path().apply {
                                moveTo(pts.first().x, h)
                                lineTo(pts.first().x, pts.first().y)
                                for (i in 1 until pts.size) {
                                    lineTo(pts[i].x, pts[i].y)
                                }
                                lineTo(pts.last().x, h)
                                close()
                            }
                            drawPath(path = areaPath, brush = fillBrush)
                            drawPath(path = linePath, color = lineColor, style = strokeStyle)
                        } else if (pts.size == 1) {
                            drawCircle(color = lineColor, radius = 3.dp.toPx(), center = pts.first())
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "00:00",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
                Text(
                    text = endTimeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
