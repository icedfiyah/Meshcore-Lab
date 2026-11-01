# MeshCore Lab Test Plan & Hardware Safety Checklist

## 1. Test Objectives
MeshCore Lab is a diagnostic and robustness assessment tool for owned MeshCore companion radios operating under authorized, isolated RF conditions.

## 2. Unit Testing Strategy (Offline Local JVM)
Local JVM tests run automatically via Gradle (`testDebugUnitTest`):
- **Hex Parsing and Formatting:** Ensure strict validation, whitespace handling, odd-length rejection, and byte array conversion.
- **Protocol Fixtures:** Verify standard MeshCore companion command construction (`CMD_APP_START`, `CMD_DEVICE_QUERY`, `CMD_SET_DEVICE_TIME`, `CMD_SEND_CHANNEL_DATA`) and response code handling.
- **MeshPacket Serialization & Parsing:** Verify binary layout `[header][transport_codes][path_len][path][payload]`, route types, payload types, and maximum LoRa MTU bounds (255 bytes).
- **Deterministic Mutation Engine:** Verify that given an identical seed and baseline, the exact same sequence of mutations (bit flips, byte substitution, insertions, deletions, boundary values, length mismatches) is generated.
- **Safety Gate & Capability Enforcement:**
  - Verify RF transmission is strictly disarmed by default.
  - Verify complete-frame transmission is blocked on real hardware transports without verified research firmware.
  - Verify campaign stops immediately on budget exhaustion (frame count or airtime limit).
  - Verify queue cancellation on transport disconnect and no automatic replay of pending cases.
  - Verify separation of simulated events and real hardware events.

## 3. Hardware Test Checklist (Authorized Testbench)
Before connecting to physical radio hardware:
1. **Ownership & Authorization:** Confirm the test devices are personally owned or explicitly authorized for testing.
2. **Physical RF Isolation:** Verify the testbench is isolated (e.g., Faraday tent, RF shielded enclosure, or 50Ω coaxial dummy load with >= 30dB attenuators). Do NOT test on open antennas that reach public or community mesh nodes.
3. **Firmware Baseline:** Flash official stock MeshCore companion firmware or verified research firmware. Note firmware version and build date.
4. **Disarmed Verification:** Ensure app launches in "RF Disarmed" state. Verify no RF transmission occurs during BLE connection or handshake.
5. **BLE Connection:** Pair or connect using standard Android BLE settings. Verify device query succeeds and firmware info is retrieved.
6. **Stock Payload Testing:** Verify `PAYLOAD_TYPE_RAW_CUSTOM` payloads transmit via stock companion commands (`CMD_SEND_CHANNEL_DATA`).
7. **Research Frame Check:** Verify the UI clearly marks real complete-frame injection as "BLOCKED (Missing Research Firmware)" when connected to stock firmware.
8. **Emergency Stop:** Confirm that clicking "EMERGENCY STOP" or disconnecting BLE immediately halts all queued transfers.
