package com.waterrower.tracker.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Native Android BLE GATT client tailored for WaterRower S4 ComModule (e.g. S4 Comms 61)
 * using FTMS (0x1826 / 0x2AD1), Battery Service (0x180F), and Heart Rate Service (0x180D).
 */
@SuppressLint("MissingPermission")
class BleRowerClient(
    private val context: Context,
    private val scope: CoroutineScope,
) : RowerClient {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

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

    private val foundDevicesMap = LinkedHashMap<String, ScannedDevice>()
    private val gattQueue = ArrayDeque<() -> Boolean>()
    private var gattOpInFlight = false

    private var bluetoothGatt: BluetoothGatt? = null
    private var scanTimeoutJob: Job? = null
    private var isScanning = false
    private var lastDeviceAddress: String? = null
    private var lastDeviceName: String = "S4 Comms"
    private var shouldReconnect = false
    private var reconnectJob: Job? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val name = result.scanRecord?.deviceName ?: device.name ?: ""
            val serviceUuids = result.scanRecord?.serviceUuids.orEmpty()
            val hasFtms = serviceUuids.any { it == ParcelUuid(BleUuids.FTMS_SERVICE) }
            val nameMatchesRower = name.contains("S4", ignoreCase = true) ||
                name.contains("WaterRower", ignoreCase = true) ||
                name.contains("Row", ignoreCase = true)

            if (!hasFtms && !nameMatchesRower) return

            val displayName = name.ifBlank { "FTMS Rower ($address)" }
            foundDevicesMap[address] = ScannedDevice(
                name = displayName,
                address = address,
                rssi = result.rssi,
                hasFtmsService = hasFtms,
            )
            _scannedDevices.value = foundDevicesMap.values
                .sortedWith(compareByDescending<ScannedDevice> { it.hasFtmsService }.thenByDescending { it.rssi })
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            _connectionState.value = ConnectionState.Error("BLE-Scan fehlgeschlagen (Code $errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                clearGattQueue()
                gatt.close()
                if (bluetoothGatt === gatt) {
                    bluetoothGatt = null
                }
                if (shouldReconnect && lastDeviceAddress != null) {
                    scheduleReconnect()
                } else {
                    _connectionState.value = ConnectionState.Error("Verbindung getrennt (GATT $status)")
                }
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectJob?.cancel()
                    _connectionState.value = ConnectionState.Connected(
                        deviceName = lastDeviceName,
                        address = gatt.device.address,
                        isSimulated = false,
                    )
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    clearGattQueue()
                    gatt.close()
                    if (bluetoothGatt === gatt) {
                        bluetoothGatt = null
                    }
                    if (shouldReconnect && lastDeviceAddress != null) {
                        scheduleReconnect()
                    } else {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _connectionState.value = ConnectionState.Error("Service-Erkennung fehlgeschlagen ($status)")
                return
            }

            val serviceLabels = gatt.services.map { service ->
                val shortId = service.uuid.toString().substring(4, 8).uppercase()
                val chars = service.characteristics.joinToString(", ") { ch ->
                    "0x${ch.uuid.toString().substring(4, 8).uppercase()}"
                }
                "0x$shortId [$chars]"
            }
            _diagnostics.update { it.copy(discoveredServices = serviceLabels) }

            clearGattQueue()

            // 1. Subscribe to FTMS Rower Data (0x2AD1)
            gatt.getService(BleUuids.FTMS_SERVICE)
                ?.getCharacteristic(BleUuids.FTMS_ROWER_DATA)
                ?.let { ch -> enqueueEnableNotifications(gatt, ch) }

            // 2. Subscribe to Heart Rate Measurement (0x2A37) if available
            gatt.getService(BleUuids.HEART_RATE_SERVICE)
                ?.getCharacteristic(BleUuids.HEART_RATE_MEASUREMENT)
                ?.let { ch -> enqueueEnableNotifications(gatt, ch) }

            // 3. Read & subscribe to Battery Level (0x2A19) if available
            gatt.getService(BleUuids.BATTERY_SERVICE)
                ?.getCharacteristic(BleUuids.BATTERY_LEVEL)
                ?.let { ch ->
                    enqueueReadCharacteristic(gatt, ch)
                    if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        enqueueEnableNotifications(gatt, ch)
                    }
                }

            // 4. Read Firmware Revision (0x2A26) if available
            gatt.getService(BleUuids.DEVICE_INFO_SERVICE)
                ?.getCharacteristic(BleUuids.FIRMWARE_REVISION)
                ?.let { ch -> enqueueReadCharacteristic(gatt, ch) }

            drainGattQueue()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            finishCurrentGattOp()
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicValue(characteristic, value)
            }
            finishCurrentGattOp()
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    @Suppress("DEPRECATION")
                    val bytes = characteristic.value
                    if (bytes != null) handleCharacteristicValue(characteristic, bytes)
                }
                finishCurrentGattOp()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristicValue(characteristic, value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val bytes = characteristic.value
                if (bytes != null) handleCharacteristicValue(characteristic, bytes)
            }
        }
    }

    private fun handleCharacteristicValue(
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ) {
        when (characteristic.uuid) {
            BleUuids.FTMS_ROWER_DATA -> {
                val parsed = FtmsRowerParser.parseRowerData(bytes) ?: return
                _telemetry.update { current -> parsed.mergeInto(current) }
                _diagnostics.update { diag ->
                    diag.copy(
                        lastRawHex = FtmsRowerParser.toHexString(bytes),
                        lastParsedFlagsHex = "0x%04X".format(parsed.flags),
                        packetCount = diag.packetCount + 1,
                    )
                }
            }
            BleUuids.BATTERY_LEVEL -> {
                FtmsRowerParser.parseBatteryLevel(bytes)?.let { level ->
                    _batteryPercent.value = level
                }
            }
            BleUuids.HEART_RATE_MEASUREMENT -> {
                FtmsRowerParser.parseHeartRateMeasurement(bytes)?.let { bpm ->
                    _telemetry.update { it.copy(heartRateBpm = bpm, timestampMs = System.currentTimeMillis()) }
                }
            }
            BleUuids.FIRMWARE_REVISION -> {
                val fw = bytes.toString(Charsets.UTF_8).trim()
                if (fw.isNotEmpty()) {
                    _diagnostics.update { it.copy(firmwareRevision = fw) }
                }
            }
        }
    }

    override fun startScan() {
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Bluetooth ist ausgeschaltet oder nicht verfügbar.")
            return
        }
        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            _connectionState.value = ConnectionState.Error("BLE-Scanner nicht verfügbar.")
            return
        }

        stopScan()
        foundDevicesMap.clear()
        _scannedDevices.value = emptyList()
        _connectionState.value = ConnectionState.Scanning

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
            isScanning = true
            scanTimeoutJob = scope.launch {
                delay(15_000L)
                stopScan()
                if (_connectionState.value is ConnectionState.Scanning) {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        } catch (e: SecurityException) {
            _connectionState.value = ConnectionState.Error("Bluetooth-Berechtigung fehlt: ${e.message}")
        }
    }

    override fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        if (!isScanning) return
        isScanning = false
        try {
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
    }

    override fun connect(address: String, deviceName: String) {
        stopScan()
        reconnectJob?.cancel()
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _connectionState.value = ConnectionState.Error("Bluetooth ist ausgeschaltet.")
            return
        }

        shouldReconnect = true
        lastDeviceAddress = address
        lastDeviceName = deviceName
        _connectionState.value = ConnectionState.Connecting(deviceName)

        try {
            bluetoothGatt?.close()
            val device = adapter.getRemoteDevice(address)
            bluetoothGatt = device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE,
            )
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error("Verbindungsfehler: ${e.message}")
        }
    }

    override fun disconnect() {
        shouldReconnect = false
        reconnectJob?.cancel()
        stopScan()
        clearGattQueue()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: SecurityException) {
        }
        bluetoothGatt = null
        _batteryPercent.value = null
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun resetTelemetry() {
        _telemetry.value = RowingTelemetry()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        val address = lastDeviceAddress ?: return
        _connectionState.value = ConnectionState.Connecting("$lastDeviceName (Neuverbindung…)")
        reconnectJob = scope.launch {
            delay(3_000L)
            if (shouldReconnect) {
                connect(address, lastDeviceName)
            }
        }
    }

    private fun enqueueEnableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        gattQueue.addLast {
            val notifyOk = gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(BleUuids.CCCD)
            if (!notifyOk || descriptor == null) {
                false
            } else {
                writeCccd(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
        }
    }

    private fun enqueueReadCharacteristic(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        gattQueue.addLast {
            gatt.readCharacteristic(characteristic)
        }
    }

    private fun writeCccd(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun drainGattQueue() {
        if (gattOpInFlight) return
        while (gattQueue.isNotEmpty()) {
            val op = gattQueue.removeFirst()
            gattOpInFlight = true
            val started = try {
                op()
            } catch (_: SecurityException) {
                false
            }
            if (started) return
            gattOpInFlight = false
        }
    }

    private fun finishCurrentGattOp() {
        gattOpInFlight = false
        drainGattQueue()
    }

    private fun clearGattQueue() {
        gattQueue.clear()
        gattOpInFlight = false
    }
}
