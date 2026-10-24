package org.example.otppager

import java.util.concurrent.Executors

/** All production vault work shares one queue. UI, receiver and USB never race snapshots. */
object StationRuntime {
    val worker = Executors.newSingleThreadExecutor()
    @Volatile var uiVisible = false
    @Volatile var pairingOpen = false
}
