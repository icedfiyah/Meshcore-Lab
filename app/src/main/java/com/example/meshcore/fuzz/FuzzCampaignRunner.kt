package com.example.meshcore.fuzz

import com.example.meshcore.model.MeshPacket
import com.example.meshcore.transport.ConnectionState
import com.example.meshcore.transport.MeshTransport
import com.example.meshcore.util.HexUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class CampaignMode {
    LOCAL_PARSER_ROBUSTNESS, // Offline, tests MeshPacket.fromBytes, 0% RF emission
    COMPANION_COMMAND,       // Sends companion commands over transport with conservative pacing
    ISOLATED_RESEARCH_FRAME  // Sends complete driver frame (requires verified research fw & lab arming)
}

enum class CaseOutcome {
    PARSER_VALID,
    PARSER_REJECTED_GRACEFULLY,
    TRANSPORT_ACCEPTED,
    FIRMWARE_REJECTED,
    TIMEOUT_ANOMALY,
    BLOCKED_DISARMED,
    ERROR
}

data class FuzzCaseRecord(
    val caseNumber: Int,
    val seed: Long,
    val mutationType: MutationType,
    val description: String,
    val inputBytes: ByteArray,
    val outcome: CaseOutcome,
    val latencyMs: Long,
    val details: String? = null
) {
    val hexInput: String get() = HexUtil.toHexString(inputBytes)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FuzzCaseRecord

        if (caseNumber != other.caseNumber) return false
        if (seed != other.seed) return false
        if (mutationType != other.mutationType) return false
        if (outcome != other.outcome) return false
        if (latencyMs != other.latencyMs) return false
        if (!inputBytes.contentEquals(other.inputBytes)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = caseNumber
        result = 31 * result + seed.hashCode()
        result = 31 * result + mutationType.hashCode()
        result = 31 * result + outcome.hashCode()
        result = 31 * result + latencyMs.hashCode()
        result = 31 * result + inputBytes.contentHashCode()
        return result
    }
}

data class CampaignConfig(
    val mode: CampaignMode = CampaignMode.LOCAL_PARSER_ROBUSTNESS,
    val baseSeed: Long = 1337L,
    val maxCases: Int = 50,
    val pacingDelayMs: Long = 100L,
    val timeoutMs: Long = 2000L,
    val allowedMutations: Set<MutationType> = MutationType.values().toSet(),
    val maxAirtimeBudgetMs: Long = 30000L
)

enum class RunnerState {
    IDLE,
    RUNNING,
    PAUSED,
    STOPPED_COMPLETE,
    STOPPED_SAFEGUARD
}

