package org.example.otppager

import java.io.File

/** Captured at owner construction. No capability in this build can activate staged storage. */
internal object VaultGeneration {
    enum class Capability { LEGACY, BLOCKED }
    fun capture(directory: File): Capability = synchronized(VaultLifecycle.lock) {
        if (EnrollmentStaging.presence(directory) == EnrollmentStaging.Presence.NONE) Capability.LEGACY else Capability.BLOCKED
    }
    fun requireCurrent(directory: File, captured: Capability) = synchronized(VaultLifecycle.lock) {
        check(captured == Capability.LEGACY && EnrollmentStaging.presence(directory) == EnrollmentStaging.Presence.NONE) {
            "Storage generation changed; this owner cannot be reused"
        }
    }
}
