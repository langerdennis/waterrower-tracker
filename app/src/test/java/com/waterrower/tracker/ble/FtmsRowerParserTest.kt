package com.waterrower.tracker.ble

import com.waterrower.tracker.model.RowingTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

        assertFalse(packet.isTruncated)
        assertTrue(packet.hasHeartRate)
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

    @Test
    fun parseTruncatedPacket_stopsAtExpendedEnergyWithoutShiftingHeartRateOrElapsedTime() {
        // Flags = 0x0B01 (MoreData=1, ExpendedEnergy=5B + HR=1B + Elapsed=2B)
        // Only 3 bytes remain after flags: enough for HR/Elapsed if offset shifted, NOT enough for Energy (5B)
        val truncated = byteArrayOf(
            0x01, 0x0B,
            0x90.toByte(), 0x01, 0x55,
        )

        val packet = FtmsRowerParser.parseRowerData(truncated)
        assertNotNull(packet)
        packet!!

        assertTrue(packet.isTruncated)
        assertNull(packet.totalEnergyKcal)
        assertFalse(packet.hasHeartRate)
        assertNull(packet.heartRateBpm)
        assertNull(packet.elapsedTimeSec)
    }

    @Test
    fun parseTruncatedPacket_preservesValidPrefixFieldsBeforeTruncation() {
        // Flags = 0x0B2C (Stroke 3B + Dist 3B + Pace 2B + Power 2B + Energy 5B + HR 1B + Elapsed 2B = 20B)
        // Truncate inside Energy at 14 bytes (2 of 5 energy bytes present)
        val truncated = byteArrayOf(
            0x2C, 0x0B, // flags (2)
            52, // stroke rate 26.0 spm (3)
            0x90.toByte(), 0x01, // stroke count 400 (5)
            0x88.toByte(), 0x13, 0x00, // distance 5000m (8)
            0x7C, 0x00, // pace 124s (10)
            0xC8.toByte(), 0x00, // power 200W (12)
            0xFA.toByte(), 0x00, // partial energy (14)
        )

        val packet = FtmsRowerParser.parseRowerData(truncated)!!
        assertTrue(packet.isTruncated)
        assertEquals(26.0f, packet.strokeRateSpm!!, 0.01f)
        assertEquals(400, packet.strokeCount)
        assertEquals(5000, packet.totalDistanceMeters)
        assertEquals(124, packet.instantaneousPaceSec)
        assertEquals(200, packet.instantaneousPowerWatts)
        assertNull(packet.totalEnergyKcal)
        assertFalse(packet.hasHeartRate)
        assertNull(packet.heartRateBpm)
        assertNull(packet.elapsedTimeSec)
    }

    @Test
    fun parseTruncatedPacket_atEveryFieldBoundaryOfFull30BytePacket() {
        // All optional fields enabled: MoreData=0 + Bits 1..12 = 0x1FFE -> LE 0xFE, 0x1F (30 bytes total)
        val fullPacketBytes = byteArrayOf(
            0xFE.toByte(), 0x1F, // flags (2)
            54, 0x2C, 0x01, // strokeRate=27.0, strokeCount=300 (5)
            50, // avgStrokeRate=25.0 (6)
            0xE8.toByte(), 0x03, 0x00, // totalDistance=1000 (9)
            0x78, 0x00, // instPace=120 (11)
            0x7A, 0x00, // avgPace=122 (13)
            0xDC.toByte(), 0x00, // instPower=220 (15)
            0xD2.toByte(), 0x00, // avgPower=210 (17)
            0x05, 0x00, // resistance=5 (19)
            0x64, 0x00, 0x90.toByte(), 0x01, 0x0A, // totalEnergy=100, perHr=400, perMin=10 (24)
            0x96.toByte(), // heartRate=150 (25)
            0x55, // met=8.5 (26)
            0x2C, 0x01, // elapsedTime=300 (28)
            0x58, 0x02, // remainingTime=600 (30)
        )
        assertEquals(30, fullPacketBytes.size)

        val full = FtmsRowerParser.parseRowerData(fullPacketBytes)!!
        assertFalse(full.isTruncated)
        assertEquals(27.0f, full.strokeRateSpm!!, 0.01f)
        assertEquals(300, full.strokeCount)
        assertEquals(25.0f, full.avgStrokeRateSpm!!, 0.01f)
        assertEquals(1000, full.totalDistanceMeters)
        assertEquals(120, full.instantaneousPaceSec)
        assertEquals(122, full.avgPaceSec)
        assertEquals(220, full.instantaneousPowerWatts)
        assertEquals(210, full.avgPowerWatts)
        assertEquals(5, full.resistanceLevel)
        assertEquals(100, full.totalEnergyKcal)
        assertEquals(150, full.heartRateBpm)
        assertTrue(full.hasHeartRate)
        assertEquals(8.5f, full.metabolicEquivalent!!, 0.01f)
        assertEquals(300, full.elapsedTimeSec)
        assertEquals(600, full.remainingTimeSec)

        // Every prefix length 2..29 must mark isTruncated=true and NEVER decode a shifted/wrong value
        for (len in 2 until fullPacketBytes.size) {
            val slice = fullPacketBytes.copyOfRange(0, len)
            val parsed = FtmsRowerParser.parseRowerData(slice)!!
            assertTrue("Expected truncated at len=$len", parsed.isTruncated)

            parsed.strokeRateSpm?.let { assertEquals(full.strokeRateSpm!!, it, 0.01f) }
            parsed.strokeCount?.let { assertEquals(full.strokeCount, it) }
            parsed.avgStrokeRateSpm?.let { assertEquals(full.avgStrokeRateSpm!!, it, 0.01f) }
            parsed.totalDistanceMeters?.let { assertEquals(full.totalDistanceMeters, it) }
            parsed.instantaneousPaceSec?.let { assertEquals(full.instantaneousPaceSec, it) }
            parsed.avgPaceSec?.let { assertEquals(full.avgPaceSec, it) }
            parsed.instantaneousPowerWatts?.let { assertEquals(full.instantaneousPowerWatts, it) }
            parsed.avgPowerWatts?.let { assertEquals(full.avgPowerWatts, it) }
            parsed.resistanceLevel?.let { assertEquals(full.resistanceLevel, it) }
            parsed.totalEnergyKcal?.let { assertEquals(full.totalEnergyKcal, it) }
            parsed.heartRateBpm?.let { assertEquals(full.heartRateBpm, it) }
            parsed.metabolicEquivalent?.let { assertEquals(full.metabolicEquivalent!!, it, 0.01f) }
            parsed.elapsedTimeSec?.let { assertEquals(full.elapsedTimeSec, it) }
            parsed.remainingTimeSec?.let { assertEquals(full.remainingTimeSec, it) }
        }
    }

    @Test
    fun parseRowerData_heartRateZeroClearsPreviousHeartRateOnMerge() {
        // Packet 1 with HR flag (0x0201: MoreData=1, HR=1) -> bpm = 148
        val hrActive = FtmsRowerParser.parseRowerData(byteArrayOf(0x01, 0x02, 148.toByte()))!!
        val t1 = hrActive.mergeInto(RowingTelemetry(), timestampMs = 1_000L)
        assertEquals(148, t1.heartRateBpm)

        // Packet 2 with HR flag present and value 0 -> must clear stale HR to null
        val hrZero = FtmsRowerParser.parseRowerData(byteArrayOf(0x01, 0x02, 0x00))!!
        assertTrue(hrZero.hasHeartRate)
        assertNull(hrZero.heartRateBpm)

        val t2 = hrZero.mergeInto(t1, timestampMs = 2_000L)
        assertNull(t2.heartRateBpm)
    }

    @Test
    fun parseRowerData_splitPacketWithoutHeartRateFlagPreservesPreviousHeartRate() {
        val t1 = RowingTelemetry(heartRateBpm = 148)
        // Split packet with MoreData=1 + ElapsedTime (0x0801), NO HR flag
        val split = FtmsRowerParser.parseRowerData(byteArrayOf(0x01, 0x08, 0x3C, 0x00))!!
        assertFalse(split.hasHeartRate)

        val t2 = split.mergeInto(t1, timestampMs = 2_000L)
        assertEquals(148, t2.heartRateBpm)
        assertEquals(60, t2.elapsedSeconds)
    }

    @Test
    fun parseRowerData_unavailableSentinelsAndNegativeSignedPower() {
        // MoreData=1 + InstPace(0x0008) + AvgPace(0x0010) + InstPower(0x0020) + Resistance(0x0080) + Energy(0x0100)
        // Flags = 0x01B9 -> LE 0xB9, 0x01
        val bytes = byteArrayOf(
            0xB9.toByte(), 0x01,
            0xFF.toByte(), 0xFF.toByte(), // instPace unavailable -> 0
            0xFF.toByte(), 0xFF.toByte(), // avgPace unavailable -> null
            0xFB.toByte(), 0xFF.toByte(), // instPower = -5 -> clamped to 0
            0xFE.toByte(), 0xFF.toByte(), // resistance = -2 -> preserved as -2
            0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x00, // totalEnergy unavailable -> null
        )

        val parsed = FtmsRowerParser.parseRowerData(bytes)!!
        assertFalse(parsed.isTruncated)
        assertEquals(0, parsed.instantaneousPaceSec)
        assertNull(parsed.avgPaceSec)
        assertEquals(0, parsed.instantaneousPowerWatts)
        assertEquals(-2, parsed.resistanceLevel)
        assertNull(parsed.totalEnergyKcal)
    }

    @Test
    fun parseHeartRatePacket_distinguishesZeroAndContactLostFromMalformed() {
        // Malformed packets -> null
        assertNull(FtmsRowerParser.parseHeartRatePacket(byteArrayOf()))
        assertNull(FtmsRowerParser.parseHeartRatePacket(byteArrayOf(0x00)))
        assertNull(FtmsRowerParser.parseHeartRatePacket(byteArrayOf(0x01, 0x80.toByte())))

        // Valid 8-bit packet with bpm=0 -> HeartRatePacket(null) (clears stale HR)
        val zeroHr = FtmsRowerParser.parseHeartRatePacket(byteArrayOf(0x00, 0x00))
        assertNotNull(zeroHr)
        assertNull(zeroHr!!.bpm)

        // Sensor contact supported & NOT detected (flags bits 1..2 = 0b10 = 0x04) -> HeartRatePacket(null)
        val noContact = FtmsRowerParser.parseHeartRatePacket(byteArrayOf(0x04, 140.toByte()))
        assertNotNull(noContact)
        assertNull(noContact!!.bpm)

        // Sensor contact supported & detected (0x06) + valid BPM -> 142
        val withContact = FtmsRowerParser.parseHeartRatePacket(byteArrayOf(0x06, 142.toByte()))
        assertEquals(142, withContact?.bpm)

        // Valid 16-bit packet -> 158
        val sixteenBit = FtmsRowerParser.parseHeartRatePacket(byteArrayOf(0x01, 158.toByte(), 0x00))
        assertEquals(158, sixteenBit?.bpm)
    }

    @Test
    fun describeFlags_formatsS4Comms61FlagsReadable() {
        val desc = FtmsRowerParser.describeFlags(0x0B2C)
        assertEquals("Stroke, Dist, Pace, Power, Energy, HR, Elapsed", desc)
        assertEquals("None", FtmsRowerParser.describeFlags(0x0001))
    }
}
