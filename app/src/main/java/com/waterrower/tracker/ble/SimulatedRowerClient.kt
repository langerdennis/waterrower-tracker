package com.waterrower.tracker.ble

import com.waterrower.tracker.model.BleDiagnostics
import com.waterrower.tracker.model.BlePacketEntry
import com.waterrower.tracker.model.ConnectionState
import com.waterrower.tracker.model.RowingTelemetry
import com.waterrower.tracker.model.ScannedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Realistic WaterRower simulator for testing the Floating Overlay over other apps
 * and local workout recording without having the physical rower connected.
 */
class SimulatedRowerClient(
    private val scope: CoroutineScope,
) : RowerClient {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _telemetry = MutableStateFlow(RowingTelemetry(timestampMs = 0L))
    override val telemetry: StateFlow<RowingTelemetry> = _telemetry.asStateFlow()

    private val _batteryPercent = MutableStateFlow<Int?>(null)
    override val batteryPercent: StateFlow<Int?> = _batteryPercent.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    override val scannedDevices: StateFlow<List<ScannedDevice>> = _scannedDevices.asStateFlow()

    private val _diagnostics = MutableStateFlow(BleDiagnostics())
    override val diagnostics: StateFlow<BleDiagnostics> = _diagnostics.asStateFlow()

    private var simJob: Job? = null

    override fun startScan() {
        _scannedDevices.value = listOf(
            ScannedDevice(
                name = SIM_NAME,
                address = SIM_ADDRESS,
                rssi = -45,
                hasFtmsService = true,
            ),
        )
    }

    override fun stopScan() = Unit

    override fun connect(address: String, deviceName: String) {
        disconnect()
        _connectionState.value = ConnectionState.Connected(
            deviceName = deviceName.ifBlank { SIM_NAME },
            address = address.ifBlank { SIM_ADDRESS },
            isSimulated = true,
        )
        _batteryPercent.value = 87
        _diagnostics.value = BleDiagnostics(
            discoveredServices = listOf(
                "0x1826 [0x2AD1] (Simuliert)",
                "0x180F [0x2A19] (Simuliert)",
                "0x180D [0x2A37] (Simuliert)",
            ),
            firmwareRevision = "SIM-1.0",
        )

        var elapsed = 0
        var distanceMeters = 0.0
        var strokeAccumulator = 0.0
        var calories = 0.0

        simJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                elapsed += 1
                val wave = sin(elapsed / 12.0)
                val spm = (25.5 + wave * 2.0).toFloat()
                val paceSec = (124.0 - wave * 5.0).roundToInt() // ~2:04 / 500m
                val speedMps = 500.0 / paceSec
                distanceMeters += speedMps
                strokeAccumulator += spm / 60.0
                val watts = (2.80 * speedMps * speedMps * speedMps).roundToInt().coerceIn(80, 350)
                calories += (watts * 3.6 + 300.0) / 3600.0
                val hr = (132 + (elapsed / 25).coerceAtMost(24) + (wave * 3).roundToInt())
                val nowMs = System.currentTimeMillis()
                val distInt = distanceMeters.roundToInt()
                val spmRounded = (spm * 2f).roundToInt() / 2f
                val strokeInt = strokeAccumulator.toInt()
                val calInt = calories.roundToInt()

                _telemetry.value = RowingTelemetry(
                    timestampMs = nowMs,
                    distanceMeters = distInt,
                    elapsedSeconds = elapsed,
                    strokeRateSpm = spmRounded,
                    strokeCount = strokeInt,
                    paceSecondsPer500m = paceSec,
                    avgPaceSecondsPer500m = 124,
                    watts = watts,
                    avgWatts = watts,
                    calories = calInt,
                    heartRateBpm = hr,
                )

                val simBytes = encodeSimulatedFtmsFrame(
                    spm = spmRounded,
                    strokeCount = strokeInt,
                    distanceMeters = distInt,
                    paceSec = paceSec,
                    watts = watts,
                    calories = calInt,
                    heartRate = hr,
                    elapsedSec = elapsed,
                )
                _diagnostics.update { diag ->
                    diag.appendPacket(
                        BlePacketEntry(
                            timestampMs = nowMs,
                            source = "0x2AD1 (Sim)",
                            rawHex = FtmsRowerParser.toHexString(simBytes),
                            flagsHex = "0x%04X".format(SIM_FLAGS),
                            flagsSummary = FtmsRowerParser.describeFlags(SIM_FLAGS),
                            isTruncated = false,
                        ),
                    )
                }
            }
        }
    }

    override fun disconnect() {
        simJob?.cancel()
        simJob = null
        _batteryPercent.value = null
        resetTelemetry()
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun resetTelemetry() {
        _telemetry.value = RowingTelemetry(timestampMs = 0L)
    }

    private fun encodeSimulatedFtmsFrame(
        spm: Float,
        strokeCount: Int,
        distanceMeters: Int,
        paceSec: Int,
        watts: Int,
        calories: Int,
        heartRate: Int,
        elapsedSec: Int,
    ): ByteArray = byteArrayOf(
        (SIM_FLAGS and 0xFF).toByte(),
        ((SIM_FLAGS ushr 8) and 0xFF).toByte(),
        (spm * 2f).roundToInt().coerceIn(0, 255).toByte(),
        (strokeCount and 0xFF).toByte(),
        ((strokeCount ushr 8) and 0xFF).toByte(),
        (distanceMeters and 0xFF).toByte(),
        ((distanceMeters ushr 8) and 0xFF).toByte(),
        ((distanceMeters ushr 16) and 0xFF).toByte(),
        (paceSec and 0xFF).toByte(),
        ((paceSec ushr 8) and 0xFF).toByte(),
        (watts and 0xFF).toByte(),
        ((watts ushr 8) and 0xFF).toByte(),
        (calories and 0xFF).toByte(),
        ((calories ushr 8) and 0xFF).toByte(),
        0x00, 0x00, 0x00,
        heartRate.coerceIn(0, 255).toByte(),
        (elapsedSec and 0xFF).toByte(),
        ((elapsedSec ushr 8) and 0xFF).toByte(),
    )

    companion object {
        const val SIM_ADDRESS = "SIM:00:00:00:00:61"
        const val SIM_NAME = "S4 Comms 61 (Simulator)"
        private const val SIM_FLAGS = 0x0B2C
    }
}
