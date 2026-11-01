package com.example.meshcore.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import com.example.meshcore.model.DeviceInfo
import com.example.meshcore.model.EventDirection
import com.example.meshcore.model.EventType
import com.example.meshcore.model.LogEntry
import com.example.meshcore.model.ProtocolConstants
import com.example.meshcore.model.ProtocolLayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Native Android BluetoothGatt implementation of MeshTransport.
 * Communicates with MeshCore companion firmware via Nordic UART Service (NUS).
 */
@SuppressLint("MissingPermission")
class GattMeshTransport(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : MeshTransport {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    override val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _logStream = MutableSharedFlow<LogEntry>(extraBufferCapacity = 512)
    override val logStream: SharedFlow<LogEntry> = _logStream.asSharedFlow()

    override val isSimulated: Boolean = false

    private val _isArmed = MutableStateFlow(false)
    override val isArmedForIsolatedLab: StateFlow<Boolean> = _isArmed.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private var negotiatedMtu: Int = 23
    private val gattOperationMutex = Mutex()

    private var connectDeferred: CompletableDeferred<Boolean>? = null
    private var writeDeferred: CompletableDeferred<Boolean>? = null
    private var pendingResponseDeferred: CompletableDeferred<ByteArray>? = null

    override fun setIsolatedLabArmed(armed: Boolean) {
        _isArmed.value = armed
        emitLog(
            direction = EventDirection.INTERNAL,
            layer = ProtocolLayer.RADIO_PHY,
            type = EventType.INFO,
            summary = if (armed) "Lab RF Armed (Requires Physical Isolation / Attenuator)" else "Lab RF Disarmed",
            detail = "Armed flag controls research transmissions."
        )
    }

    override fun isResearchFrameInjectionSupported(): Boolean {
        // Stock MeshCore companion firmware does not expose an arbitrary frame injection driver hook.
        return false
    }

    override suspend fun connect(targetAddress: String?): Result<DeviceInfo> {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            val err = "Bluetooth adapter is unavailable or disabled"
            emitLog(EventDirection.INTERNAL, ProtocolLayer.BLE_GATT_TRANSPORT, EventType.ERROR, err)
            return Result.failure(IllegalStateException(err))
        }

        if (targetAddress.isNullOrBlank()) {
            return Result.failure(IllegalArgumentException("BLE target MAC address must not be blank"))
        }

        val device: BluetoothDevice = try {
            bluetoothAdapter.getRemoteDevice(targetAddress)
        } catch (e: Exception) {
            emitLog(EventDirection.INTERNAL, ProtocolLayer.BLE_GATT_TRANSPORT, EventType.ERROR, "Invalid address: $targetAddress")
            return Result.failure(e)
        }

        disconnect()

        _connectionState.value = ConnectionState.CONNECTING
        emitLog(
            direction = EventDirection.INTERNAL,
            layer = ProtocolLayer.BLE_GATT_TRANSPORT,
            type = EventType.INFO,
            summary = "Connecting to BLE device: ${device.name ?: "Unknown"} ($targetAddress)"
        )

        val deferred = CompletableDeferred<Boolean>()
        connectDeferred = deferred

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }

        val success = withTimeoutOrNull(15000L) {
            deferred.await()
        } ?: false

        if (!success) {
            disconnect()
            emitLog(EventDirection.INTERNAL, ProtocolLayer.BLE_GATT_TRANSPORT, EventType.ANOMALY_TIMEOUT, "Connection or service discovery timed out")
            return Result.failure(IllegalStateException("Connection timed out"))
        }

        // Perform standard handshake query (CMD_DEVICE_QUERY = 22)
        var fwVersion = "Stock MeshCore"
        try {
            val queryResp = sendCompanionCommand(byteArrayOf(ProtocolConstants.CMD_DEVICE_QUERY))
            if (queryResp.isSuccess) {
                val data = queryResp.getOrThrow()
                if (data.isNotEmpty() && data[0] == ProtocolConstants.RESP_CODE_DEVICE_INFO) {
                    fwVersion = "MeshCore (Resp: ${data.size}B)"
                }
            }
        } catch (_: Exception) {
            // Handshake query optional fallback
        }

        val info = DeviceInfo(
            name = device.name ?: "MeshCore Radio",
            address = targetAddress,
            firmwareVersion = fwVersion,
            hardwareModel = "Physical MeshCore Node",
            supportsResearchFrames = false,
            isSimulated = false
        )
        _deviceInfo.value = info
        _connectionState.value = ConnectionState.CONNECTED

        emitLog(
            direction = EventDirection.INTERNAL,
            layer = ProtocolLayer.BLE_GATT_TRANSPORT,
            type = EventType.TRANSPORT_COMPLETED,
            summary = "GATT connected & ready. MTU: $negotiatedMtu. Notifications subscribed."
        )

        return Result.success(info)
    }

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTING
        cancelQueuedOperations()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {
        } finally {
            bluetoothGatt = null
            rxCharacteristic = null
            txCharacteristic = null
            _deviceInfo.value = null
            _connectionState.value = ConnectionState.DISCONNECTED
            emitLog(
                direction = EventDirection.INTERNAL,
                layer = ProtocolLayer.BLE_GATT_TRANSPORT,
                type = EventType.INFO,
                summary = "Disconnected from Bluetooth GATT."
            )
        }
    }

    override suspend fun sendCompanionCommand(commandBytes: ByteArray): Result<ByteArray> = gattOperationMutex.withLock {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return Result.failure(IllegalStateException("Not connected"))
        }
        val gatt = bluetoothGatt ?: return Result.failure(IllegalStateException("GATT is null"))
        val rx = rxCharacteristic ?: return Result.failure(IllegalStateException("NUS RX characteristic not found"))

        if (commandBytes.isEmpty()) {
            return Result.failure(IllegalArgumentException("Command cannot be empty"))
        }

        emitLog(
            direction = EventDirection.OUTGOING,
            layer = ProtocolLayer.COMPANION_COMMAND,
            type = EventType.TRANSPORT_COMPLETED,
            summary = "Writing Companion Command (Opcode 0x%02X, %d B)".format(commandBytes[0], commandBytes.size),
            bytes = commandBytes
        )

        val writeDone = CompletableDeferred<Boolean>()
        writeDeferred = writeDone

        val responseDeferred = CompletableDeferred<ByteArray>()
        pendingResponseDeferred = responseDeferred

        // Perform GATT characteristic write
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(rx, commandBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            rx.value = commandBytes
            @Suppress("DEPRECATION")
            rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(rx)
        }

        val writeSuccess = withTimeoutOrNull(3000L) {
            writeDone.await()
        } ?: false

        if (!writeSuccess) {
            writeDeferred = null
            pendingResponseDeferred = null
            emitLog(EventDirection.INTERNAL, ProtocolLayer.BLE_GATT_TRANSPORT, EventType.ANOMALY_TIMEOUT, "GATT write timed out")
            return Result.failure(IllegalStateException("GATT write timed out"))
        }

        val response = withTimeoutOrNull(3000L) {
            responseDeferred.await()
        }

        writeDeferred = null
        pendingResponseDeferred = null

        return if (response != null) {
            emitLog(
                direction = EventDirection.INCOMING,
                layer = ProtocolLayer.COMPANION_COMMAND,
                type = EventType.FIRMWARE_ACCEPTED,
                summary = "Firmware Response (0x%02X, %d B)".format(response.firstOrNull() ?: 0, response.size),
                bytes = response
            )
            Result.success(response)
        } else {
            emitLog(EventDirection.INTERNAL, ProtocolLayer.COMPANION_COMMAND, EventType.ANOMALY_TIMEOUT, "No firmware response received within 3000ms")
            Result.failure(IllegalStateException("Response timeout"))
        }
    }

    override suspend fun sendStockCustomPayload(payload: ByteArray, channelIdx: Int): Result<Unit> {
        val frame = ByteArray(3 + payload.size)
        frame[0] = ProtocolConstants.CMD_SEND_CHANNEL_DATA
        frame[1] = channelIdx.toByte()
        frame[2] = payload.size.toByte()
        System.arraycopy(payload, 0, frame, 3, payload.size)

        emitLog(
            direction = EventDirection.OUTGOING,
            layer = ProtocolLayer.STOCK_CUSTOM_PAYLOAD,
            type = EventType.TRANSPORT_COMPLETED,
            summary = "Stock Custom Payload Wrapped by Firmware (${payload.size} B)",
            bytes = frame
        )

        val result = sendCompanionCommand(frame)
        return if (result.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Failed to send custom payload"))
        }
    }

    override suspend fun sendResearchFrame(frameBytes: ByteArray): Result<Unit> {
        // Strict safeguard: Stock firmware does not expose an arbitrary frame injection command.
        val err = "Complete-frame injection to radio driver is BLOCKED on physical hardware. " +
                "Stock MeshCore companion firmware does not provide an arbitrary frame injection driver hook."
        emitLog(
            direction = EventDirection.INTERNAL,
            layer = ProtocolLayer.RESEARCH_FRAME,
            type = EventType.ERROR,
            summary = "BLOCKED: Complete-frame injection requires research firmware extension.",
            bytes = frameBytes,
            detail = err
        )
        return Result.failure(UnsupportedOperationException(err))
    }

    override fun cancelQueuedOperations() {
        writeDeferred?.cancel()
        writeDeferred = null
        pendingResponseDeferred?.cancel()
        pendingResponseDeferred = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                emitLog(EventDirection.INTERNAL, ProtocolLayer.BLE_GATT_TRANSPORT, EventType.INFO, "GATT connected, requesting MTU 512...")
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                emitLog(EventDirection.INTERNAL, ProtocolLayer.BLE_GATT_TRANSPORT, EventType.SAFEGUARD_STOP, "GATT disconnected (status $status)")
                _connectionState.value = ConnectionState.DISCONNECTED
                connectDeferred?.complete(false)
                connectDeferred = null
                cancelQueuedOperations()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            negotiatedMtu = mtu
            emitLog(EventDirection.INTERNAL, ProtocolLayer.BLE_GATT_TRANSPORT, EventType.INFO, "Negotiated MTU: $mtu, discovering services...")
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(ProtocolConstants.NUS_SERVICE_UUID)
                if (service != null) {
                    rxCharacteristic = service.getCharacteristic(ProtocolConstants.NUS_RX_CHAR_UUID)
                    txCharacteristic = service.getCharacteristic(ProtocolConstants.NUS_TX_CHAR_UUID)

                    txCharacteristic?.let { tx ->
                        gatt.setCharacteristicNotification(tx, true)
                        val cccd = tx.getDescriptor(ProtocolConstants.CLIENT_CHAR_CONFIG_DESCRIPTOR_UUID)
                        if (cccd != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(cccd)
                            }
                        }
                    }

                    emitLog(EventDirection.INTERNAL, ProtocolLayer.BLE_GATT_TRANSPORT, EventType.INFO, "Discovered MeshCore NUS service & characteristics.")
                    connectDeferred?.complete(true)
                    connectDeferred = null
                } else {
                    emitLog(EventDirection.INTERNAL, ProtocolLayer.BLE_GATT_TRANSPORT, EventType.ERROR, "Device does not provide NUS service UUID (${ProtocolConstants.NUS_SERVICE_UUID})")
                    connectDeferred?.complete(false)
                    connectDeferred = null
                }
            } else {
                emitLog(EventDirection.INTERNAL, ProtocolLayer.BLE_GATT_TRANSPORT, EventType.ERROR, "Service discovery failed: $status")
                connectDeferred?.complete(false)
                connectDeferred = null
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid == ProtocolConstants.NUS_RX_CHAR_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    writeDeferred?.complete(true)
                } else {
                    emitLog(EventDirection.INTERNAL, ProtocolLayer.BLE_GATT_TRANSPORT, EventType.ERROR, "GATT write failed with status $status")
                    writeDeferred?.complete(false)
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val bytes = characteristic.value ?: ByteArray(0)
            handleIncomingBytes(bytes)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncomingBytes(value)
        }
    }

    private fun handleIncomingBytes(bytes: ByteArray) {
        if (pendingResponseDeferred?.isActive == true) {
            pendingResponseDeferred?.complete(bytes)
        } else {
            // Independent unsolicited notification
            emitLog(
                direction = EventDirection.INCOMING,
                layer = ProtocolLayer.COMPANION_COMMAND,
                type = EventType.INDEPENDENT_RECEIVED,
                summary = "Unsolicited Firmware Notification (0x%02X, %d B)".format(bytes.firstOrNull() ?: 0, bytes.size),
                bytes = bytes
            )
        }
    }

    private fun emitLog(
        direction: EventDirection,
        layer: ProtocolLayer,
        type: EventType,
        summary: String,
        bytes: ByteArray = ByteArray(0),
        detail: String? = null
    ) {
        val entry = LogEntry(
            direction = direction,
            layer = layer,
            type = type,
            summary = summary,
            bytes = bytes,
            isSimulated = false,
            detail = detail
        )
        _logStream.tryEmit(entry)
    }
}
