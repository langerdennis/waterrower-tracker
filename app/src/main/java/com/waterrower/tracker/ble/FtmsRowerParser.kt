package com.waterrower.tracker.ble

import com.waterrower.tracker.model.RowingTelemetry
import java.util.UUID

/**
 * Standard Bluetooth SIG GATT UUIDs exposed by WaterRower S4 ComModule (e.g. S4 Comms 61).
 */
object BleUuids {
    val FTMS_SERVICE: UUID = UUID.fromString("00001826-0000-1000-8000-00805f9b34fb")
    val FTMS_ROWER_DATA: UUID = UUID.fromString("00002ad1-0000-1000-8000-00805f9b34fb")
    val FTMS_FEATURE: UUID = UUID.fromString("00002acc-0000-1000-8000-00805f9b34fb")

    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_REVISION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

/**
 * Partial update parsed from a single FTMS Rower Data (0x2AD1) notification packet.
 * Because some FTMS devices split metrics across multiple packets using the "More Data" flag,
 * fields are nullable and should be merged into the active [RowingTelemetry] state.
 */
data class FtmsRowerPacket(
    val flags: Int,
    val strokeRateSpm: Float? = null,
    val strokeCount: Int? = null,
    val avgStrokeRateSpm: Float? = null,
    val totalDistanceMeters: Int? = null,
    val instantaneousPaceSec: Int? = null,
    val avgPaceSec: Int? = null,
    val instantaneousPowerWatts: Int? = null,
    val avgPowerWatts: Int? = null,
    val resistanceLevel: Int? = null,
    val totalEnergyKcal: Int? = null,
    val heartRateBpm: Int? = null,
    val hasHeartRate: Boolean = false,
    val metabolicEquivalent: Float? = null,
    val elapsedTimeSec: Int? = null,
    val remainingTimeSec: Int? = null,
    val isTruncated: Boolean = false,
) {
    fun mergeInto(current: RowingTelemetry, timestampMs: Long = System.currentTimeMillis()): RowingTelemetry {
        return current.copy(
            timestampMs = timestampMs,
            distanceMeters = totalDistanceMeters ?: current.distanceMeters,
            elapsedSeconds = elapsedTimeSec ?: current.elapsedSeconds,
            strokeRateSpm = strokeRateSpm ?: current.strokeRateSpm,
            strokeCount = strokeCount ?: current.strokeCount,
            paceSecondsPer500m = instantaneousPaceSec ?: current.paceSecondsPer500m,
            avgPaceSecondsPer500m = avgPaceSec ?: current.avgPaceSecondsPer500m,
            watts = instantaneousPowerWatts ?: current.watts,
            avgWatts = avgPowerWatts ?: current.avgWatts,
            calories = totalEnergyKcal ?: current.calories,
            heartRateBpm = if (hasHeartRate) heartRateBpm else current.heartRateBpm,
        )
    }
}

/**
 * Parsed Bluetooth SIG Heart Rate Measurement (0x2A37) packet.
 * Distinguishes structurally valid packets whose heart rate is absent/cleared (bpm == null, e.g.
 * bpm == 0 or sensor contact not detected) from malformed packets (parseHeartRatePacket returns null).
 */
data class HeartRatePacket(val bpm: Int?)

object FtmsRowerParser {

    private const val FLAG_MORE_DATA = 1 shl 0
    private const val FLAG_AVG_STROKE_RATE = 1 shl 1
    private const val FLAG_TOTAL_DISTANCE = 1 shl 2
    private const val FLAG_INSTANTANEOUS_PACE = 1 shl 3
    private const val FLAG_AVG_PACE = 1 shl 4
    private const val FLAG_INSTANTANEOUS_POWER = 1 shl 5
    private const val FLAG_AVG_POWER = 1 shl 6
    private const val FLAG_RESISTANCE_LEVEL = 1 shl 7
    private const val FLAG_EXPENDED_ENERGY = 1 shl 8
    private const val FLAG_HEART_RATE = 1 shl 9
    private const val FLAG_METABOLIC_EQUIV = 1 shl 10
    private const val FLAG_ELAPSED_TIME = 1 shl 11
    private const val FLAG_REMAINING_TIME = 1 shl 12

    private const val FTMS_UINT16_UNAVAILABLE = 0xFFFF
    private const val HR_SENSOR_CONTACT_MASK = 0x06
    private const val HR_SENSOR_CONTACT_NOT_DETECTED = 0x04

