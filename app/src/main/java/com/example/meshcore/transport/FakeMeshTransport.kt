package com.example.meshcore.transport

import com.example.meshcore.model.DeviceInfo
import com.example.meshcore.model.EventDirection
import com.example.meshcore.model.EventType
import com.example.meshcore.model.LogEntry
import com.example.meshcore.model.ProtocolConstants
import com.example.meshcore.model.ProtocolLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Deterministic fake transport for offline testbench development and automated testing.
 * Implements full MeshCore handshake and companion command protocol simulation.
 * Clearly marks all emitted events with isSimulated = true.
 */
class FakeMeshTransport(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val simulatedLatencyMs: Long = 40L
) : MeshTransport {

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    override val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _logStream = MutableSharedFlow<LogEntry>(extraBufferCapacity = 256)
    override val logStream: SharedFlow<LogEntry> = _logStream.asSharedFlow()

    override val isSimulated: Boolean = true

    private val _isArmed = MutableStateFlow(false)
    override val isArmedForIsolatedLab: StateFlow<Boolean> = _isArmed.asStateFlow()

    private var allowResearchFramesInSimulation: Boolean = true

    override fun setIsolatedLabArmed(armed: Boolean) {
        _isArmed.value = armed
        emitLog(
            direction = EventDirection.INTERNAL,
            layer = ProtocolLayer.RADIO_PHY,
            type = EventType.INFO,
            summary = if (armed) "Simulated Lab Arming: ARMED" else "Simulated Lab Arming: DISARMED",
            detail = "Safety gate updated. Requires physical isolation confirmation."
        )
    }

    override fun isResearchFrameInjectionSupported(): Boolean {
        // In simulation, we can simulate research firmware capability when armed
        return allowResearchFramesInSimulation
    }

    override suspend fun connect(targetAddress: String?): Result<DeviceInfo> {
        _connectionState.value = ConnectionState.CONNECTING
        emitLog(
            direction = EventDirection.INTERNAL,
            layer = ProtocolLayer.BLE_GATT_TRANSPORT,
            type = EventType.INFO,
            summary = "Connecting to Fake MeshCore Node (Simulated)",
            detail = "Target: 00:11:22:33:44:55"
        )

        delay(simulatedLatencyMs)

        val info = DeviceInfo(
            name = "MeshCore-Sim-Node",
            address = "00:11:22:33:44:55",
            firmwareVersion = "v1.14-sim-research",
            hardwareModel = "ESP32-S3 + SX1262 (Simulated)",
            supportsResearchFrames = true,
            isSimulated = true
        )
        _deviceInfo.value = info
        _connectionState.value = ConnectionState.CONNECTED

        emitLog(
            direction = EventDirection.INTERNAL,
            layer = ProtocolLayer.BLE_GATT_TRANSPORT,
            type = EventType.TRANSPORT_COMPLETED,
            summary = "Connected to Fake MeshCore Node. Subscribed to NUS TX Notifications."
        )

        return Result.success(info)
    }

    override suspend fun disconnect() {
        _connectionState.value = ConnectionState.DISCONNECTING
        delay(10)
        _connectionState.value = ConnectionState.DISCONNECTED
        _deviceInfo.value = null
        emitLog(
            direction = EventDirection.INTERNAL,
            layer = ProtocolLayer.BLE_GATT_TRANSPORT,
            type = EventType.INFO,
            summary = "Disconnected from Fake MeshCore Node. Canceled all queues."
        )
    }

    override suspend fun sendCompanionCommand(commandBytes: ByteArray): Result<ByteArray> {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return Result.failure(IllegalStateException("Not connected"))
        }
        if (commandBytes.isEmpty()) {
            return Result.failure(IllegalArgumentException("Command bytes cannot be empty"))
        }

        // Log outgoing command
        emitLog(
            direction = EventDirection.OUTGOING,
            layer = ProtocolLayer.COMPANION_COMMAND,
            type = EventType.TRANSPORT_COMPLETED,
            summary = "Sent Companion Command (Opcode 0x%02X)".format(commandBytes[0]),
            bytes = commandBytes
        )

        delay(simulatedLatencyMs)

        // Synthesize response based on MeshCore companion protocol
        val responseBytes = when (val opcode = commandBytes[0]) {
            ProtocolConstants.CMD_DEVICE_QUERY -> {
                byteArrayOf(
                    ProtocolConstants.RESP_CODE_DEVICE_INFO,
                    0x01, 0x0E, // fw version 1.14
                    0x45, 0x53, 0x50, 0x33, 0x32 // 'ESP32'
                )
            }
            ProtocolConstants.CMD_APP_START -> {
                byteArrayOf(
                    ProtocolConstants.RESP_CODE_SELF_INFO,
                    0x01, // node status ok
                    0x00, 0x01 // my node hash
                )
            }
            ProtocolConstants.CMD_GET_DEVICE_TIME -> {
                val buf = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                buf.put(ProtocolConstants.RESP_CODE_CURR_TIME)
                buf.putInt((System.currentTimeMillis() / 1000L).toInt())
                buf.array()
            }
            ProtocolConstants.CMD_SET_DEVICE_TIME,
            ProtocolConstants.CMD_SEND_SELF_ADVERT,
            ProtocolConstants.CMD_TRIGGER_FIND,
            ProtocolConstants.CMD_STOP_FIND,
            ProtocolConstants.CMD_SET_PATH_HASH_MODE -> {
                byteArrayOf(ProtocolConstants.RESP_CODE_OK)
            }
            ProtocolConstants.CMD_SEND_CHANNEL_DATA -> {
                byteArrayOf(ProtocolConstants.RESP_CODE_OK)
            }
            else -> {
                byteArrayOf(ProtocolConstants.RESP_CODE_ERR, opcode)
            }
        }

        emitLog(
            direction = EventDirection.INCOMING,
            layer = ProtocolLayer.COMPANION_COMMAND,
            type = EventType.FIRMWARE_ACCEPTED,
            summary = "Received Response Code 0x%02X".format(responseBytes[0]),
            bytes = responseBytes
        )

        return Result.success(responseBytes)
    }

    override suspend fun sendStockCustomPayload(payload: ByteArray, channelIdx: Int): Result<Unit> {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return Result.failure(IllegalStateException("Not connected"))
        }

        // Construct stock command frame: CMD_SEND_CHANNEL_DATA (62) + channel_idx (1B) + payload_len (1B) + payload
        val frame = ByteArray(3 + payload.size)
        frame[0] = ProtocolConstants.CMD_SEND_CHANNEL_DATA
        frame[1] = channelIdx.toByte()
        frame[2] = payload.size.toByte()
        System.arraycopy(payload, 0, frame, 3, payload.size)

        emitLog(
            direction = EventDirection.OUTGOING,
            layer = ProtocolLayer.STOCK_CUSTOM_PAYLOAD,
            type = EventType.TRANSPORT_COMPLETED,
            summary = "Stock Custom Payload Wrapped by Firmware (${payload.size} B on Ch $channelIdx)",
            bytes = frame
        )

        delay(simulatedLatencyMs)

        emitLog(
            direction = EventDirection.INCOMING,
            layer = ProtocolLayer.COMPANION_COMMAND,
            type = EventType.FIRMWARE_ACCEPTED,
            summary = "Firmware Accepted Stock Custom Payload for LoRa Queue",
            bytes = byteArrayOf(ProtocolConstants.RESP_CODE_OK)
        )

        return Result.success(Unit)
    }

    override suspend fun sendResearchFrame(frameBytes: ByteArray): Result<Unit> {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            return Result.failure(IllegalStateException("Not connected"))
        }
        if (!_isArmed.value) {
            emitLog(
                direction = EventDirection.INTERNAL,
                layer = ProtocolLayer.RESEARCH_FRAME,
                type = EventType.ERROR,
                summary = "BLOCKED: RF Disarmed. Research frame transmission requires explicit lab arming.",
                bytes = frameBytes
            )
            return Result.failure(SecurityException("RF Disarmed: Research frame transmission requires explicit lab arming"))
        }
        if (frameBytes.size > ProtocolConstants.MAX_LORA_PHY_MTU) {
            emitLog(
                direction = EventDirection.INTERNAL,
                layer = ProtocolLayer.RESEARCH_FRAME,
                type = EventType.ERROR,
                summary = "REJECTED: Frame exceeds LoRa PHY MTU (${frameBytes.size} > ${ProtocolConstants.MAX_LORA_PHY_MTU})"
            )
            return Result.failure(IllegalArgumentException("Frame exceeds MTU"))
        }

        emitLog(
            direction = EventDirection.OUTGOING,
            layer = ProtocolLayer.RESEARCH_FRAME,
            type = EventType.TRANSPORT_COMPLETED,
            summary = "Simulated Driver TX: Complete Frame (${frameBytes.size} B)",
            bytes = frameBytes
        )

        delay(simulatedLatencyMs)

        emitLog(
            direction = EventDirection.INTERNAL,
            layer = ProtocolLayer.RESEARCH_FRAME,
            type = EventType.TRANSMIT_COMPLETED,
            summary = "Simulated Radio PHY Transmit Completed (Dummy Load)",
            bytes = frameBytes
        )

        return Result.success(Unit)
    }

    override fun cancelQueuedOperations() {
        emitLog(
            direction = EventDirection.INTERNAL,
            layer = ProtocolLayer.BLE_GATT_TRANSPORT,
            type = EventType.INFO,
            summary = "Queued simulated operations canceled."
        )
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
            isSimulated = true,
            detail = detail
        )
        _logStream.tryEmit(entry)
    }
}
