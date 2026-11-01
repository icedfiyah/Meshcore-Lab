package com.example.meshcore.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meshcore.export.SessionExporter
import com.example.meshcore.fuzz.CampaignConfig
import com.example.meshcore.fuzz.CampaignMode
import com.example.meshcore.fuzz.FuzzCampaignRunner
import com.example.meshcore.fuzz.FuzzCaseRecord
import com.example.meshcore.fuzz.MutationEngine
import com.example.meshcore.fuzz.MutationType
import com.example.meshcore.fuzz.RunnerState
import com.example.meshcore.model.DeviceInfo
import com.example.meshcore.model.LogEntry
import com.example.meshcore.model.MeshPacket
import com.example.meshcore.model.ProtocolConstants
import com.example.meshcore.transport.ConnectionState
import com.example.meshcore.transport.FakeMeshTransport
import com.example.meshcore.transport.GattMeshTransport
import com.example.meshcore.transport.MeshTransport
import com.example.meshcore.util.HexUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

enum class AppTab {
    CONNECT_LOGS,
    EDITORS,
    ROBUSTNESS_FUZZER,
    SAFETY_EXPORT
}

enum class EditorSubTab {
    COMPANION_COMMAND,
    STOCK_CUSTOM_PAYLOAD,
    RESEARCH_FRAME
}

class MeshCoreLabViewModel(application: Application) : AndroidViewModel(application) {

    private val fakeTransport = FakeMeshTransport()
    private val gattTransport = GattMeshTransport(application)

    private val _useSimulation = MutableStateFlow(true)
    val useSimulation: StateFlow<Boolean> = _useSimulation.asStateFlow()

    private var activeTransport: MeshTransport = fakeTransport

    val connectionState: StateFlow<ConnectionState>
        get() = activeTransport.connectionState

    val deviceInfo: StateFlow<DeviceInfo?>
        get() = activeTransport.deviceInfo

    val isArmedForIsolatedLab: StateFlow<Boolean>
        get() = activeTransport.isArmedForIsolatedLab

    private val _currentTab = MutableStateFlow(AppTab.CONNECT_LOGS)
    val currentTab: StateFlow<AppTab> = _currentTab.asStateFlow()

    private val _editorSubTab = MutableStateFlow(EditorSubTab.COMPANION_COMMAND)
    val editorSubTab: StateFlow<EditorSubTab> = _editorSubTab.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _targetMacAddress = MutableStateFlow("C0:4E:30:12:34:56")
    val targetMacAddress: StateFlow<String> = _targetMacAddress.asStateFlow()

    // Editor 1: Companion Command
    private val _companionOpcode = MutableStateFlow("16") // CMD_DEVICE_QUERY (22 = 0x16)
    val companionOpcode: StateFlow<String> = _companionOpcode.asStateFlow()

    private val _companionParamsHex = MutableStateFlow("")
    val companionParamsHex: StateFlow<String> = _companionParamsHex.asStateFlow()

    // Editor 2: Stock Custom Payload
    private val _stockChannelIdx = MutableStateFlow("0")
    val stockChannelIdx: StateFlow<String> = _stockChannelIdx.asStateFlow()

    private val _stockPayloadHex = MutableStateFlow("48 65 6C 6C 6F 20 4D 65 73 68 43 6F 72 65") // "Hello MeshCore"
    val stockPayloadHex: StateFlow<String> = _stockPayloadHex.asStateFlow()

    // Editor 3: Research Frame
    private val sampleFrameBytes = MeshPacket.createSamplePacket().toBytes()
    private val _researchFrameHex = MutableStateFlow(HexUtil.toHexString(sampleFrameBytes))
    val researchFrameHex: StateFlow<String> = _researchFrameHex.asStateFlow()

    private val _parsedResearchPacket = MutableStateFlow<MeshPacket?>(null)
    val parsedResearchPacket: StateFlow<MeshPacket?> = _parsedResearchPacket.asStateFlow()

    private val _researchParseError = MutableStateFlow<String?>(null)
    val researchParseError: StateFlow<String?> = _researchParseError.asStateFlow()

    // Fuzzer Runner
    private var fuzzRunner = FuzzCampaignRunner(activeTransport)
    val fuzzRunnerState: StateFlow<RunnerState> get() = fuzzRunner.state
    val fuzzCases: StateFlow<List<FuzzCaseRecord>> get() = fuzzRunner.cases
    val fuzzCurrentIndex: StateFlow<Int> get() = fuzzRunner.currentCaseIndex
    val fuzzAirtimeConsumedMs: StateFlow<Long> get() = fuzzRunner.consumedAirtimeMs
    val fuzzStopReason: StateFlow<String?> get() = fuzzRunner.stopReason

