# Protocol Verification Notes: MeshCore Companion Protocol

**Target Repository:** [https://github.com/meshcore-dev/MeshCore](https://github.com/meshcore-dev/MeshCore)  
**Reference Source Commit / Tag:** `main` (commit baseline: `5c18b4e` / v1.14+ companion radio release)  
**Documented Specification:** [https://docs.meshcore.io/companion_protocol/](https://docs.meshcore.io/companion_protocol/)  
**Client Reference Repositories:** `meshcore-dev/meshcore.js`, `meshcore-dev/meshcore_py`

---

## 1. BLE Service and Characteristic UUIDs

MeshCore utilizes Nordic Semiconductor UART Service (NUS) profile UUIDs for its companion Bluetooth Low Energy interface:

* **Primary Service UUID:**  
  `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
* **RX Characteristic (Client/App -> Radio Firmware):**  
  `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`  
  - Permissions: Write / Write Without Response  
  - Usage: Client sends serialized companion command frames to this characteristic.
* **TX Characteristic (Radio Firmware -> Client/App):**  
  `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`  
  - Permissions: Notify / Indicate (Client must write Client Characteristic Configuration Descriptor `0x2902` to `0x0001` to enable notifications)  
  - Usage: Radio pushes command responses, contacts, messages, and raw packet notifications.

---

## 2. Framing and Size Limits

### BLE Framing
* Over BLE, each command or notification corresponds to a characteristic value transfer.
* Standard Android BLE link-layer handles packet framing and link integrity.
* Negotiated ATT MTU typically ranges from 23 to 512 bytes. MeshCore companion packets exceeding current MTU must observe negotiated fragmentation or fit in the MTU.
* Safe maximum BLE companion transfer block: 255 bytes (matched to LoRa physical payload maximum).

### USB/Serial Framing (Reference Comparison)
* Inbound (Host -> Radio): `<` (`0x3C` / 60) + `length` (2 bytes, Little-Endian) + `payload`
* Outbound (Radio -> Host): `>` (`0x3E` / 62) + `length` (2 bytes, Little-Endian) + `payload`

---

## 3. Companion Protocol Command Identifiers (`CMD_*`)

Commands sent from Host/App to Radio on the RX characteristic (`Byte 0 = Command ID`):

| Opcode | Name | Parameters | Expected Response |
| :--- | :--- | :--- | :--- |
| `0x01` (1) | `CMD_APP_START` | `[app_ver_major, app_ver_minor]` | `RESP_CODE_SELF_INFO` (`0x05`) |
| `0x04` (4) | `CMD_GET_CONTACTS` | `[since_timestamp: uint32 LE]` | Sequence of `RESP_CODE_CONTACT` (`0x08`) + `RESP_CODE_OK` (`0x00`) |
| `0x05` (5) | `CMD_GET_DEVICE_TIME` | None | `RESP_CODE_CURR_TIME` (`0x09`) |
| `0x06` (6) | `CMD_SET_DEVICE_TIME` | `[epoch_seconds: uint32 LE]` | `RESP_CODE_OK` (`0x00`) |
| `0x07` (7) | `CMD_SEND_SELF_ADVERT` | `[flood_flag: uint8]` | `RESP_CODE_OK` (`0x00`) |
| `0x16` (22)| `CMD_DEVICE_QUERY` | None | `RESP_CODE_DEVICE_INFO` (`0x06`) |
| `0x3D` (61)| `CMD_SET_PATH_HASH_MODE`| `[mode: uint8]` (0=Single, 1=Multi) | `RESP_CODE_OK` (`0x00`) |
| `0x3E` (62)| `CMD_SEND_CHANNEL_DATA` | `[channel_idx, flags, data_len, data...]` | `RESP_CODE_OK` (`0x00`) |
| `0x42` (66)| `CMD_TRIGGER_FIND` | None | `RESP_CODE_OK` (`0x00`) |
| `0x43` (67)| `CMD_STOP_FIND` | None | `RESP_CODE_OK` (`0x00`) |

### Common Firmware Response Codes (`RESP_*` / `PUSH_*`):
* `0x00`: `RESP_CODE_OK`
* `0x01`: `RESP_CODE_ERR`
* `0x05`: `RESP_CODE_SELF_INFO`
* `0x06`: `RESP_CODE_DEVICE_INFO`
* `0x08`: `RESP_CODE_CONTACT`
* `0x09`: `RESP_CODE_CURR_TIME`
* `0x80`: `PUSH_CODE_ADVERT`
* `0x81`: `PUSH_CODE_MSG`
* `0x84`: `PUSH_CODE_RAW_DATA` (`PAYLOAD_TYPE_RAW_CUSTOM` payload received)

---

## 4. Packet Format (`src/Packet.h`)

Fundamental over-the-air packet layout:
```
[Header: 1B] [Transport Codes: 0 or 4B] [Path Length: 1B] [Path: N B] [Payload: M B]
```

1. **Header Byte (8 bits: `0bVVPPPPRR`):**
   - Bits 7..6 (`0xC0`): **Version** (typically `0x00`)
   - Bits 5..2 (`0x3C`): **Payload Type**:
     - `0x01`: `PAYLOAD_TYPE_REQ`
     - `0x02`: `PAYLOAD_TYPE_TXT_MSG`
     - `0x03`: `PAYLOAD_TYPE_ACK`
     - `0x04`: `PAYLOAD_TYPE_ADVERT`
     - `0x05`: `PAYLOAD_TYPE_GRP_TXT`
     - `0x06`: `PAYLOAD_TYPE_GRP_DATA`
     - `0x07`: `PAYLOAD_TYPE_ANON_REQ`
     - `0x0F`: `PAYLOAD_TYPE_RAW_CUSTOM` (raw custom application data)
   - Bits 1..0 (`0x03`): **Route Type**:
     - `0x00`: `ROUTE_TYPE_FLOOD` (0)
     - `0x01`: `ROUTE_TYPE_DIRECT` (1)
     - `0x02`: `ROUTE_TYPE_TRANSPORT_FLOOD` (2) - includes 4 bytes of transport codes
     - `0x03`: `ROUTE_TYPE_TRANSPORT_DIRECT` (3) - includes 4 bytes of transport codes
2. **Transport Codes (4 bytes, conditional):**
   - Present only if Route Type is `ROUTE_TYPE_TRANSPORT_FLOOD` or `ROUTE_TYPE_TRANSPORT_DIRECT`.
   - Encoded as two 16-bit Little-Endian integers (`transport_codes[0]`, `transport_codes[1]`).
3. **Path Length (1 byte) & Path:**
   - 1 byte `path_len` followed by `path_len` bytes (up to 64 bytes max).
4. **Payload:**
   - Remaining bytes up to maximum LoRa MTU.
5. **Physical Layer Bounds:**
   - Semtech SX1261/SX1262/SX1268 and SX1276 hardware buffer: 255 bytes maximum total physical frame size (`MAX_PACKET_LEN = 255`).

---

## 5. Scope Definitions & Full-Frame Injection Verification

The prompt requires separating these 4 protocol layers:
1. **BLE Companion-Command Bytes:** Host-to-modem control protocol over BLE NUS (`CMD_*`).
2. **Custom Payloads Wrapped by Stock Firmware:** Data sent with payload type `0x0F` (`PAYLOAD_TYPE_RAW_CUSTOM`). The stock firmware automatically manages headers, hashes, transport flags, and routing.
3. **Complete Serialized Packet Bytes Submitted to the Radio Driver:** Complete raw frame bytes (`[header][transport_codes][path][payload]`) passed to the SX126x/SX127x driver `transmit()` function.
4. **Physical-Layer Fields:** Preamble, sync word, hardware CRC, LoRa spreading factor, coding rate, and IQ inversion.

### Status of Full-Frame Injection on Stock MeshCore Firmware:
* **FINDING:** Stock MeshCore firmware does **NOT** expose a companion command for arbitrary complete-frame injection to the radio driver. Stock companion commands only allow injecting application payload data, which the firmware wraps in valid headers.
* **SAFEGUARD ACTION:** As prescribed in the prompt:
  - Real complete-frame transmission is marked **BLOCKED / UNAVAILABLE** on connected stock hardware.
  - The Android app defines a full backend interface (`ResearchFrameInterface`) and provides an in-memory `FakeMeshTransport` simulation to allow testing and parser validation.
  - No fake hardware success is ever reported.