    /**
     * Parses a Bluetooth SIG FTMS Rower Data (0x2AD1) payload.
     * Returns null if the payload is shorter than the mandatory 2-byte flags field.
     * Stops immediately on the first truncated field to prevent byte-offset shifts into
     * subsequent fields, marking [FtmsRowerPacket.isTruncated] = true while preserving
     * all completely decoded prefix fields.
     */
    fun parseRowerData(bytes: ByteArray): FtmsRowerPacket? {
        if (bytes.size < 2) return null

        val flags = readUInt16LE(bytes, 0)
        var offset = 2

        var strokeRate: Float? = null
        var strokeCount: Int? = null
        var avgStrokeRate: Float? = null
        var totalDistance: Int? = null
        var instPace: Int? = null
        var avgPace: Int? = null
        var instPower: Int? = null
        var avgPower: Int? = null
        var resistance: Int? = null
        var totalEnergy: Int? = null
        var heartRate: Int? = null
        var hasHeartRate = false
        var met: Float? = null
        var elapsedTime: Int? = null
        var remainingTime: Int? = null

        fun buildPacket(truncated: Boolean) = FtmsRowerPacket(
            flags = flags,
            strokeRateSpm = strokeRate,
            strokeCount = strokeCount,
            avgStrokeRateSpm = avgStrokeRate,
            totalDistanceMeters = totalDistance,
            instantaneousPaceSec = instPace,
            avgPaceSec = avgPace,
            instantaneousPowerWatts = instPower,
            avgPowerWatts = avgPower,
            resistanceLevel = resistance,
            totalEnergyKcal = totalEnergy,
            heartRateBpm = heartRate,
            hasHeartRate = hasHeartRate,
            metabolicEquivalent = met,
            elapsedTimeSec = elapsedTime,
            remainingTimeSec = remainingTime,
            isTruncated = truncated,
        )

        // Bit 0 == 0 means Stroke Rate (UINT8, 0.5 spm) and Stroke Count (UINT16) are present.
        if ((flags and FLAG_MORE_DATA) == 0) {
            if (offset + 3 > bytes.size) return buildPacket(truncated = true)
            strokeRate = readUInt8(bytes, offset) * 0.5f
            offset += 1
            strokeCount = readUInt16LE(bytes, offset)
            offset += 2
        }

        if ((flags and FLAG_AVG_STROKE_RATE) != 0) {
            if (offset + 1 > bytes.size) return buildPacket(truncated = true)
            avgStrokeRate = readUInt8(bytes, offset) * 0.5f
            offset += 1
        }

        if ((flags and FLAG_TOTAL_DISTANCE) != 0) {
            if (offset + 3 > bytes.size) return buildPacket(truncated = true)
            totalDistance = readUInt24LE(bytes, offset)
            offset += 3
        }

        if ((flags and FLAG_INSTANTANEOUS_PACE) != 0) {
            if (offset + 2 > bytes.size) return buildPacket(truncated = true)
            val raw = readUInt16LE(bytes, offset)
            instPace = if (raw == FTMS_UINT16_UNAVAILABLE) 0 else raw
            offset += 2
        }

        if ((flags and FLAG_AVG_PACE) != 0) {
            if (offset + 2 > bytes.size) return buildPacket(truncated = true)
            val raw = readUInt16LE(bytes, offset)
            avgPace = if (raw == FTMS_UINT16_UNAVAILABLE) null else raw
            offset += 2
        }

        if ((flags and FLAG_INSTANTANEOUS_POWER) != 0) {
            if (offset + 2 > bytes.size) return buildPacket(truncated = true)
            instPower = readSInt16LE(bytes, offset).coerceAtLeast(0)
            offset += 2
        }

        if ((flags and FLAG_AVG_POWER) != 0) {
            if (offset + 2 > bytes.size) return buildPacket(truncated = true)
            avgPower = readSInt16LE(bytes, offset).coerceAtLeast(0)
            offset += 2
        }

        if ((flags and FLAG_RESISTANCE_LEVEL) != 0) {
            if (offset + 2 > bytes.size) return buildPacket(truncated = true)
            resistance = readSInt16LE(bytes, offset)
            offset += 2
        }

        // Expended Energy consists of Total Energy (UINT16), Energy/Hour (UINT16), Energy/Min (UINT8)
        if ((flags and FLAG_EXPENDED_ENERGY) != 0) {
            if (offset + 5 > bytes.size) return buildPacket(truncated = true)
            val rawTotal = readUInt16LE(bytes, offset)
            if (rawTotal != FTMS_UINT16_UNAVAILABLE) {
                totalEnergy = rawTotal
            }
            offset += 5
        }

        if ((flags and FLAG_HEART_RATE) != 0) {
            if (offset + 1 > bytes.size) return buildPacket(truncated = true)
            hasHeartRate = true
            val rawHr = readUInt8(bytes, offset)
            heartRate = if (rawHr in 30..240) rawHr else null
            offset += 1
        }

        if ((flags and FLAG_METABOLIC_EQUIV) != 0) {
            if (offset + 1 > bytes.size) return buildPacket(truncated = true)
            met = readUInt8(bytes, offset) * 0.1f
            offset += 1
        }

        if ((flags and FLAG_ELAPSED_TIME) != 0) {
            if (offset + 2 > bytes.size) return buildPacket(truncated = true)
            elapsedTime = readUInt16LE(bytes, offset)
            offset += 2
        }

        if ((flags and FLAG_REMAINING_TIME) != 0) {
            if (offset + 2 > bytes.size) return buildPacket(truncated = true)
            remainingTime = readUInt16LE(bytes, offset)
            offset += 2
        }

        return buildPacket(truncated = false)
    }