    // Fuzz Config
    private val _fuzzCampaignMode = MutableStateFlow(CampaignMode.LOCAL_PARSER_ROBUSTNESS)
    val fuzzCampaignMode: StateFlow<CampaignMode> = _fuzzCampaignMode.asStateFlow()

    private val _fuzzSeed = MutableStateFlow("1337")
    val fuzzSeed: StateFlow<String> = _fuzzSeed.asStateFlow()

    private val _fuzzMaxCases = MutableStateFlow("30")
    val fuzzMaxCases: StateFlow<String> = _fuzzMaxCases.asStateFlow()

    private val _fuzzPacingMs = MutableStateFlow("80")
    val fuzzPacingMs: StateFlow<String> = _fuzzPacingMs.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    init {
        subscribeTransportLogs(fakeTransport)
        parseCurrentResearchHex()
    }

    fun selectTab(tab: AppTab) {
        _currentTab.value = tab
    }

    fun selectEditorSubTab(subTab: EditorSubTab) {
        _editorSubTab.value = subTab
    }

    fun setTargetMacAddress(address: String) {
        _targetMacAddress.value = address
    }

    fun setSimulationMode(enabled: Boolean) {
        if (_useSimulation.value == enabled) return
        viewModelScope.launch {
            activeTransport.disconnect()
            _useSimulation.value = enabled
            activeTransport = if (enabled) fakeTransport else gattTransport
            fuzzRunner = FuzzCampaignRunner(activeTransport)
            subscribeTransportLogs(activeTransport)
            _statusMessage.value = if (enabled) "Switched to Fake Transport (Simulated Node)" else "Switched to Real BluetoothGatt Transport"
        }
    }

    private fun subscribeTransportLogs(transport: MeshTransport) {
        viewModelScope.launch {
            transport.logStream.collectLatest { entry ->
                _logs.value = listOf(entry) + _logs.value.take(200)
            }
        }
    }

    fun toggleLabArming() {
        val current = activeTransport.isArmedForIsolatedLab.value
        activeTransport.setIsolatedLabArmed(!current)
    }

