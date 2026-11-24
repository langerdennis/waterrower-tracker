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
import com.waterrower.tracker.model.BlePacketEntry
import com.waterrower.tracker.model.ConnectionState
import com.waterrower.tracker.model.RowingTelemetry
import com.waterrower.tracker.model.ScannedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.UUID

/**
 * Native Android BLE GATT client tailored for WaterRower S4 ComModule (e.g. S4 Comms 61)
 * using FTMS (0x1826 / 0x2AD1), Battery Service (0x180F), and Heart Rate Service (0x180D).
 */
@SuppressLint("MissingPermission")
class BleRowerClient(
    private val context: Context,
    private val scope: CoroutineScope,
) : RowerClient {

    private enum class GattOpKind {
        WRITE_CCCD,
        READ_CHARACTERISTIC,
    }

    private data class GattOp(
        val kind: GattOpKind,
        val characteristicUuid: UUID,
        val isCriticalFtmsCccd: Boolean = false,
        val execute: () -> Boolean,
    )

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

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

    private val scanLock = Any()
    private val foundDevicesMap = LinkedHashMap<String, ScannedDevice>()
    private var scanTimeoutJob: Job? = null
    private var isScanning = false

    private val gattLock = Any()
    private val gattQueue = ArrayDeque<GattOp>()
    private var gattOpInFlight = false
    private var activeGattOp: GattOp? = null
    private var gattOpSequence = 0L
    private var gattOpTimeoutJob: Job? = null
    private var connectionGeneration = 0L
    private var bluetoothGatt: BluetoothGatt? = null
    private var lastDeviceAddress: String? = null
    private var lastDeviceName: String = "S4 Comms"
    private var shouldReconnect = false
    private var reconnectAttempts = 0
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
            val snapshot = synchronized(scanLock) {
                foundDevicesMap[address] = ScannedDevice(
                    name = displayName,
                    address = address,
                    rssi = result.rssi,
                    hasFtmsService = hasFtms,
                )
                foundDevicesMap.values
                    .sortedWith(compareByDescending<ScannedDevice> { it.hasFtmsService }.thenByDescending { it.rssi })
            }
            _scannedDevices.value = snapshot
        }

        override fun onScanFailed(errorCode: Int) {
            synchronized(scanLock) {
                scanTimeoutJob?.cancel()
                scanTimeoutJob = null
                isScanning = false
            }
            _connectionState.value = ConnectionState.Error("BLE-Scan fehlgeschlagen (Code $errorCode)")
        }
    }

    private fun createGattCallback(gen: Long): BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val isCurrent = synchronized(gattLock) { connectionGeneration == gen }
            if (!isCurrent) {
                try {
                    gatt.close()
                } catch (_: Exception) {
                }
                return
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleGattFailure(
                    gatt = gatt,
                    gen = gen,
                    allowReconnect = true,
                    terminalErrorMessage = "Verbindung getrennt (GATT $status)",
                )
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    synchronized(gattLock) {
                        if (connectionGeneration != gen) return
                        reconnectJob?.cancel()
                        reconnectJob = null
                        if (bluetoothGatt == null) {
                            bluetoothGatt = gatt
                        }
                        _connectionState.value = ConnectionState.Connecting("$lastDeviceName (Initialisiere…)")
                    }
                    val discovered = try {
                        gatt.discoverServices()
                    } catch (_: Exception) {
                        false
                    }
                    if (!discovered) {
                        handleGattFailure(
                            gatt = gatt,
                            gen = gen,
                            allowReconnect = true,
                            terminalErrorMessage = "Service-Suche konnte nicht gestartet werden.",
                        )
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val shouldRetry: Boolean
                    synchronized(gattLock) {
                        if (connectionGeneration != gen) {
                            try {
                                gatt.close()
                            } catch (_: Exception) {
                            }
                            return
                        }
                        clearGattQueueLocked()
                        if (bluetoothGatt === gatt) {
                            bluetoothGatt = null
                        }
                        shouldRetry = shouldReconnect && lastDeviceAddress != null
                        if (!shouldRetry) {
                            _batteryPercent.value = null
                            resetTelemetry()
                            _connectionState.value = ConnectionState.Disconnected
                        }
                    }
                    try {
                        gatt.close()
                    } catch (_: Exception) {
                    }
                    if (shouldRetry) {
                        scheduleReconnect(expectedGen = gen)
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val isCurrent = synchronized(gattLock) { connectionGeneration == gen }
            if (!isCurrent) return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                handleGattFailure(
                    gatt = gatt,
                    gen = gen,
                    allowReconnect = true,
                    terminalErrorMessage = "Service-Erkennung fehlgeschlagen (GATT $status)",
                )
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

            val ftmsChar = gatt.getService(BleUuids.FTMS_SERVICE)
                ?.getCharacteristic(BleUuids.FTMS_ROWER_DATA)
            if (ftmsChar == null) {
                handleGattFailure(
                    gatt = gatt,
                    gen = gen,
                    allowReconnect = false,
                    terminalErrorMessage = "Kein FTMS-Service (0x1826/0x2AD1) gefunden.",
                )
                return
            }

            synchronized(gattLock) {
                if (connectionGeneration != gen) return
                clearGattQueueLocked()

                // 1. Subscribe to FTMS Rower Data (0x2AD1) — required before marking Connected
                enqueueEnableNotificationsLocked(gatt, ftmsChar, isCriticalFtms = true)

                // 2. Subscribe to Heart Rate Measurement (0x2A37) if available
                gatt.getService(BleUuids.HEART_RATE_SERVICE)
                    ?.getCharacteristic(BleUuids.HEART_RATE_MEASUREMENT)
                    ?.let { ch -> enqueueEnableNotificationsLocked(gatt, ch, isCriticalFtms = false) }

                // 3. Read & subscribe to Battery Level (0x2A19) if available
                gatt.getService(BleUuids.BATTERY_SERVICE)
                    ?.getCharacteristic(BleUuids.BATTERY_LEVEL)
                    ?.let { ch ->
                        enqueueReadCharacteristicLocked(gatt, ch)
                        if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                            enqueueEnableNotificationsLocked(gatt, ch, isCriticalFtms = false)
                        }
                    }

                // 4. Read Firmware Revision (0x2A26) if available
                gatt.getService(BleUuids.DEVICE_INFO_SERVICE)
                    ?.getCharacteristic(BleUuids.FIRMWARE_REVISION)
                    ?.let { ch -> enqueueReadCharacteristicLocked(gatt, ch) }
            }

            drainGattQueue(gen)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val isCurrent = synchronized(gattLock) { connectionGeneration == gen }
            if (!isCurrent) return

            val charUuid = descriptor.characteristic?.uuid
            if (charUuid == BleUuids.FTMS_ROWER_DATA) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    synchronized(gattLock) {
                        if (connectionGeneration != gen) return
                        reconnectAttempts = 0
                        _connectionState.value = ConnectionState.Connected(
                            deviceName = lastDeviceName,
                            address = gatt.device.address,
                            isSimulated = false,
                        )
                    }
                    finishCurrentGattOp(gen, GattOpKind.WRITE_CCCD, charUuid)
                } else {
                    handleGattFailure(
                        gatt = gatt,
                        gen = gen,
                        allowReconnect = true,
                        terminalErrorMessage = "FTMS-Abo fehlgeschlagen (GATT $status)",
                    )
                }
                return
            }

            finishCurrentGattOp(gen, GattOpKind.WRITE_CCCD, charUuid)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            val isCurrent = synchronized(gattLock) { connectionGeneration == gen }
            if (!isCurrent) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleCharacteristicValue(characteristic, value)
            }
            finishCurrentGattOp(gen, GattOpKind.READ_CHARACTERISTIC, characteristic.uuid)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val isCurrent = synchronized(gattLock) { connectionGeneration == gen }
                if (!isCurrent) return
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    @Suppress("DEPRECATION")
                    val bytes = characteristic.value
                    if (bytes != null) handleCharacteristicValue(characteristic, bytes)
                }
                finishCurrentGattOp(gen, GattOpKind.READ_CHARACTERISTIC, characteristic.uuid)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val isCurrent = synchronized(gattLock) { connectionGeneration == gen }
            if (!isCurrent) return
            handleCharacteristicValue(characteristic, value)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val isCurrent = synchronized(gattLock) { connectionGeneration == gen }
                if (!isCurrent) return
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
        val nowMs = System.currentTimeMillis()
        when (characteristic.uuid) {
            BleUuids.FTMS_ROWER_DATA -> {
                val rawHex = FtmsRowerParser.toHexString(bytes)
                val parsed = FtmsRowerParser.parseRowerData(bytes)
                if (parsed == null) {
                    _diagnostics.update { diag ->
                        diag.appendPacket(
                            BlePacketEntry(
                                timestampMs = nowMs,
                                source = "0x2AD1",
                                rawHex = rawHex,
                                flagsHex = "",
                                flagsSummary = "Malformed (<2B)",
                                isTruncated = true,
                            ),
                        )
                    }
                    return
                }
                _telemetry.update { current -> parsed.mergeInto(current, timestampMs = nowMs) }
                _diagnostics.update { diag ->
                    diag.appendPacket(
                        BlePacketEntry(
                            timestampMs = nowMs,
                            source = "0x2AD1",
                            rawHex = rawHex,
                            flagsHex = "0x%04X".format(parsed.flags),
                            flagsSummary = FtmsRowerParser.describeFlags(parsed.flags),
                            isTruncated = parsed.isTruncated,
                        ),
                    )
                }
            }
            BleUuids.BATTERY_LEVEL -> {
                FtmsRowerParser.parseBatteryLevel(bytes)?.let { level ->
                    _batteryPercent.value = level
                }
            }
            BleUuids.HEART_RATE_MEASUREMENT -> {
                FtmsRowerParser.parseHeartRatePacket(bytes)?.let { hr ->
                    _telemetry.update { current ->
                        current.copy(
                            heartRateBpm = hr.bpm,
                            timestampMs = if (current.timestampMs > 0L) nowMs else 0L,
                        )
                    }
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

        stopScanInternal(resetState = false)
        synchronized(scanLock) {
            foundDevicesMap.clear()
        }
        _scannedDevices.value = emptyList()
        _connectionState.value = ConnectionState.Scanning

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, scanCallback)
            synchronized(scanLock) {
                isScanning = true
                scanTimeoutJob = scope.launch {
                    delay(SCAN_TIMEOUT_MS)
                    stopScanInternal(resetState = true)
                }
            }
        } catch (e: SecurityException) {
            synchronized(scanLock) {
                isScanning = false
            }
            _connectionState.value = ConnectionState.Error("Bluetooth-Berechtigung fehlt: ${e.message}")
        }
    }

    override fun stopScan() {
        stopScanInternal(resetState = true)
    }

    private fun stopScanInternal(resetState: Boolean) {
        val wasScanning: Boolean
        synchronized(scanLock) {
            scanTimeoutJob?.cancel()
            scanTimeoutJob = null
            wasScanning = isScanning
            isScanning = false
        }
        if (wasScanning) {
            try {
                bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (_: SecurityException) {
            } catch (_: IllegalStateException) {
            }
        }
        if (resetState && _connectionState.value is ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    override fun connect(address: String, deviceName: String) {
        connectInternal(address = address, deviceName = deviceName, isReconnect = false)
    }

    private fun connectInternal(
        address: String,
        deviceName: String,
        isReconnect: Boolean,
        expectedGen: Long? = null,
    ) {
        stopScanInternal(resetState = false)
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            synchronized(gattLock) {
                if (expectedGen != null && (connectionGeneration != expectedGen || !shouldReconnect)) {
                    return
                }
                connectionGeneration += 1L
                shouldReconnect = false
                reconnectJob?.cancel()
                reconnectJob = null
                if (isReconnect) {
                    _batteryPercent.value = null
                    resetTelemetry()
                }
                _connectionState.value = ConnectionState.Error("Bluetooth ist ausgeschaltet.")
            }
            return
        }

        val previousGatt: BluetoothGatt?
        val myGen: Long
        synchronized(gattLock) {
            if (expectedGen != null && (connectionGeneration != expectedGen || !shouldReconnect)) {
                return
            }
            connectionGeneration += 1L
            myGen = connectionGeneration
            reconnectJob?.cancel()
            reconnectJob = null
            val isFreshTarget = !isReconnect || lastDeviceAddress != address
            if (!isReconnect) {
                reconnectAttempts = 0
            }
            shouldReconnect = true
            lastDeviceAddress = address
            lastDeviceName = deviceName
            clearGattQueueLocked()
            previousGatt = bluetoothGatt
            bluetoothGatt = null

            if (isFreshTarget) {
                _batteryPercent.value = null
                resetTelemetry()
                _diagnostics.value = BleDiagnostics()
            }
            _connectionState.value = if (isReconnect) {
                ConnectionState.Connecting("$deviceName (Neuverbindung…)")
            } else {
                ConnectionState.Connecting(deviceName)
            }
        }

        try {
            previousGatt?.close()
        } catch (_: Exception) {
        }

        try {
            val device = adapter.getRemoteDevice(address)
            val newGatt = device.connectGatt(
                context,
                false,
                createGattCallback(myGen),
                BluetoothDevice.TRANSPORT_LE,
            )
            val shouldCloseNewGatt: Boolean
            synchronized(gattLock) {
                if (connectionGeneration != myGen || !shouldReconnect) {
                    shouldCloseNewGatt = true
                } else {
                    shouldCloseNewGatt = false
                    bluetoothGatt = newGatt
                    if (newGatt == null) {
                        _connectionState.value = ConnectionState.Error("Verbindungsaufbau fehlgeschlagen.")
                    }
                }
            }
            if (shouldCloseNewGatt && newGatt != null) {
                try {
                    newGatt.disconnect()
                    newGatt.close()
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            synchronized(gattLock) {
                if (connectionGeneration == myGen) {
                    _connectionState.value = ConnectionState.Error("Verbindungsfehler: ${e.message}")
                }
            }
        }
    }

    override fun disconnect() {
        val gattToClose: BluetoothGatt?
        synchronized(gattLock) {
            connectionGeneration += 1L
            shouldReconnect = false
            reconnectAttempts = 0
            reconnectJob?.cancel()
            reconnectJob = null
            clearGattQueueLocked()
            gattToClose = bluetoothGatt
            bluetoothGatt = null
            _batteryPercent.value = null
            resetTelemetry()
            _connectionState.value = ConnectionState.Disconnected
        }
        stopScanInternal(resetState = false)
        try {
            gattToClose?.disconnect()
            gattToClose?.close()
        } catch (_: Exception) {
        }
    }

    override fun resetTelemetry() {
        _telemetry.value = RowingTelemetry(timestampMs = 0L)
    }

    private fun handleGattFailure(
        gatt: BluetoothGatt?,
        gen: Long,
        allowReconnect: Boolean,
        terminalErrorMessage: String,
    ) {
        val shouldRetry: Boolean
        synchronized(gattLock) {
            if (connectionGeneration != gen) return
            clearGattQueueLocked()
            if (gatt != null && bluetoothGatt === gatt) {
                bluetoothGatt = null
            }
            if (!allowReconnect) {
                shouldReconnect = false
            }
            shouldRetry = allowReconnect && shouldReconnect && lastDeviceAddress != null
            if (!shouldRetry) {
                _batteryPercent.value = null
                resetTelemetry()
                _connectionState.value = ConnectionState.Error(terminalErrorMessage)
            }
        }
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        if (shouldRetry) {
            scheduleReconnect(expectedGen = gen)
        }
    }

    private fun scheduleReconnect(expectedGen: Long) {
        val adapter = bluetoothManager?.adapter
        if (adapter == null || !adapter.isEnabled) {
            synchronized(gattLock) {
                if (connectionGeneration != expectedGen) return
                shouldReconnect = false
                reconnectJob?.cancel()
                reconnectJob = null
                _batteryPercent.value = null
                resetTelemetry()
                _connectionState.value = ConnectionState.Disconnected
            }
            return
        }

        val jobToStart: Job? = synchronized(gattLock) {
            if (connectionGeneration != expectedGen) return
            reconnectJob?.cancel()
            reconnectJob = null
            val addr = lastDeviceAddress
            if (!shouldReconnect || addr == null) return
            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                shouldReconnect = false
                reconnectAttempts = 0
                _batteryPercent.value = null
                resetTelemetry()
                _connectionState.value = ConnectionState.Error(
                    "Verbindung verloren – bitte erneut verbinden.",
                )
                return
            }
            val attempt = reconnectAttempts
            reconnectAttempts += 1
            val deviceName = lastDeviceName
            val scheduledGen = connectionGeneration
            val delayMs = RECONNECT_BACKOFF_MS[attempt.coerceAtMost(RECONNECT_BACKOFF_MS.lastIndex)]
            _connectionState.value = ConnectionState.Connecting(
                "$deviceName (Versuch ${attempt + 1}/$MAX_RECONNECT_ATTEMPTS…)",
            )
            val job = scope.launch(start = CoroutineStart.LAZY) {
                delay(delayMs)
                connectInternal(
                    address = addr,
                    deviceName = deviceName,
                    isReconnect = true,
                    expectedGen = scheduledGen,
                )
            }
            reconnectJob = job
            job
        }
        jobToStart?.start()
    }

    private fun enqueueEnableNotificationsLocked(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        isCriticalFtms: Boolean,
    ) {
        gattQueue.addLast(
            GattOp(
                kind = GattOpKind.WRITE_CCCD,
                characteristicUuid = characteristic.uuid,
                isCriticalFtmsCccd = isCriticalFtms,
            ) {
                val notifyOk = gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(BleUuids.CCCD)
                if (!notifyOk || descriptor == null) {
                    false
                } else {
                    writeCccd(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
            },
        )
    }

    private fun enqueueReadCharacteristicLocked(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        gattQueue.addLast(
            GattOp(
                kind = GattOpKind.READ_CHARACTERISTIC,
                characteristicUuid = characteristic.uuid,
                isCriticalFtmsCccd = false,
            ) {
                gatt.readCharacteristic(characteristic)
            },
        )
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

    private fun drainGattQueue(gen: Long) {
        while (true) {
            val nextOp: GattOp
            val seq: Long
            val currentGatt: BluetoothGatt?
            synchronized(gattLock) {
                if (connectionGeneration != gen || gattOpInFlight || gattQueue.isEmpty()) return
                nextOp = gattQueue.removeFirst()
                gattOpInFlight = true
                activeGattOp = nextOp
                gattOpSequence += 1L
                seq = gattOpSequence
                currentGatt = bluetoothGatt
            }

            val started = try {
                nextOp.execute()
            } catch (_: Exception) {
                false
            }

            if (started) {
                synchronized(gattLock) {
                    if (connectionGeneration == gen && gattOpInFlight && gattOpSequence == seq) {
                        gattOpTimeoutJob?.cancel()
                        gattOpTimeoutJob = scope.launch {
                            delay(GATT_OP_TIMEOUT_MS)
                            onGattOpTimeout(seq, gen, nextOp.isCriticalFtmsCccd)
                        }
                    }
                }
                return
            }

            synchronized(gattLock) {
                if (gattOpSequence == seq) {
                    gattOpInFlight = false
                    activeGattOp = null
                }
            }
            if (nextOp.isCriticalFtmsCccd) {
                handleGattFailure(
                    gatt = currentGatt,
                    gen = gen,
                    allowReconnect = true,
                    terminalErrorMessage = "FTMS-Benachrichtigungen konnten nicht aktiviert werden.",
                )
                return
            }
        }
    }

    private fun onGattOpTimeout(seq: Long, gen: Long, isCriticalFtms: Boolean) {
        val gattToFail: BluetoothGatt?
        synchronized(gattLock) {
            if (connectionGeneration != gen || !gattOpInFlight || gattOpSequence != seq) return
            gattOpInFlight = false
            activeGattOp = null
            gattOpTimeoutJob = null
            gattToFail = bluetoothGatt
        }
        if (isCriticalFtms) {
            handleGattFailure(
                gatt = gattToFail,
                gen = gen,
                allowReconnect = true,
                terminalErrorMessage = "FTMS-Initialisierung Zeitüberschreitung.",
            )
        } else {
            drainGattQueue(gen)
        }
    }

    private fun finishCurrentGattOp(
        gen: Long,
        expectedKind: GattOpKind,
        characteristicUuid: UUID?,
    ) {
        synchronized(gattLock) {
            if (connectionGeneration != gen || !gattOpInFlight) return
            val currentOp = activeGattOp
            if (currentOp == null ||
                currentOp.kind != expectedKind ||
                (characteristicUuid != null && currentOp.characteristicUuid != characteristicUuid)
            ) {
                return
            }
            gattOpTimeoutJob?.cancel()
            gattOpTimeoutJob = null
            gattOpInFlight = false
            activeGattOp = null
        }
        drainGattQueue(gen)
    }

    private fun clearGattQueueLocked() {
        gattQueue.clear()
        gattOpInFlight = false
        activeGattOp = null
        gattOpSequence += 1L
        gattOpTimeoutJob?.cancel()
        gattOpTimeoutJob = null
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val GATT_OP_TIMEOUT_MS = 5_000L
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private val RECONNECT_BACKOFF_MS = longArrayOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
    }
}
