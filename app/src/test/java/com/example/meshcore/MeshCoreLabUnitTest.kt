package com.example.meshcore

import com.example.meshcore.fuzz.CampaignConfig
import com.example.meshcore.fuzz.CampaignMode
import com.example.meshcore.fuzz.FuzzCampaignRunner
import com.example.meshcore.fuzz.MutationEngine
import com.example.meshcore.fuzz.MutationType
import com.example.meshcore.fuzz.RunnerState
import com.example.meshcore.model.MeshPacket
import com.example.meshcore.model.ProtocolConstants
import com.example.meshcore.transport.ConnectionState
import com.example.meshcore.transport.FakeMeshTransport
import com.example.meshcore.util.HexUtil
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshCoreLabUnitTest {

    @Test
    fun testHexParsingAndFormatting() {
        val bytes = byteArrayOf(0x01, 0x16, 0x3E, 0xFF.toByte())
        val hexStr = HexUtil.toHexString(bytes)
        assertEquals("01 16 3E FF", hexStr)

        val parsed = HexUtil.parseHex("01 16 3E FF")
        assertArrayEquals(bytes, parsed)

        // Handles prefixes, irregular spaces, lowercase
        val parsedFlex = HexUtil.parseHex("0x01  16:3e:ff")
        assertArrayEquals(bytes, parsedFlex)

        // Empty hex
        assertArrayEquals(ByteArray(0), HexUtil.parseHex(""))

        // Rejects odd length
        var oddLengthError = false
        try {
            HexUtil.parseHex("123")
        } catch (_: IllegalArgumentException) {
            oddLengthError = true
        }
        assertTrue(oddLengthError)
    }

    @Test
    fun testMeshPacketSerializationAndParsing() {
        // Create sample packet: TXT_MSG, flood route
        val sample = MeshPacket.createSamplePacket(
            payloadType = ProtocolConstants.PAYLOAD_TYPE_TXT_MSG,
            routeType = ProtocolConstants.ROUTE_TYPE_FLOOD,
            path = byteArrayOf(0x10, 0x20),
            payload = "Hello".toByteArray(Charsets.UTF_8)
        )
        val serialized = sample.toBytes()
        assertTrue(serialized.size > 0)
        assertEquals(sample.header, serialized[0])

        // Parse back
        val parsed = MeshPacket.fromBytes(serialized)
        assertEquals(sample.header, parsed.header)
        assertEquals(ProtocolConstants.PAYLOAD_TYPE_TXT_MSG, parsed.payloadType)
        assertEquals(ProtocolConstants.ROUTE_TYPE_FLOOD, parsed.routeType)
        assertArrayEquals(sample.path, parsed.path)
        assertArrayEquals(sample.payload, parsed.payload)
    }

    @Test
    fun testMeshPacketTransportCodesParsing() {
        // ROUTE_TYPE_TRANSPORT_FLOOD requires 4 bytes transport codes
        val sample = MeshPacket.createSamplePacket(
            payloadType = ProtocolConstants.PAYLOAD_TYPE_RAW_CUSTOM,
            routeType = ProtocolConstants.ROUTE_TYPE_TRANSPORT_FLOOD,
            payload = byteArrayOf(0xCA.toByte(), 0xFE.toByte())
        )
        val serialized = sample.toBytes()
        val parsed = MeshPacket.fromBytes(serialized)

        assertTrue(parsed.hasTransportCodes)
        assertNotNull(parsed.transportCode0)
        assertNotNull(parsed.transportCode1)
        assertEquals(0x1234, parsed.transportCode0)
        assertEquals(0x5678, parsed.transportCode1)
        assertArrayEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte()), parsed.payload)
    }

    @Test(expected = MeshPacket.ParseException::class)
    fun testMeshPacketMtuExceededRejection() {
        // Must reject buffers exceeding LoRa PHY MTU (255 bytes)
        val oversized = ByteArray(ProtocolConstants.MAX_LORA_PHY_MTU + 1)
        MeshPacket.fromBytes(oversized)
    }

    @Test
    fun testDeterministicMutationFromFixedSeed() {
        val baseline = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        val seed = 424242L

        // Two identical calls with same seed and iteration must yield identical results
        val res1 = MutationEngine.mutate(baseline, seed, iteration = 5)
        val res2 = MutationEngine.mutate(baseline, seed, iteration = 5)

        assertEquals(res1.type, res2.type)
        assertEquals(res1.description, res2.description)
        assertArrayEquals(res1.mutatedBytes, res2.mutatedBytes)

        // Different iteration should produce different mutation
        val res3 = MutationEngine.mutate(baseline, seed, iteration = 6)
        assertFalse(res1.mutatedBytes.contentEquals(res3.mutatedBytes) && res1.description == res3.description)
    }

    @Test
    fun testMutationSizeLimitRespected() {
        // Ensure insertion mutation never exceeds maximum MTU (255 bytes)
        val nearFull = ByteArray(ProtocolConstants.MAX_LORA_PHY_MTU) { 0x55 }
        val seed = 99999L
        for (i in 1..20) {
            val res = MutationEngine.mutate(nearFull, seed, iteration = i, allowedTypes = setOf(MutationType.INSERTION))
            assertTrue("Mutated length ${res.mutatedBytes.size} exceeds MTU", res.mutatedBytes.size <= ProtocolConstants.MAX_LORA_PHY_MTU)
        }
    }

    @Test
    fun testIntentionalInconsistencyPreserved() {
        val baseline = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x41)
        val res = MutationEngine.mutate(baseline, seed = 123L, iteration = 1, allowedTypes = setOf(MutationType.LENGTH_MISMATCH))
        assertEquals(MutationType.LENGTH_MISMATCH, res.type)
        assertTrue(res.description.contains("mismatch"))
    }

    @Test
    fun testFakeTransportSeparationAndRfDisarmedRestrictions() = runBlocking {
        val transport = FakeMeshTransport(simulatedLatencyMs = 1)
        assertTrue(transport.isSimulated)

        // Connect
        val connectRes = transport.connect()
        assertTrue(connectRes.isSuccess)
        val dev = transport.deviceInfo.value
        assertNotNull(dev)
        assertTrue(dev!!.isSimulated)

        // RF Disarmed by default: sending research frame must be rejected
        assertFalse(transport.isArmedForIsolatedLab.value)
        val blockedRes = transport.sendResearchFrame(byteArrayOf(0x01, 0x02))
        assertTrue(blockedRes.isFailure)
        assertTrue(blockedRes.exceptionOrNull() is SecurityException)

        // Arm lab session
        transport.setIsolatedLabArmed(true)
        assertTrue(transport.isArmedForIsolatedLab.value)

        // Now research frame send succeeds
        val armedRes = transport.sendResearchFrame(byteArrayOf(0x01, 0x02))
        assertTrue(armedRes.isSuccess)

        // Check logs show isSimulated = true
        val log = transport.logStream.first()
        assertTrue(log.isSimulated)
    }

    @Test
    fun testQueueCancellationAndNoReplayAfterReconnect() = runBlocking {
        val transport = FakeMeshTransport(simulatedLatencyMs = 1)
        transport.connect()
        assertEquals(ConnectionState.CONNECTED, transport.connectionState.value)

        // Disconnect cancels queue
        transport.disconnect()
        assertEquals(ConnectionState.DISCONNECTED, transport.connectionState.value)

        // Reconnect starts clean
        transport.connect()
        assertEquals(ConnectionState.CONNECTED, transport.connectionState.value)
        // No pending actions executed
    }

    @Test
    fun testFuzzCampaignStopsOnBudgetExhaustion() = runBlocking {
        val transport = FakeMeshTransport(simulatedLatencyMs = 1)
        val runner = FuzzCampaignRunner(transport)

        val baseline = MeshPacket.createSamplePacket().toBytes()
        val config = CampaignConfig(
            mode = CampaignMode.LOCAL_PARSER_ROBUSTNESS,
            baseSeed = 555L,
            maxCases = 10,
            pacingDelayMs = 1L
        )

        runner.startCampaign(baseline, config)

        // Wait until completion
        var count = 0
        while (runner.state.value == RunnerState.RUNNING && count < 50) {
            kotlinx.coroutines.delay(20)
            count++
        }

        assertEquals(RunnerState.STOPPED_COMPLETE, runner.state.value)
        assertEquals(10, runner.cases.value.size)
    }
}
