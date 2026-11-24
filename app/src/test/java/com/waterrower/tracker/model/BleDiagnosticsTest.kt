package com.waterrower.tracker.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleDiagnosticsTest {

    @Test
    fun appendPacket_capsRingBufferAtMax10EntriesAndTracksTruncation() {
        var diag = BleDiagnostics(
            discoveredServices = listOf("0x1826 [0x2AD1]"),
            firmwareRevision = "4.12",
        )

        for (i in 1..14) {
            diag = diag.appendPacket(
                BlePacketEntry(
                    timestampMs = 1_700_000_000_000L + i * 1_000L,
                    source = "0x2AD1",
                    rawHex = "%02X 0B".format(i),
                    flagsHex = "0x0B2C",
                    flagsSummary = "Stroke, Dist",
                    isTruncated = (i % 4 == 0),
                ),
            )
        }

        assertEquals(14L, diag.packetCount)
        assertEquals(3L, diag.truncatedPacketCount) // 4, 8, 12
        assertEquals(BleDiagnostics.MAX_RECENT_PACKETS, diag.recentPackets.size)
        assertEquals("05 0B", diag.recentPackets.first().rawHex)
        assertEquals("0E 0B", diag.recentPackets.last().rawHex)
        assertEquals("0E 0B", diag.lastRawHex)
        assertEquals("0x0B2C", diag.lastParsedFlagsHex)
    }

    @Test
    fun formatForClipboard_includesFirmwareServicesAndPacketHistory() {
        val diag = BleDiagnostics(
            discoveredServices = listOf("0x1826 [0x2AD1]", "0x180F [0x2A19]"),
            firmwareRevision = "S4-2.1",
        ).appendPacket(
            BlePacketEntry(
                timestampMs = 1_700_000_000_000L,
                source = "0x2AD1",
                rawHex = "2C 0B 34",
                flagsHex = "0x0B2C",
                flagsSummary = "Stroke, Dist",
                isTruncated = true,
            ),
        )

        val report = diag.formatForClipboard()
        assertTrue(report.contains("Firmware: S4-2.1"))
        assertTrue(report.contains("Packets: 1 (truncated: 1)"))
        assertTrue(report.contains("0x1826 [0x2AD1]"))
        assertTrue(report.contains("flags=0x0B2C (Stroke, Dist)"))
        assertTrue(report.contains("2C 0B 34 [TRUNCATED]"))
    }
}
