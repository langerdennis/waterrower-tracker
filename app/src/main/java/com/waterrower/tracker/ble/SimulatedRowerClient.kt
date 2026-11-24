package com.waterrower.tracker.ble

import com.waterrower.tracker.model.BleDiagnostics
import com.waterrower.tracker.model.ConnectionState
import com.waterrower.tracker.model.RowingTelemetry
import com.waterrower.tracker.model.ScannedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _telemetry = MutableStateFlow(RowingTelemetry())
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
                name = "S4 Comms 61 (Simulator)",
                address = "SIM:00:00:00:00:61",
                rssi = -45,
                hasFtmsService = true,
            ),
        )
    }

    override fun stopScan() = Unit

    override fun connect(address: String, deviceName: String) {
        disconnect()
        _connectionState.value = ConnectionState.Connected(
            deviceName = deviceName.ifBlank { "S4 Comms 61 (Simulator)" },
            address = address.ifBlank { "SIM:00:00:00:00:61" },
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

                _telemetry.value = RowingTelemetry(
                    timestampMs = System.currentTimeMillis(),
                    distanceMeters = distanceMeters.roundToInt(),
                    elapsedSeconds = elapsed,
                    strokeRateSpm = (spm * 2f).roundToInt() / 2f,
                    strokeCount = strokeAccumulator.toInt(),
                    paceSecondsPer500m = paceSec,
                    avgPaceSecondsPer500m = 124,
                    watts = watts,
                    avgWatts = watts,
                    calories = calories.roundToInt(),
                    heartRateBpm = hr,
                )
                _diagnostics.value = _diagnostics.value.copy(
                    lastRawHex = "SIMULATED FTMS 0x2AD1",
                    lastParsedFlagsHex = "0x0BAC",
                    packetCount = elapsed.toLong(),
                )
            }
        }
    }

    override fun disconnect() {
        simJob?.cancel()
        simJob = null
        _batteryPercent.value = null
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun resetTelemetry() {
        _telemetry.value = RowingTelemetry()
    }
}
