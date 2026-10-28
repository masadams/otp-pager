package org.example.otppager

import java.io.File

/**
 * Captured once at owner construction and inherited by siblings, identities and leases.
 * Old owners never rebind to "whatever is current". ACTIVE is only possible once an
 * authenticated `OTPACT01` manifest resolves through [EpochLineage]; no code path in this
 * build writes one (see docs/ACTIVE_EPOCH_DESIGN.md, slice B3e).
 */
internal object VaultGeneration {
    sealed interface Capability {
        data object Legacy : Capability
        data object Blocked : Capability
        data class Active(val epoch: String) : Capability
    }

    fun capture(directory: File): Capability = synchronized(VaultLifecycle.lock) {
        if (EnrollmentStaging.presence(directory) == EnrollmentStaging.Presence.NONE) Capability.Legacy
        else EpochState.active(directory)?.let { Capability.Active(it.epoch) } ?: Capability.Blocked
    }

    fun requireCurrent(directory: File, captured: Capability, namespace: String) = synchronized(VaultLifecycle.lock) {
        val stagingAbsent = EnrollmentStaging.presence(directory) == EnrollmentStaging.Presence.NONE
        check(allows(captured, stagingAbsent, { EpochState.resolveIfManifest(directory) }, namespace)) {
            "Storage generation changed; this owner cannot be reused"
        }
    }

    /**
     * Pure decision. Legacy owners need no staging at all (unchanged from before ACTIVE
     * existed). Active owners need the same epoch still resolved ACTIVE and the namespace
     * inside that epoch's allowlist. [state] is evaluated only for Active owners.
     */
    internal fun allows(captured: Capability, stagingAbsent: Boolean, state: () -> EpochLineage.State, namespace: String): Boolean =
        when (captured) {
            Capability.Legacy -> stagingAbsent
            Capability.Blocked -> false
            is Capability.Active -> !stagingAbsent && (state() as? EpochLineage.State.Active)?.manifest
                ?.let { it.epoch == captured.epoch && EpochScope.permits(it, namespace) } == true
        }
}
