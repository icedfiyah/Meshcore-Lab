package com.example.meshcore.model

import com.example.meshcore.model.ProtocolConstants.MAX_LORA_PHY_MTU
import com.example.meshcore.model.ProtocolConstants.ROUTE_TYPE_TRANSPORT_DIRECT
import com.example.meshcore.model.ProtocolConstants.ROUTE_TYPE_TRANSPORT_FLOOD
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Fundamental MeshCore radio packet representation (src/Packet.h).
 * Serialized wire format:
 * [header: 1B] [transport_codes: 0 or 4B] [path_len: 1B] [path: N B] [payload: M B]
 */
data class MeshPacket(
    val header: Byte,
    val transportCode0: Int? = null, // uint16
    val transportCode1: Int? = null, // uint16
    val path: ByteArray = ByteArray(0),
    val payload: ByteArray = ByteArray(0),
    val rawBytes: ByteArray? = null
) {
    val version: Int get() = (header.toInt() and 0xC0) ushr 6
    val payloadType: Int get() = (header.toInt() and 0x3C) shr 2
    val routeType: Int get() = header.toInt() and 0x03

    val hasTransportCodes: Boolean
        get() = routeType == ROUTE_TYPE_TRANSPORT_FLOOD || routeType == ROUTE_TYPE_TRANSPORT_DIRECT

    fun payloadTypeName(): String = when (payloadType) {
        ProtocolConstants.PAYLOAD_TYPE_REQ -> "REQ (0x01)"
        ProtocolConstants.PAYLOAD_TYPE_TXT_MSG -> "TXT_MSG (0x02)"
        ProtocolConstants.PAYLOAD_TYPE_ACK -> "ACK (0x03)"
        ProtocolConstants.PAYLOAD_TYPE_ADVERT -> "ADVERT (0x04)"
        ProtocolConstants.PAYLOAD_TYPE_GRP_TXT -> "GRP_TXT (0x05)"
        ProtocolConstants.PAYLOAD_TYPE_GRP_DATA -> "GRP_DATA (0x06)"
        ProtocolConstants.PAYLOAD_TYPE_ANON_REQ -> "ANON_REQ (0x07)"
        ProtocolConstants.PAYLOAD_TYPE_RAW_CUSTOM -> "RAW_CUSTOM (0x0F)"
        else -> "UNKNOWN (0x%02X)".format(payloadType)
    }

    fun routeTypeName(): String = when (routeType) {
        ProtocolConstants.ROUTE_TYPE_FLOOD -> "FLOOD (0)"
        ProtocolConstants.ROUTE_TYPE_DIRECT -> "DIRECT (1)"
        ProtocolConstants.ROUTE_TYPE_TRANSPORT_FLOOD -> "TRANSPORT_FLOOD (2)"
        ProtocolConstants.ROUTE_TYPE_TRANSPORT_DIRECT -> "TRANSPORT_DIRECT (3)"
        else -> "UNKNOWN (%d)".format(routeType)
    }

    fun toBytes(): ByteArray {
        val stream = ByteArrayOutputStream()
        stream.write(header.toInt())

        if (hasTransportCodes && transportCode0 != null && transportCode1 != null) {
            val tcBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            tcBuf.putShort(transportCode0.toShort())
            tcBuf.putShort(transportCode1.toShort())
            stream.write(tcBuf.array())
        }

        stream.write(path.size)
        if (path.isNotEmpty()) {
            stream.write(path)
        }

        if (payload.isNotEmpty()) {
            stream.write(payload)
        }

        return stream.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MeshPacket

        if (header != other.header) return false
        if (transportCode0 != other.transportCode0) return false
        if (transportCode1 != other.transportCode1) return false
        if (!path.contentEquals(other.path)) return false
        if (!payload.contentEquals(other.payload)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = header.toInt()
        result = 31 * result + (transportCode0 ?: 0)
        result = 31 * result + (transportCode1 ?: 0)
        result = 31 * result + path.contentHashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        /**
         * Parses a raw byte array into a MeshPacket.
         * Throws ParseException if the buffer is smaller than the minimum header or exceeds MTU.
         */
        @Throws(ParseException::class)
        fun fromBytes(bytes: ByteArray): MeshPacket {
            if (bytes.isEmpty()) {
                throw ParseException("Packet buffer is empty")
            }
            if (bytes.size > MAX_LORA_PHY_MTU) {
                throw ParseException("Packet exceeds maximum LoRa PHY MTU (${bytes.size} > $MAX_LORA_PHY_MTU)")
            }

            var offset = 0
            val header = bytes[offset++]
            val routeType = header.toInt() and 0x03
            val hasTransportCodes = routeType == ROUTE_TYPE_TRANSPORT_FLOOD || routeType == ROUTE_TYPE_TRANSPORT_DIRECT

            var tc0: Int? = null
            var tc1: Int? = null
            if (hasTransportCodes) {
                if (bytes.size < offset + 4) {
                    throw ParseException("Truncated packet: missing transport codes (offset $offset, total ${bytes.size})")
                }
                val tcBuf = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN)
                tc0 = tcBuf.short.toInt() and 0xFFFF
                tc1 = tcBuf.short.toInt() and 0xFFFF
                offset += 4
            }

            if (bytes.size < offset + 1) {
                // Return packet without path or payload
                return MeshPacket(header, tc0, tc1, ByteArray(0), ByteArray(0), bytes)
            }

            val pathLen = bytes[offset++].toInt() and 0xFF
            if (bytes.size < offset + pathLen) {
                throw ParseException("Truncated packet: declared path_len $pathLen exceeds remaining bytes (${bytes.size - offset})")
            }

            val pathBytes = if (pathLen > 0) {
                bytes.copyOfRange(offset, offset + pathLen)
            } else {
                ByteArray(0)
            }
            offset += pathLen

            val payloadBytes = if (offset < bytes.size) {
                bytes.copyOfRange(offset, bytes.size)
            } else {
                ByteArray(0)
            }

            return MeshPacket(header, tc0, tc1, pathBytes, payloadBytes, bytes)
        }

        /**
         * Creates a valid MeshPacket for testing.
         */
        fun createSamplePacket(
            payloadType: Int = ProtocolConstants.PAYLOAD_TYPE_TXT_MSG,
            routeType: Int = ProtocolConstants.ROUTE_TYPE_FLOOD,
            path: ByteArray = ByteArray(0),
            payload: ByteArray = "MeshCore Lab Test".toByteArray(Charsets.UTF_8)
        ): MeshPacket {
            val headerByte = ((0 and 0x03) shl 6 or ((payloadType and 0x0F) shl 2) or (routeType and 0x03)).toByte()
            val (tc0, tc1) = if (routeType == ROUTE_TYPE_TRANSPORT_FLOOD || routeType == ROUTE_TYPE_TRANSPORT_DIRECT) {
                Pair(0x1234, 0x5678)
            } else {
                Pair(null, null)
            }
            return MeshPacket(headerByte, tc0, tc1, path, payload)
        }
    }

    class ParseException(message: String) : Exception(message)
}
