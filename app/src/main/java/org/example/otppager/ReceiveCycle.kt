package org.example.otppager

/** Bounded round-robin polling; injectable transport permits real queue/replay tests without a test hook in the service. */
class ReceiveCycle {
    private var nextChat = 0
    data class Result(val newMessages: Boolean, val unread: Int, val attempted: Int, val failed: Int)
    fun poll(book: ChatBook, relay: RelayTransport = RelayClient, allowed: () -> Boolean = { true }): Result {
        val chats = book.chats(); var attempted = 0; var failed = 0; var newMessages = false
        repeat(minOf(3, chats.size)) {
            if (allowed()) {
                val chat = chats[(nextChat++).mod(chats.size)]
                try {
                    val engine = book.engine(chat.id, relay); val view = engine.view()
                    if (engine.state != null && view.padCheck != null && view.pairingComplete && !view.relayRevoked) {
                        val before = engine.state!!.getJSONArray("inbox").let { list -> (0 until list.length()).map { list.getJSONObject(it).getString("id") }.toSet() }
                        attempted++; engine.sync()
                        val after = engine.state!!.getJSONArray("inbox")
                        if ((0 until after.length()).any { after.getJSONObject(it).getString("id") !in before }) newMessages = true
                    }
                } catch (_: Exception) { failed++ }
                book.refresh(chat.id)
            }
        }
        return Result(newMessages, chats.sumOf { book.summary(it.id).unread }, attempted, failed)
    }
}
