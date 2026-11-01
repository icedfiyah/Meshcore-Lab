# MeshCore Lab

MeshCore Lab is a minimal, native Android diagnostic and robustness assessment tool for owned MeshCore companion radios operating under authorized testbench conditions.

## Key Capabilities

1. **BLE Companion Protocol Diagnostic & Logging**
   - Connects via Nordic Semiconductor UART Service (NUS) UUIDs:
     - Service: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
     - RX (Host -> Radio): `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
     - TX (Radio -> Host): `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`
   - Logs exact incoming and outgoing byte sequences with microsecond/millisecond timestamps.
   - Categorizes events into GATT transfer, firmware response, transmit completion, and independent reception.
   - Exports complete session logs as JSON via Android Storage Access Framework.

2. **Tri-Layer Packet & Command Editors**
   - **Companion Command Editor:** Compose and send native host commands (`CMD_APP_START`, `CMD_DEVICE_QUERY`, `CMD_SET_DEVICE_TIME`, `CMD_SEND_SELF_ADVERT`, etc.).
   - **Stock Custom Payload Editor:** Compose custom binary datagrams (`PAYLOAD_TYPE_RAW_CUSTOM = 0x0F`) wrapped by stock firmware headers.
   - **Complete-Frame Research Editor:** Inspect raw wire frames `[header][transport_codes][path][payload]` up to 255 bytes (LoRa hardware MTU). Local parser inspection and hardware capability gating.

3. **Offline Robustness & Fuzzing Engine**
   - Pure deterministic mutation engine seeded by user inputs (bit flips, byte substitution, insertions/deletions, boundary values, length mismatches).
   - High-speed offline testing of the internal parser with zero RF emission.
   - Safe transport testing with rate pacing, 1 in-flight limit, strict airtime and frame budgets, and immediate emergency halt.

4. **Physical-Lab Safeguards**
   - **RF Disarmed by Default:** Hardware transmission remains strictly disarmed until manual physical authorization and laboratory confirmation.
   - **Capability Gating:** Real full-frame transmission is explicitly marked **BLOCKED** on stock MeshCore firmware because stock firmware does not expose an arbitrary frame injection driver hook.
   - **Simulated vs. Hardware Separation:** Synthetic events from `FakeMeshTransport` are clearly separated and labeled from real GATT transactions.

## Building the Project

### Local Build (Command Line)
```bash
gradle :app:assembleDebug
```
The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

### Local Unit Tests
```bash
gradle :app:testDebugUnitTest
```

### GitHub Actions CI
A complete GitHub Actions workflow is provided at `.github/workflows/build.yml` which builds the project on JDK 17, executes unit tests, and uploads the debug APK as a build artifact.
