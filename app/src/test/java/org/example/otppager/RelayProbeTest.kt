package org.example.otppager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayProbeTest {
    @Test fun skippedStepsDoNotFailButAnyFailureDoes() {
        val passed = RelayProbe.Report("Main server", "https://relay.example", listOf(
            RelayProbe.Step("Address", true, "1 found"), RelayProbe.Step("Chat access", null, "not tested")))
        assertTrue(passed.ok)
        val failed = passed.copy(steps = passed.steps + RelayProbe.Step("Relay health", false, "HTTP 502"))
        assertFalse(failed.ok)
    }
    @Test fun describeMarksEachStep() {
        val report = RelayProbe.Report("Backup 1", "https://b.example", listOf(
            RelayProbe.Step("Address", true, "2 found"), RelayProbe.Step("Secure connection", false, "refused"),
            RelayProbe.Step("Relay health", null, "not tested")))
        assertEquals("Backup 1\nhttps://b.example\n✓ Address: 2 found\n✗ Secure connection: refused\n– Relay health: not tested", report.describe())
    }
    @Test fun warningsShowButDoNotFail() {
        val report = RelayProbe.Report("Main server", "https://a.example", listOf(
            RelayProbe.Step("Post-quantum TLS", true, "classical", warn = true)))
        assertTrue(report.ok)
        assertEquals("Main server\nhttps://a.example\n! Post-quantum TLS: classical", report.describe())
    }
    @Test fun probeCursorIsTheLargestSafeRelayCursor() = assertEquals((1L shl 53) - 1, RelayProbe.PROBE_CURSOR)
}