    /**
     * Parses Bluetooth SIG Heart Rate Measurement (0x2A37).
     * Returns null only on structurally malformed packets; returns [HeartRatePacket] with
     * `bpm = null` when the strap reports 0 BPM, out-of-range values, or sensor contact lost.
     */
    fun parseHeartRatePacket(bytes: ByteArray): HeartRatePacket? {
        if (bytes.size < 2) return null
        val flags = bytes[0].toInt() and 0xFF
        val is16Bit = (flags and 0x01) != 0
        val bpm = if (is16Bit) {
            if (bytes.size < 3) return null
            readUInt16LE(bytes, 1)
        } else {
            readUInt8(bytes, 1)
        }
        val contactNotDetected = (flags and HR_SENSOR_CONTACT_MASK) == HR_SENSOR_CONTACT_NOT_DETECTED
        if (contactNotDetected) {
            return HeartRatePacket(bpm = null)
        }
        return HeartRatePacket(bpm = if (bpm in 30..240) bpm else null)
    }

    /**
     * Parses Bluetooth SIG Heart Rate Measurement (0x2A37) into a valid BPM or null.
     */
    fun parseHeartRateMeasurement(bytes: ByteArray): Int? =
        parseHeartRatePacket(bytes)?.bpm

    /**
     * Parses Bluetooth SIG Battery Level (0x2A19).
     */
    fun parseBatteryLevel(bytes: ByteArray): Int? {
        if (bytes.isEmpty()) return null
        return readUInt8(bytes, 0).coerceIn(0, 100)
    }

    /**
     * Produces a concise human-readable summary of the active fields encoded in FTMS 0x2AD1 flags.
     */
    fun describeFlags(flags: Int): String {
        val parts = mutableListOf<String>()
        if ((flags and FLAG_MORE_DATA) == 0) parts += "Stroke"
        if ((flags and FLAG_AVG_STROKE_RATE) != 0) parts += "AvgSPM"
        if ((flags and FLAG_TOTAL_DISTANCE) != 0) parts += "Dist"
        if ((flags and FLAG_INSTANTANEOUS_PACE) != 0) parts += "Pace"
        if ((flags and FLAG_AVG_PACE) != 0) parts += "AvgPace"
        if ((flags and FLAG_INSTANTANEOUS_POWER) != 0) parts += "Power"
        if ((flags and FLAG_AVG_POWER) != 0) parts += "AvgPower"
        if ((flags and FLAG_RESISTANCE_LEVEL) != 0) parts += "Res"
        if ((flags and FLAG_EXPENDED_ENERGY) != 0) parts += "Energy"
        if ((flags and FLAG_HEART_RATE) != 0) parts += "HR"
        if ((flags and FLAG_METABOLIC_EQUIV) != 0) parts += "MET"
        if ((flags and FLAG_ELAPSED_TIME) != 0) parts += "Elapsed"
        if ((flags and FLAG_REMAINING_TIME) != 0) parts += "Rem"
        return if (parts.isEmpty()) "None" else parts.joinToString(", ")
    }

    fun toHexString(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun readUInt8(bytes: ByteArray, offset: Int): Int =
        bytes[offset].toInt() and 0xFF

    private fun readUInt16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readSInt16LE(bytes: ByteArray, offset: Int): Int =
        readUInt16LE(bytes, offset).toShort().toInt()

    private fun readUInt24LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
}
