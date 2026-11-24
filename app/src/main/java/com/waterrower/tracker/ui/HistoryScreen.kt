package com.waterrower.tracker.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Rowing
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waterrower.tracker.data.WorkoutEntity
import com.waterrower.tracker.model.RowingTelemetry
import com.waterrower.tracker.ui.theme.LocalRowingColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class HistoryFilter(val label: String) {
    REAL("Echt"),
    SIMULATED("Simulator"),
    ALL("Alle"),
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    workouts: List<WorkoutEntity>,
    onSelectWorkout: (String) -> Unit,
) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy • HH:mm", Locale.GERMANY) }
    val colorScheme = MaterialTheme.colorScheme
    val rowingColors = LocalRowingColors.current

    val realCount = remember(workouts) { workouts.count { !it.isSimulated } }
    val simCount = remember(workouts) { workouts.count { it.isSimulated } }

    var explicitFilter by rememberSaveable { mutableStateOf<HistoryFilter?>(null) }
    val activeFilter = explicitFilter ?: if (realCount > 0 || workouts.isEmpty()) {
        HistoryFilter.REAL
    } else {
        HistoryFilter.ALL
    }

    val filteredWorkouts = remember(workouts, activeFilter) {
        when (activeFilter) {
            HistoryFilter.REAL -> workouts.filter { !it.isSimulated }
            HistoryFilter.SIMULATED -> workouts.filter { it.isSimulated }
            HistoryFilter.ALL -> workouts
        }
    }

    val totalMeters = filteredWorkouts.sumOf { it.totalDistanceMeters }
    val totalSeconds = filteredWorkouts.sumOf { it.durationSeconds }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 960.dp)
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Trainingshistorie",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )

                if (workouts.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        HistoryFilter.entries.forEach { filter ->
                            val count = when (filter) {
                                HistoryFilter.REAL -> realCount
                                HistoryFilter.SIMULATED -> simCount
                                HistoryFilter.ALL -> workouts.size
                            }
                            FilterChip(
                                selected = activeFilter == filter,
                                onClick = { explicitFilter = filter },
                                label = { Text("${filter.label} ($count)") },
                            )
                        }
                    }
                }
            }

            // Summary Banner (reflects active filter so simulator runs don't pollute real stats)
            Card(
                colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerHigh),
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SummaryStat("EINHEITEN", "${filteredWorkouts.size}")
                    SummaryStat(
                        "GESAMTDISTANZ",
                        RowingTelemetry.formatDistance(totalMeters),
                        accent = rowingColors.distance,
                    )
                    SummaryStat("GESAMTZEIT", RowingTelemetry.formatDuration(totalSeconds))
                }
            }

            if (filteredWorkouts.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 28.dp, vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(60.dp)
                                .clip(CircleShape)
                                .background(colorScheme.primaryContainer),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Rowing,
                                contentDescription = null,
                                tint = colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(30.dp),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        val emptyTitle = when {
                            workouts.isEmpty() -> "Noch keine Ruder-Einheiten gespeichert"
                            activeFilter == HistoryFilter.REAL -> "Noch keine echten Ruder-Einheiten"
                            else -> "Keine Simulator-Einheiten vorhanden"
                        }
                        val emptySubtitle = when {
                            workouts.isEmpty() -> "Verbinde dein S4 Comms 61 oder teste den Simulator im Tab „Rudern“."
                            activeFilter == HistoryFilter.REAL -> "Deine Simulator-Testläufe werden getrennt gehalten, damit echte Statistiken sauber bleiben."
                            else -> "Aktiviere den Simulator im Tab „Rudern“, um Testläufe aufzuzeichnen."
                        }
                        Text(
                            text = emptyTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = emptySubtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        if (workouts.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))
                            FilledTonalButton(onClick = { explicitFilter = HistoryFilter.ALL }) {
                                Text("Alle Einheiten anzeigen (${workouts.size})")
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(filteredWorkouts, key = { it.id }) { workout ->
                        Card(
                            onClick = { onSelectWorkout(workout.id) },
                            colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerLow),
                            shape = MaterialTheme.shapes.medium,
                            border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = dateFormat.format(Date(workout.startedAtEpochMs)),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = colorScheme.onSurfaceVariant,
                                        )
                                        if (workout.isSimulated) {
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

                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        Text(
                                            text = RowingTelemetry.formatDistance(workout.totalDistanceMeters),
                                            style = MaterialTheme.typography.headlineSmall,
                                            color = rowingColors.distance,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            MiniStat("ZEIT", RowingTelemetry.formatDuration(workout.durationSeconds))
                                            MiniStat(
                                                "Ø PACE",
                                                RowingTelemetry.formatPace(workout.avgPaceSecPer500m),
                                                rowingColors.pace,
                                            )
                                            MiniStat("Ø SPM", String.format(Locale.US, "%.1f", workout.avgStrokeRate))
                                        }
                                    }
                                }

                                Icon(
                                    imageVector = Icons.Rounded.ChevronRight,
                                    contentDescription = "Details öffnen",
                                    tint = colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryStat(
    label: String,
    value: String,
    accent: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = accent,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun MiniStat(
    label: String,
    value: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}
