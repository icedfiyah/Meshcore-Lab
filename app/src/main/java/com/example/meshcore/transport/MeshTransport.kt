package com.example.meshcore.transport

import com.example.meshcore.model.DeviceInfo
import com.example.meshcore.model.LogEntry
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING,
    ERROR
}

interface MeshTransport {
    val connectionState: StateFlow<ConnectionState>
    val deviceInfo: StateFlow<DeviceInfo?>
    val logStream: SharedFlow<LogEntry>
    val isSimulated: Boolean
    val isArmedForIsolatedLab: StateFlow<Boolean>

    fun setIsolatedLabArmed(armed: Boolean)

    /**
     * Checks if the connected firmware natively supports complete-frame injection directly to the driver.
     * On stock MeshCore companion firmware, this returns false.
     */
    fun isResearchFrameInjectionSupported(): Boolean

    suspend fun connect(targetAddress: String? = null): Result<DeviceInfo>

    suspend fun disconnect()

    /**
     * Submits a companion command (Byte 0 = CMD_*) to the radio.
     * Completes when GATT write finishes and firmware response is received.
     */
    suspend fun sendCompanionCommand(commandBytes: ByteArray): Result<ByteArray>

    /**
     * Submits a stock custom payload (PAYLOAD_TYPE_RAW_CUSTOM).
     * The stock firmware wraps the data in standard headers, hash, and route.
     */
    suspend fun sendStockCustomPayload(payload: ByteArray, channelIdx: Int = 0): Result<Unit>

    /**
     * Submits a complete serialized frame [header][transport_codes][path][payload] to the radio driver.
     * Only available when research firmware is verified and the lab session is explicitly armed.
     * Fails with UnsupportedOperationException on stock hardware.
     */
    suspend fun sendResearchFrame(frameBytes: ByteArray): Result<Unit>

    fun cancelQueuedOperations()
}