class FuzzCampaignRunner(
    private val transport: MeshTransport,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private val _state = MutableStateFlow(RunnerState.IDLE)
    val state: StateFlow<RunnerState> = _state.asStateFlow()

    private val _cases = MutableStateFlow<List<FuzzCaseRecord>>(emptyList())
    val cases: StateFlow<List<FuzzCaseRecord>> = _cases.asStateFlow()

    private val _currentCaseIndex = MutableStateFlow(0)
    val currentCaseIndex: StateFlow<Int> = _currentCaseIndex.asStateFlow()

    private val _consumedAirtimeMs = MutableStateFlow(0L)
    val consumedAirtimeMs: StateFlow<Long> = _consumedAirtimeMs.asStateFlow()

    private val _stopReason = MutableStateFlow<String?>(null)
    val stopReason: StateFlow<String?> = _stopReason.asStateFlow()

    private var campaignJob: Job? = null
    private val executedHashes = mutableSetOf<String>()

    fun startCampaign(baseline: ByteArray, config: CampaignConfig) {
        if (_state.value == RunnerState.RUNNING) return

        _state.value = RunnerState.RUNNING
        _stopReason.value = null
        executedHashes.clear()
        _cases.value = emptyList()
        _currentCaseIndex.value = 0
        _consumedAirtimeMs.value = 0L

        campaignJob = scope.launch {
            try {
                runLoop(baseline, config)
            } catch (_: CancellationException) {
                _stopReason.value = "Campaign stopped by user"
            } catch (e: Exception) {
                _stopReason.value = "Unexpected error: ${e.message}"
            } finally {
                if (_state.value == RunnerState.RUNNING) {
                    _state.value = RunnerState.STOPPED_COMPLETE
                }
            }
        }
    }

    private suspend fun runLoop(baseline: ByteArray, config: CampaignConfig) {
        var consecutiveTimeouts = 0

        for (i in 1..config.maxCases) {
            if (!scope.isActive || _state.value != RunnerState.RUNNING) break

            // Check budget
            if (_consumedAirtimeMs.value >= config.maxAirtimeBudgetMs) {
                _stopReason.value = "Airtime budget exhausted (${_consumedAirtimeMs.value} ms)"
                _state.value = RunnerState.STOPPED_SAFEGUARD
                break
            }

            // Generate deterministic mutation
            var mutation = MutationEngine.mutate(baseline, config.baseSeed, i, config.allowedMutations)
            var attempts = 0
            while (executedHashes.contains(HexUtil.toCompactHex(mutation.mutatedBytes)) && attempts < 5) {
                attempts++
                mutation = MutationEngine.mutate(baseline, config.baseSeed + attempts * 1000L, i, config.allowedMutations)
            }
            executedHashes.add(HexUtil.toCompactHex(mutation.mutatedBytes))

            _currentCaseIndex.value = i
            val startTime = System.currentTimeMillis()

            val record = when (config.mode) {
                CampaignMode.LOCAL_PARSER_ROBUSTNESS -> {
                    // Test local MeshPacket parser without any RF or transport
                    try {
                        val packet = MeshPacket.fromBytes(mutation.mutatedBytes)
                        val elapsed = System.currentTimeMillis() - startTime
                        FuzzCaseRecord(
                            caseNumber = i,
                            seed = config.baseSeed,
                            mutationType = mutation.type,
                            description = mutation.description,
                            inputBytes = mutation.mutatedBytes,
                            outcome = CaseOutcome.PARSER_VALID,
                            latencyMs = elapsed,
                            details = "Parsed: Type=${packet.payloadTypeName()}, Route=${packet.routeTypeName()}, PayloadLen=${packet.payload.size}"
                        )
                    } catch (e: MeshPacket.ParseException) {
                        val elapsed = System.currentTimeMillis() - startTime
                        FuzzCaseRecord(
                            caseNumber = i,
                            seed = config.baseSeed,
                            mutationType = mutation.type,
                            description = mutation.description,
                            inputBytes = mutation.mutatedBytes,
                            outcome = CaseOutcome.PARSER_REJECTED_GRACEFULLY,
                            latencyMs = elapsed,
                            details = "Gracefully rejected: ${e.message}"
                        )
                    } catch (t: Throwable) {
                        val elapsed = System.currentTimeMillis() - startTime
                        FuzzCaseRecord(
                            caseNumber = i,
                            seed = config.baseSeed,
                            mutationType = mutation.type,
                            description = mutation.description,
                            inputBytes = mutation.mutatedBytes,
                            outcome = CaseOutcome.ERROR,
                            latencyMs = elapsed,
                            details = "Parser anomaly / unhandled exception: ${t.javaClass.simpleName}: ${t.message}"
                        )
                    }
                }

                CampaignMode.COMPANION_COMMAND -> {
                    // Requires connected transport
                    if (transport.connectionState.value != ConnectionState.CONNECTED) {
                        _stopReason.value = "Transport disconnected during campaign"
                        _state.value = RunnerState.STOPPED_SAFEGUARD
                        break
                    }

                    val sendResult = transport.sendCompanionCommand(mutation.mutatedBytes)
                    val elapsed = System.currentTimeMillis() - startTime
                    _consumedAirtimeMs.value += elapsed

                    if (sendResult.isSuccess) {
                        consecutiveTimeouts = 0
                        val resp = sendResult.getOrThrow()
                        val outcome = if (resp.isNotEmpty() && resp[0] == 0x00.toByte()) {
                            CaseOutcome.TRANSPORT_ACCEPTED
                        } else {
                            CaseOutcome.FIRMWARE_REJECTED
                        }
                        FuzzCaseRecord(
                            caseNumber = i,
                            seed = config.baseSeed,
                            mutationType = mutation.type,
                            description = mutation.description,
                            inputBytes = mutation.mutatedBytes,
                            outcome = outcome,
                            latencyMs = elapsed,
                            details = "Response: ${HexUtil.toHexString(resp)}"
                        )
                    } else {
                        consecutiveTimeouts++
                        FuzzCaseRecord(
                            caseNumber = i,
                            seed = config.baseSeed,
                            mutationType = mutation.type,
                            description = mutation.description,
                            inputBytes = mutation.mutatedBytes,
                            outcome = CaseOutcome.TIMEOUT_ANOMALY,
                            latencyMs = elapsed,
                            details = sendResult.exceptionOrNull()?.message
                        )
                    }
                }

                CampaignMode.ISOLATED_RESEARCH_FRAME -> {
                    // Requires transport + explicit lab arming
                    if (!transport.isArmedForIsolatedLab.value) {
                        _stopReason.value = "Campaign stopped: RF disarmed"
                        _state.value = RunnerState.STOPPED_SAFEGUARD
                        break
                    }
                    if (!transport.isResearchFrameInjectionSupported()) {
                        _stopReason.value = "Campaign stopped: Frame injection not supported on this firmware"
                        _state.value = RunnerState.STOPPED_SAFEGUARD
                        break
                    }

                    val sendResult = transport.sendResearchFrame(mutation.mutatedBytes)
                    val elapsed = System.currentTimeMillis() - startTime
                    _consumedAirtimeMs.value += elapsed

                    if (sendResult.isSuccess) {
                        consecutiveTimeouts = 0
                        FuzzCaseRecord(
                            caseNumber = i,
                            seed = config.baseSeed,
                            mutationType = mutation.type,
                            description = mutation.description,
                            inputBytes = mutation.mutatedBytes,
                            outcome = CaseOutcome.TRANSPORT_ACCEPTED,
                            latencyMs = elapsed,
                            details = "Frame submitted to driver"
                        )
                    } else {
                        consecutiveTimeouts++
                        FuzzCaseRecord(
                            caseNumber = i,
                            seed = config.baseSeed,
                            mutationType = mutation.type,
                            description = mutation.description,
                            inputBytes = mutation.mutatedBytes,
                            outcome = CaseOutcome.ERROR,
                            latencyMs = elapsed,
                            details = sendResult.exceptionOrNull()?.message
                        )
                    }
                }
            }

            _cases.value = _cases.value + record

            // Stop on repeated consecutive timeouts (suspected unresponsive device)
            if (consecutiveTimeouts >= 3) {
                _stopReason.value = "Stopped: 3 consecutive timeouts (suspected modem reset or stall)"
                _state.value = RunnerState.STOPPED_SAFEGUARD
                break
            }

            // Rate pacing
            delay(config.pacingDelayMs)
        }

        if (_state.value == RunnerState.RUNNING) {
            _stopReason.value = "Campaign completed all requested cases"
            _state.value = RunnerState.STOPPED_COMPLETE
        }
    }

    fun pause() {
        if (_state.value == RunnerState.RUNNING) {
            _state.value = RunnerState.PAUSED
        }
    }

    fun stop() {
        campaignJob?.cancel()
        _state.value = RunnerState.STOPPED_COMPLETE
        _stopReason.value = "User emergency stop"
    }
}
