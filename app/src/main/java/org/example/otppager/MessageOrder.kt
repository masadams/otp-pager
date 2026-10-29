package org.example.otppager

/**
 * Conversation order from authenticated sender metadata. Each text payload carries the sender's
 * clock (`at`) and a per-chat send sequence (`n`) inside the OTP layer, so the pad's MAC covers
 * them; pad slot allocation stays separate. Older payloads without them use arrival time.
 */
internal object MessageOrder {
    private const val DAY = 86_400_000L
    /** Sender time, never after arrival (a fast clock can't jump a message to the bottom) and at most a day earlier. */
    fun sentTime(at: Long, received: Long): Long = if (at <= 0) received else at.coerceIn(received - DAY, received)
    /**
     * Sorts by time, after making each friend's messages keep their send sequence even if the
     * sender's clock moved backwards between them.
     */
    fun ordered(rows: List<StationEngine.Message>): List<StationEngine.Message> {
        var floor = Long.MIN_VALUE
        val incoming = rows.filter { it.incoming && it.order > 0 }.sortedBy { it.order }
            .map { m -> m.copy(time = maxOf(m.time, floor)).also { floor = it.time } }
        val rest = rows.filterNot { it.incoming && it.order > 0 }
        return (incoming + rest).sortedWith(compareBy({ it.time }, { if (it.incoming) 0 else 1 }, { it.order }))
    }
}
