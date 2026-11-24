package com.waterrower.tracker.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RowingTelemetry(
    val timestampMs: Long = System.currentTimeMillis(),
    val distanceMeters: Int = 0,
    val elapsedSeconds: Int = 0,
    val strokeRateSpm: Float = 0f,
    val strokeCount: Int = 0,
    val paceSecondsPer500m: Int = 0,
    val avgPaceSecondsPer500m: Int? = null,
    val watts: Int = 0,
    val avgWatts: Int? = null,
    val calories: Int = 0,
    val heartRateBpm: Int? = null,
) {
    val formattedPace: String get() = formatPace(paceSecondsPer500m)
    val formattedElapsed: String get() = formatDuration(elapsedSeconds)
    val formattedDistance: String get() = formatDistance(distanceMeters)

    companion object {
        fun formatPace(secondsPer500m: Int): String {
            if (secondsPer500m <= 0 || secondsPer500m >= 3600) return "--:--"
            val mins = secondsPer500m / 60
            val secs = secondsPer500m % 60
            return String.format(Locale.US, "%d:%02d", mins, secs)
        }

        fun formatDuration(totalSeconds: Int): String {
            val safe = totalSeconds.coerceAtLeast(0)
            val hours = safe / 3600
            val mins = (safe % 3600) / 60
            val secs = safe % 60
            return if (hours > 0) {
                String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
            } else {
                String.format(Locale.US, "%02d:%02d", mins, secs)
            }
        }

        fun formatDistance(meters: Int): String {
            val safe = meters.coerceAtLeast(0)
            return String.format(Locale.GERMANY, "%,d m", safe)
        }
    }
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Scanning : ConnectionState
    data class Connecting(val deviceName: String) : ConnectionState
    data class Connected(
        val deviceName: String,
        val address: String,
        val isSimulated: Boolean = false,
    ) : ConnectionState
    data class Error(val message: String) : ConnectionState
}

data class ScannedDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val hasFtmsService: Boolean,
)

data class BlePacketEntry(
    val timestampMs: Long,
    val source: String,
    val rawHex: String,
    val flagsHex: String = "",
    val flagsSummary: String = "",
    val isTruncated: Boolean = false,
)

data class BleDiagnostics(
    val discoveredServices: List<String> = emptyList(),
    val lastRawHex: String = "",
    val lastParsedFlagsHex: String = "",
    val packetCount: Long = 0L,
    val firmwareRevision: String? = null,
    val recentPackets: List<BlePacketEntry> = emptyList(),
    val truncatedPacketCount: Long = 0L,
) {
    fun appendPacket(entry: BlePacketEntry): BleDiagnostics {
        val updatedRecent = (recentPackets + entry).takeLast(MAX_RECENT_PACKETS)
        return copy(
            lastRawHex = entry.rawHex,
            lastParsedFlagsHex = entry.flagsHex.ifEmpty { lastParsedFlagsHex },
            packetCount = packetCount + 1L,
            recentPackets = updatedRecent,
            truncatedPacketCount = truncatedPacketCount + if (entry.isTruncated) 1L else 0L,
        )
    }

    fun formatForClipboard(): String {
        val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        return buildString {
            appendLine("WaterRower S4 BLE Diagnostics")
            appendLine("Firmware: ${firmwareRevision ?: "--"}")
            appendLine("Packets: $packetCount (truncated: $truncatedPacketCount)")
            appendLine("Last Flags: ${lastParsedFlagsHex.ifBlank { "--" }}")
            if (discoveredServices.isNotEmpty()) {
                appendLine("GATT Services:")
                discoveredServices.forEach { svc -> appendLine("  - $svc") }
            }
            appendLine("Recent Packets (max $MAX_RECENT_PACKETS):")
            if (recentPackets.isEmpty()) {
                appendLine("  (none)")
            } else {
                recentPackets.forEach { p ->
                    val ts = timeFmt.format(Date(p.timestampMs))
                    val flagsPart = buildString {
                        if (p.flagsHex.isNotBlank()) append("flags=${p.flagsHex}")
                        if (p.flagsSummary.isNotBlank()) {
                            if (isNotEmpty()) append(' ')
                            append("(${p.flagsSummary})")
                        }
                    }.ifBlank { "--" }
                    val truncTag = if (p.isTruncated) " [TRUNCATED]" else ""
                    appendLine("  $ts | ${p.source} | $flagsPart | ${p.rawHex}$truncTag")
                }
            }
        }.trimEnd()
    }

    companion object {
        const val MAX_RECENT_PACKETS = 10
    }
}
