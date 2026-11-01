package com.example.meshcore.model

import com.example.meshcore.util.HexUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class ProtocolLayer {
    BLE_GATT_TRANSPORT,      // Direct BLE characteristic writes / notifications
    COMPANION_COMMAND,      // Stock host commands (CMD_*)
    STOCK_CUSTOM_PAYLOAD,   // Payload wrapped by stock firmware (PAYLOAD_TYPE_RAW_CUSTOM)
    RESEARCH_FRAME,         // Arbitrary complete serialized frame to driver
    RADIO_PHY               // Physical layer properties (frequency, spreading factor, power)
}

enum class EventDirection {
    OUTGOING,
    INCOMING,
    INTERNAL
}

enum class EventType {
    TRANSPORT_COMPLETED,     // BLE characteristic write / notify confirmed
    FIRMWARE_ACCEPTED,       // Firmware responded with RESP_CODE_OK / expected response
    TRANSMIT_COMPLETED,      // Radio modem completed TX
    INDEPENDENT_RECEIVED,    // Unsolicited over-the-air packet received
    ANOMALY_TIMEOUT,         // No response within configured timeout window
    SAFEGUARD_STOP,          // Campaign stopped due to safety bounds or disconnect
    INFO,
    ERROR
}

data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestampMillis: Long = System.currentTimeMillis(),
    val direction: EventDirection,
    val layer: ProtocolLayer,
    val type: EventType,
    val summary: String,
    val bytes: ByteArray = ByteArray(0),
    val intendedBytes: ByteArray? = null,
    val isSimulated: Boolean = false,
    val detail: String? = null
) {
    val formattedTimestamp: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMillis))

    val hexString: String
        get() = HexUtil.toHexString(bytes)

    val intendedHexString: String?
        get() = intendedBytes?.let { HexUtil.toHexString(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LogEntry

        if (id != other.id) return false
        if (timestampMillis != other.timestampMillis) return false
        if (direction != other.direction) return false
        if (layer != other.layer) return false
        if (type != other.type) return false
        if (summary != other.summary) return false
        if (!bytes.contentEquals(other.bytes)) return false
        if (isSimulated != other.isSimulated) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + timestampMillis.hashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + layer.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + summary.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + isSimulated.hashCode()
        return result
    }
}
