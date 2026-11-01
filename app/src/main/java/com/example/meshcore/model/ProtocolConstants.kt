package com.example.meshcore.model

import java.util.UUID

object ProtocolConstants {
    // Nordic Semiconductor UART Service (NUS) UUIDs used by MeshCore BLE companion
    val NUS_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val NUS_RX_CHAR_UUID: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // Client -> Radio (Write)
    val NUS_TX_CHAR_UUID: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // Radio -> Client (Notify)
    val CLIENT_CHAR_CONFIG_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    // Companion Protocol Commands (Byte 0 of RX packet)
    const val CMD_APP_START: Byte = 0x01
    const val CMD_GET_CONTACTS: Byte = 0x04
    const val CMD_GET_DEVICE_TIME: Byte = 0x05
    const val CMD_SET_DEVICE_TIME: Byte = 0x06
    const val CMD_SEND_SELF_ADVERT: Byte = 0x07
    const val CMD_DEVICE_QUERY: Byte = 0x16 // 22
    const val CMD_SET_PATH_HASH_MODE: Byte = 0x3D // 61
    const val CMD_SEND_CHANNEL_DATA: Byte = 0x3E // 62
    const val CMD_TRIGGER_FIND: Byte = 0x42 // 66
    const val CMD_STOP_FIND: Byte = 0x43 // 67

    // Companion Protocol Responses & Push Codes
    const val RESP_CODE_OK: Byte = 0x00
    const val RESP_CODE_ERR: Byte = 0x01
    const val RESP_CODE_SELF_INFO: Byte = 0x05
    const val RESP_CODE_DEVICE_INFO: Byte = 0x06
    const val RESP_CODE_CONTACT: Byte = 0x08
    const val RESP_CODE_CURR_TIME: Byte = 0x09
    const val PUSH_CODE_ADVERT: Byte = 0x80.toByte()
    const val PUSH_CODE_MSG: Byte = 0x81.toByte()
    const val PUSH_CODE_RAW_DATA: Byte = 0x84.toByte()

    // MeshCore Over-The-Air Packet Header Fields (0bVVPPPPRR)
    // Route Types (bits 1..0)
    const val ROUTE_TYPE_FLOOD: Int = 0x00
    const val ROUTE_TYPE_DIRECT: Int = 0x01
    const val ROUTE_TYPE_TRANSPORT_FLOOD: Int = 0x02
    const val ROUTE_TYPE_TRANSPORT_DIRECT: Int = 0x03

    // Payload Types (bits 5..2)
    const val PAYLOAD_TYPE_REQ: Int = 0x01
    const val PAYLOAD_TYPE_TXT_MSG: Int = 0x02
    const val PAYLOAD_TYPE_ACK: Int = 0x03
    const val PAYLOAD_TYPE_ADVERT: Int = 0x04
    const val PAYLOAD_TYPE_GRP_TXT: Int = 0x05
    const val PAYLOAD_TYPE_GRP_DATA: Int = 0x06
    const val PAYLOAD_TYPE_ANON_REQ: Int = 0x07
    const val PAYLOAD_TYPE_RAW_CUSTOM: Int = 0x0F

    // Physical Hardware Limits
    const val MAX_LORA_PHY_MTU: Int = 255 // Semtech SX126x/SX127x hardware buffer limit
    const val MAX_PATH_SIZE: Int = 64
    const val MAX_PAYLOAD_SIZE: Int = 240

    // Reference protocol git commit
    const val PROTOCOL_BASELINE_COMMIT: String = "5c18b4e"
}
