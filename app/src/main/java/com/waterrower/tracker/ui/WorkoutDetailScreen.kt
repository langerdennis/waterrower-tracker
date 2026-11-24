package com.waterrower.tracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.waterrower.tracker.data.WorkoutEntity
import com.waterrower.tracker.data.WorkoutExporter
import com.waterrower.tracker.data.WorkoutSampleEntity
import com.waterrower.tracker.model.RowingTelemetry
import com.waterrower.tracker.service.RowingSessionHub
import com.waterrower.tracker.ui.theme.CoralDanger
import com.waterrower.tracker.ui.theme.EmeraldActive
import com.waterrower.tracker.ui.theme.SkyAccent
import com.waterrower.tracker.ui.theme.SlateSurface
import com.waterrower.tracker.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun WorkoutDetailScreen(
    workoutId: String,
    hub: RowingSessionHub,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var workout by remember { mutableStateOf<WorkoutEntity?>(null) }
    var samples by remember { mutableStateOf<List<WorkoutSampleEntity>>(emptyList()) }

    LaunchedEffect(workoutId) {
        workout = hub.getWorkout(workoutId)
        samples = hub.getWorkoutSamples(workoutId)
    }

    val current = workout
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy • HH:mm", Locale.GERMANY) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Zurück")
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = current?.let { dateFormat.format(Date(it.startedAtEpochMs)) } ?: "Workout",
                    style = MaterialTheme.typography.titleLarge,
                )
            }

            if (current != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { WorkoutExporter.shareWorkout(context, current, samples, asCsv = false) },
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("JSON")
                    }
                    FilledTonalButton(
                        onClick = { WorkoutExporter.shareWorkout(context, current, samples, asCsv = true) },
                    ) {
                        Text("CSV")
                    }
                    OutlinedButton(
                        onClick = {
                            hub.deleteWorkout(current.id)
                            onBack()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CoralDanger),
                    ) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Löschen")
                    }
                }
            }
        }

        if (current != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        DetailMetric("DISTANZ", RowingTelemetry.formatDistance(current.totalDistanceMeters), EmeraldActive)
                        DetailMetric("DAUER", RowingTelemetry.formatDuration(current.durationSeconds))
                        DetailMetric("Ø PACE", "${RowingTelemetry.formatPace(current.avgPaceSecPer500m)} /500m", SkyAccent)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        DetailMetric("Ø SPM", String.format(Locale.US, "%.1f", current.avgStrokeRate))
                        DetailMetric("Ø WATT", "${current.avgWatts} W")
                        DetailMetric("KALORIEN", "${current.totalCalories} kcal")
                        DetailMetric("Ø PULS", current.avgHeartRateBpm?.let { "$it bpm" } ?: "--")
                    }
                }
            }

            if (samples.size >= 2) {
                SampleChartCard(
                    title = "Schlagzahl-Verlauf (SPM)",
                    values = samples.map { it.strokeRateSpm },
                    lineColor = SkyAccent,
                    unitSuffix = " spm",
                )
                val validWatts = samples.map { it.watts.toFloat() }
                if (validWatts.any { it > 0f }) {
                    SampleChartCard(
                        title = "Leistungs-Verlauf (Watt)",
                        values = validWatts,
                        lineColor = EmeraldActive,
                        unitSuffix = " W",
                    )
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
    }
}

@Composable
private fun SampleChartCard(
    title: String,
    values: List<Float>,
    lineColor: Color,
    unitSuffix: String,
) {
    val minVal = (values.minOrNull() ?: 0f).coerceAtLeast(0f)
    val maxVal = (values.maxOrNull() ?: 1f).coerceAtLeast(minVal + 1f)

    Card(
        colors = CardDefaults.cardColors(containerColor = SlateSurface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Min ${minVal.toInt()}$unitSuffix  •  Max ${maxVal.toInt()}$unitSuffix",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
            }
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
            ) {
                val w = size.width
                val h = size.height
                val range = (maxVal - minVal).coerceAtLeast(1f)

                // Horizontal guide lines
                listOf(0.25f, 0.5f, 0.75f).forEach { frac ->
                    drawLine(
                        color = Color(0x2694A3B8),
                        start = Offset(0f, h * frac),
                        end = Offset(w, h * frac),
                        strokeWidth = 1f,
                    )
                }

                val path = Path()
                values.forEachIndexed { idx, v ->
                    val x = (idx.toFloat() / (values.size - 1).coerceAtLeast(1)) * w
                    val norm = ((v - minVal) / range).coerceIn(0f, 1f)
                    val y = h - (norm * (h - 16f)) - 8f
                    if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }

                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(width = 4f, cap = StrokeCap.Round),
                )
            }
        }
    }
}
