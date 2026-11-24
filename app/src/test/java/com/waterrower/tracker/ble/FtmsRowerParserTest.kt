package com.waterrower.tracker.ble

import com.waterrower.tracker.model.RowingTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FtmsRowerParserTest {

    @Test
    fun parseCombinedRowerPacket_extractsAllStandardMetrics() {
        // Flags:
        // Bit 0 = 0 (Stroke Rate + Stroke Count present)
        // Bit 2 = 1 (Total Distance, UINT24) -> 0x0004
        // Bit 3 = 1 (Instantaneous Pace, UINT16) -> 0x0008
        // Bit 5 = 1 (Instantaneous Power, SINT16) -> 0x0020
        // Bit 8 = 1 (Expended Energy, 5 bytes) -> 0x0100
        // Bit 9 = 1 (Heart Rate, UINT8) -> 0x0200
        // Bit 11 = 1 (Elapsed Time, UINT16) -> 0x0800
        // Combined flags = 0x0B2C -> LE: 0x2C, 0x0B
        val bytes = byteArrayOf(
            0x2C, 0x0B, // flags
            52, // stroke rate = 52 * 0.5 = 26.0 spm
            0x90.toByte(), 0x01, // stroke count = 400
            0x88.toByte(), 0x13, 0x00, // total distance = 5000 m (UINT24 LE)
            0x7C, 0x00, // instantaneous pace = 124 sec (2:04 / 500m)
            0xC8.toByte(), 0x00, // instantaneous power = 200 W
            0xFA.toByte(), 0x00, 0x2C, 0x01, 0x05, // total energy = 250 kcal, perHr=300, perMin=5
            0x91.toByte(), // heart rate = 145 bpm
            0x58, 0x02, // elapsed time = 600 sec (10:00)
        )

        val packet = FtmsRowerParser.parseRowerData(bytes)
        assertNotNull(packet)
        packet!!

        assertEquals(26.0f, packet.strokeRateSpm!!, 0.01f)
        assertEquals(400, packet.strokeCount)
        assertEquals(5000, packet.totalDistanceMeters)
        assertEquals(124, packet.instantaneousPaceSec)
        assertEquals(200, packet.instantaneousPowerWatts)
        assertEquals(250, packet.totalEnergyKcal)
        assertEquals(145, packet.heartRateBpm)
        assertEquals(600, packet.elapsedTimeSec)
    }

    @Test
    fun parseSplitPackets_mergesCleanlyIntoTelemetry() {
        // Packet 1 (More Data = 0): Stroke Rate (24.5 spm), Stroke Count (120), Distance (1500m), Pace (125s)
        // Flags = 0x000C -> LE: 0x0C, 0x00
        val packet1Bytes = byteArrayOf(
            0x0C, 0x00,
            49, // 24.5 spm
            0x78, 0x00, // 120 strokes
            0xDC.toByte(), 0x05, 0x00, // 1500 m
            0x7D, 0x00, // 125 s/500m
        )

        // Packet 2 (More Data = 1): Power (190 W), Elapsed Time (375 s)
        // Flags = 0x0821 -> LE: 0x21, 0x08
        val packet2Bytes = byteArrayOf(
            0x21, 0x08,
            0xBE.toByte(), 0x00, // 190 W
            0x77, 0x01, // 375 s
        )

        val p1 = FtmsRowerParser.parseRowerData(packet1Bytes)!!
        val p2 = FtmsRowerParser.parseRowerData(packet2Bytes)!!

        val merged1 = p1.mergeInto(RowingTelemetry(), timestampMs = 1000L)
        val merged2 = p2.mergeInto(merged1, timestampMs = 1200L)

        assertEquals(24.5f, merged2.strokeRateSpm, 0.01f)
        assertEquals(120, merged2.strokeCount)
        assertEquals(1500, merged2.distanceMeters)
        assertEquals(125, merged2.paceSecondsPer500m)
        assertEquals(190, merged2.watts)
        assertEquals(375, merged2.elapsedSeconds)
    }

    @Test
    fun parseShortOrInvalidPacket_returnsNullSafely() {
        assertNull(FtmsRowerParser.parseRowerData(byteArrayOf()))
        assertNull(FtmsRowerParser.parseRowerData(byteArrayOf(0x00)))
    }

    @Test
    fun parseHeartRateMeasurement_supports8BitAnd16Bit() {
        assertEquals(142, FtmsRowerParser.parseHeartRateMeasurement(byteArrayOf(0x00, 142.toByte())))
        assertEquals(158, FtmsRowerParser.parseHeartRateMeasurement(byteArrayOf(0x01, 158.toByte(), 0x00)))
        assertNull(FtmsRowerParser.parseHeartRateMeasurement(byteArrayOf(0x00, 0x00)))
    }

    @Test
    fun parseBatteryLevel_clampsAndReadsCorrectly() {
        assertEquals(84, FtmsRowerParser.parseBatteryLevel(byteArrayOf(84)))
        assertEquals(100, FtmsRowerParser.parseBatteryLevel(byteArrayOf(120)))
        assertNull(FtmsRowerParser.parseBatteryLevel(byteArrayOf()))
    }
}
