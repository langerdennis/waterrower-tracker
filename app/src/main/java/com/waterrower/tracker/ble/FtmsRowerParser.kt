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
    val metabolicEquivalent: Float? = null,
    val elapsedTimeSec: Int? = null,
    val remainingTimeSec: Int? = null,
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
            heartRateBpm = heartRateBpm ?: current.heartRateBpm,
        )
    }
}

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

    /**
     * Parses a Bluetooth SIG FTMS Rower Data (0x2AD1) payload.
     * Returns null if the payload is shorter than the mandatory 2-byte flags field.
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
        var met: Float? = null
        var elapsedTime: Int? = null
        var remainingTime: Int? = null

        // Bit 0 == 0 means Stroke Rate (UINT8, 0.5 spm) and Stroke Count (UINT16) are present.
        if ((flags and FLAG_MORE_DATA) == 0 && offset + 3 <= bytes.size) {
            strokeRate = readUInt8(bytes, offset) * 0.5f
            offset += 1
            strokeCount = readUInt16LE(bytes, offset)
            offset += 2
        }

        if ((flags and FLAG_AVG_STROKE_RATE) != 0 && offset + 1 <= bytes.size) {
            avgStrokeRate = readUInt8(bytes, offset) * 0.5f
            offset += 1
        }

        if ((flags and FLAG_TOTAL_DISTANCE) != 0 && offset + 3 <= bytes.size) {
            totalDistance = readUInt24LE(bytes, offset)
            offset += 3
        }

        if ((flags and FLAG_INSTANTANEOUS_PACE) != 0 && offset + 2 <= bytes.size) {
            val raw = readUInt16LE(bytes, offset)
            instPace = if (raw == FTMS_UINT16_UNAVAILABLE) 0 else raw
            offset += 2
        }

        if ((flags and FLAG_AVG_PACE) != 0 && offset + 2 <= bytes.size) {
            val raw = readUInt16LE(bytes, offset)
            avgPace = if (raw == FTMS_UINT16_UNAVAILABLE) null else raw
            offset += 2
        }

        if ((flags and FLAG_INSTANTANEOUS_POWER) != 0 && offset + 2 <= bytes.size) {
            instPower = readSInt16LE(bytes, offset).coerceAtLeast(0)
            offset += 2
        }

        if ((flags and FLAG_AVG_POWER) != 0 && offset + 2 <= bytes.size) {
            avgPower = readSInt16LE(bytes, offset).coerceAtLeast(0)
            offset += 2
        }

        if ((flags and FLAG_RESISTANCE_LEVEL) != 0 && offset + 2 <= bytes.size) {
            resistance = readSInt16LE(bytes, offset)
            offset += 2
        }

        // Expended Energy consists of Total Energy (UINT16), Energy/Hour (UINT16), Energy/Min (UINT8)
        if ((flags and FLAG_EXPENDED_ENERGY) != 0 && offset + 5 <= bytes.size) {
            val rawTotal = readUInt16LE(bytes, offset)
            if (rawTotal != FTMS_UINT16_UNAVAILABLE) {
                totalEnergy = rawTotal
            }
            offset += 5
        }

        if ((flags and FLAG_HEART_RATE) != 0 && offset + 1 <= bytes.size) {
            val rawHr = readUInt8(bytes, offset)
            heartRate = if (rawHr > 0) rawHr else null
            offset += 1
        }

        if ((flags and FLAG_METABOLIC_EQUIV) != 0 && offset + 1 <= bytes.size) {
            met = readUInt8(bytes, offset) * 0.1f
            offset += 1
        }

        if ((flags and FLAG_ELAPSED_TIME) != 0 && offset + 2 <= bytes.size) {
            elapsedTime = readUInt16LE(bytes, offset)
            offset += 2
        }

        if ((flags and FLAG_REMAINING_TIME) != 0 && offset + 2 <= bytes.size) {
            remainingTime = readUInt16LE(bytes, offset)
        }

        return FtmsRowerPacket(
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
            metabolicEquivalent = met,
            elapsedTimeSec = elapsedTime,
            remainingTimeSec = remainingTime,
        )
    }

    /**
     * Parses Bluetooth SIG Heart Rate Measurement (0x2A37).
     */
    fun parseHeartRateMeasurement(bytes: ByteArray): Int? {
        if (bytes.size < 2) return null
        val flags = bytes[0].toInt() and 0xFF
        val is16Bit = (flags and 0x01) != 0
        val bpm = if (is16Bit) {
            if (bytes.size < 3) return null
            readUInt16LE(bytes, 1)
        } else {
            readUInt8(bytes, 1)
        }
        return if (bpm in 30..240) bpm else null
    }

    /**
     * Parses Bluetooth SIG Battery Level (0x2A19).
     */
    fun parseBatteryLevel(bytes: ByteArray): Int? {
        if (bytes.isEmpty()) return null
        return readUInt8(bytes, 0).coerceIn(0, 100)
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
