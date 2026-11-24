package com.waterrower.tracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waterrower.tracker.data.WorkoutEntity
import com.waterrower.tracker.model.RowingTelemetry
import com.waterrower.tracker.ui.theme.EmeraldActive
import com.waterrower.tracker.ui.theme.SkyAccent
import com.waterrower.tracker.ui.theme.SlateSurface
import com.waterrower.tracker.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    workouts: List<WorkoutEntity>,
    onSelectWorkout: (String) -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy • HH:mm", Locale.GERMANY) }

    val totalMeters = workouts.sumOf { it.totalDistanceMeters }
    val totalSeconds = workouts.sumOf { it.durationSeconds }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Summary Banner
        Card(
            colors = CardDefaults.cardColors(containerColor = SlateSurface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SummaryStat("EINHEITEN", "${workouts.size}")
                SummaryStat("GESAMTDISTANZ", RowingTelemetry.formatDistance(totalMeters))
                SummaryStat("GESAMTZEIT", RowingTelemetry.formatDuration(totalSeconds))
            }
        }

        if (workouts.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SlateSurface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Noch keine Ruder-Einheiten gespeichert",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Verbinde dein S4 Comms 61 oder teste den Simulator im Tab „Rudern“.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(workouts, key = { it.id }) { workout ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SlateSurface),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectWorkout(workout.id) },
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = dateFormat.format(Date(workout.startedAtEpochMs)) +
                                        if (workout.isSimulated) "  (Sim)" else "",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextSecondary,
                                )
                                Text(
                                    text = RowingTelemetry.formatDistance(workout.totalDistanceMeters),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = EmeraldActive,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                MiniStat("ZEIT", RowingTelemetry.formatDuration(workout.durationSeconds))
                                MiniStat("Ø PACE", RowingTelemetry.formatPace(workout.avgPaceSecPer500m), SkyAccent)
                                MiniStat("Ø SPM", String.format(Locale.US, "%.1f", workout.avgStrokeRate))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun MiniStat(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(horizontalAlignment = Alignment.End) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = color,
        )
    }
}
