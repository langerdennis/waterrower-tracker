package com.waterrower.tracker.ble

import com.waterrower.tracker.model.BleDiagnostics
import com.waterrower.tracker.model.ConnectionState
import com.waterrower.tracker.model.RowingTelemetry
import com.waterrower.tracker.model.ScannedDevice
import kotlinx.coroutines.flow.StateFlow

interface RowerClient {
    val connectionState: StateFlow<ConnectionState>
    val telemetry: StateFlow<RowingTelemetry>
    val batteryPercent: StateFlow<Int?>
    val scannedDevices: StateFlow<List<ScannedDevice>>
    val diagnostics: StateFlow<BleDiagnostics>

    fun startScan()
    fun stopScan()
    fun connect(address: String, deviceName: String)
    fun disconnect()
    fun resetTelemetry()
}
