package com.example

import com.example.meshcore.model.ProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fast, pure local unit test that executes in milliseconds without network or shadow SDK downloads.
 */
class ExampleLabTest {

    @Test
    fun testProtocolBaselineCommit() {
        assertEquals("5c18b4e", ProtocolConstants.PROTOCOL_BASELINE_COMMIT)
    }

    @Test
    fun testLoRaMtuLimit() {
        assertEquals(255, ProtocolConstants.MAX_LORA_PHY_MTU)
    }
}
