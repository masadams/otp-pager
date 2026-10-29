package org.example.otppager

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageOrderTest {
    private fun inMsg(text: String, time: Long, n: Long) = StationEngine.Message(true, text, "received", time, order = n)
    private fun outMsg(text: String, time: Long) = StationEngine.Message(false, text, "delivered", time)
    @Test fun senderTimeIsUsedButNeverAfterArrivalOrMoreThanADayBefore() {
        assertEquals(1_000L, MessageOrder.sentTime(1_000, 5_000))
        assertEquals(5_000L, MessageOrder.sentTime(9_000, 5_000)) // fast sender clock
        assertEquals(5_000L, MessageOrder.sentTime(0, 5_000)) // older build: no `at`
        assertEquals(100_000_000L - 86_400_000L, MessageOrder.sentTime(1, 100_000_000))
    }
    @Test fun delayedDeliveryKeepsTheConversationInTheOrderItWasWritten() {
        // Friend wrote "a" at 10, I replied at 20, friend wrote "b" at 30; "a" only arrived at 40.
        val rows = listOf(inMsg("b", 30, 2), outMsg("reply", 20), inMsg("a", 10, 1))
        assertEquals(listOf("a", "reply", "b"), MessageOrder.ordered(rows).map { it.text })
    }
    @Test fun aSenderClockStepBackwardsCannotReorderTheirMessages() {
        val rows = listOf(inMsg("first", 50, 1), inMsg("second", 40, 2), inMsg("third", 60, 3))
        assertEquals(listOf("first", "second", "third"), MessageOrder.ordered(rows).map { it.text })
    }
    @Test fun messagesFromOlderBuildsSortByArrival() {
        val rows = listOf(inMsg("late", 30, 0), outMsg("mine", 20), inMsg("early", 10, 0))
        assertEquals(listOf("early", "mine", "late"), MessageOrder.ordered(rows).map { it.text })
    }
}