    fun connect() {
        viewModelScope.launch {
            val addr = if (_useSimulation.value) null else _targetMacAddress.value
            val res = activeTransport.connect(addr)
            if (res.isFailure) {
                _statusMessage.value = "Connect failed: ${res.exceptionOrNull()?.message}"
            } else {
                _statusMessage.value = "Connected to ${res.getOrNull()?.name}"
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            activeTransport.disconnect()
            _statusMessage.value = "Disconnected"
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    // Companion Editor Actions
    fun setCompanionOpcode(opcodeHex: String) {
        _companionOpcode.value = opcodeHex
    }

    fun setCompanionParamsHex(params: String) {
        _companionParamsHex.value = params
    }

    fun sendCompanionCommandPreset(opcode: Byte, params: ByteArray = ByteArray(0)) {
        val cmd = ByteArray(1 + params.size)
        cmd[0] = opcode
        if (params.isNotEmpty()) {
            System.arraycopy(params, 0, cmd, 1, params.size)
        }
        viewModelScope.launch {
            val res = activeTransport.sendCompanionCommand(cmd)
            if (res.isFailure) {
                _statusMessage.value = "Command 0x%02X failed: ${res.exceptionOrNull()?.message}".format(opcode)
            } else {
                _statusMessage.value = "Command 0x%02X accepted (%d B resp)".format(opcode, res.getOrNull()?.size ?: 0)
            }
        }
    }

    fun sendCustomCompanionCommand() {
        val op = _companionOpcode.value.toIntOrNull(16)?.toByte() ?: return
        val params = try {
            HexUtil.parseHex(_companionParamsHex.value)
        } catch (e: Exception) {
            _statusMessage.value = "Invalid hex in params: ${e.message}"
            return
        }
        sendCompanionCommandPreset(op, params)
    }

    // Stock Custom Payload Actions
    fun setStockChannelIdx(idx: String) {
        _stockChannelIdx.value = idx
    }

    fun setStockPayloadHex(hex: String) {
        _stockPayloadHex.value = hex
    }

    fun sendStockCustomPayload() {
        val ch = _stockChannelIdx.value.toIntOrNull() ?: 0
        val payload = try {
            HexUtil.parseHex(_stockPayloadHex.value)
        } catch (e: Exception) {
            _statusMessage.value = "Invalid hex payload: ${e.message}"
            return
        }
        viewModelScope.launch {
            val res = activeTransport.sendStockCustomPayload(payload, ch)
            if (res.isFailure) {
                _statusMessage.value = "Stock send failed: ${res.exceptionOrNull()?.message}"
            } else {
                _statusMessage.value = "Stock custom payload sent to channel $ch"
            }
        }
    }

    // Research Frame Editor Actions
    fun setResearchFrameHex(hex: String) {
        _researchFrameHex.value = hex
        parseCurrentResearchHex()
    }

    private fun parseCurrentResearchHex() {
        try {
            val bytes = HexUtil.parseHex(_researchFrameHex.value)
            if (bytes.isEmpty()) {
                _parsedResearchPacket.value = null
                _researchParseError.value = "Empty frame buffer"
                return
            }
            val pkt = MeshPacket.fromBytes(bytes)
            _parsedResearchPacket.value = pkt
            _researchParseError.value = null
        } catch (e: Exception) {
            _parsedResearchPacket.value = null
            _researchParseError.value = e.message
        }
    }

    fun loadSampleResearchFrame(type: Int) {
        val pkt = MeshPacket.createSamplePacket(payloadType = type)
        _researchFrameHex.value = HexUtil.toHexString(pkt.toBytes())
        parseCurrentResearchHex()
    }

    fun sendResearchFrame() {
        val bytes = try {
            HexUtil.parseHex(_researchFrameHex.value)
        } catch (e: Exception) {
            _statusMessage.value = "Invalid hex in frame: ${e.message}"
            return
        }

        if (!activeTransport.isResearchFrameInjectionSupported()) {
            _statusMessage.value = "BLOCKED: Complete-frame injection requires research firmware extension."
        }

        viewModelScope.launch {
            val res = activeTransport.sendResearchFrame(bytes)
            if (res.isFailure) {
                _statusMessage.value = "Frame send rejected: ${res.exceptionOrNull()?.message}"
            } else {
                _statusMessage.value = "Complete frame submitted to driver (${bytes.size} B)"
            }
        }
    }

    // Fuzz Campaign Actions
    fun setFuzzCampaignMode(mode: CampaignMode) {
        _fuzzCampaignMode.value = mode
    }

    fun setFuzzSeed(seed: String) {
        _fuzzSeed.value = seed
    }

    fun setFuzzMaxCases(max: String) {
        _fuzzMaxCases.value = max
    }

    fun setFuzzPacingMs(pacing: String) {
        _fuzzPacingMs.value = pacing
    }

    fun startFuzzCampaign() {
        val seed = _fuzzSeed.value.toLongOrNull() ?: 1337L
        val maxCases = _fuzzMaxCases.value.toIntOrNull()?.coerceIn(1, 200) ?: 30
        val pacing = _fuzzPacingMs.value.toLongOrNull()?.coerceIn(20L, 5000L) ?: 80L

        val baseline = when (_fuzzCampaignMode.value) {
            CampaignMode.LOCAL_PARSER_ROBUSTNESS,
            CampaignMode.ISOLATED_RESEARCH_FRAME -> {
                try {
                    HexUtil.parseHex(_researchFrameHex.value)
                } catch (_: Exception) {
                    sampleFrameBytes
                }
            }
            CampaignMode.COMPANION_COMMAND -> {
                byteArrayOf(ProtocolConstants.CMD_DEVICE_QUERY)
            }
        }

        val config = CampaignConfig(
            mode = _fuzzCampaignMode.value,
            baseSeed = seed,
            maxCases = maxCases,
            pacingDelayMs = pacing
        )

        fuzzRunner.startCampaign(baseline, config)
    }

    fun stopFuzzCampaign() {
        fuzzRunner.stop()
    }

    fun pauseFuzzCampaign() {
        fuzzRunner.pause()
    }

    fun exportSessionJson(): String {
        return SessionExporter.generateSessionJson(
            deviceInfo = activeTransport.deviceInfo.value,
            isArmed = activeTransport.isArmedForIsolatedLab.value,
            logs = _logs.value,
            fuzzCases = fuzzCases.value,
            stopReason = fuzzStopReason.value
        )
    }

    fun clearStatus() {
        _statusMessage.value = null
    }
}
