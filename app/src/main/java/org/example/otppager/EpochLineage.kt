package org.example.otppager

/**
 * Pure lineage resolution (design: docs/ACTIVE_EPOCH_DESIGN.md). Given authenticated,
 * already-decoded observations, decides Legacy, Active(epoch) or Frozen. No head pointer,
 * no "latest wins": every present record must belong to one consistent chain from legacy,
 * otherwise the result is Frozen. Adapters that read real files/keys come in a later slice;
 * nothing in this build routes storage access through this result yet.
 */
internal object EpochLineage {
    const val LEGACY = "legacy"
    private val epochPattern = Regex("[a-f0-9]{32}")

    /** Authenticated terminal evidence. [boundEpoch] is [LEGACY] for `OTPTERM1`. */
    data class Terminal(val generation: String, val boundEpoch: String, val commitment: String, val cleaned: Boolean)

    data class Observation(
        /** Any unreadable, unauthenticated, variant or unknown lifecycle artifact. */
        val unreadable: Boolean = false,
        /** The v1 `global-retirement-v1.json` journal (or a variant) is present. */
        val legacyJournal: Boolean = false,
        val terminals: List<Terminal> = emptyList(),
        val stages: List<EnrollmentStageCodec.Record> = emptyList(),
        val manifests: List<ActiveManifestCodec.Record> = emptyList(),
    )

    sealed interface State {
        data object Legacy : State
        data class Active(val manifest: ActiveManifestCodec.Record) : State
        data class Frozen(val reason: String) : State
    }

    private class Stop(val reason: String) : Exception(reason)
    private fun freeze(reason: String): Nothing = throw Stop(reason)

    fun resolve(o: Observation): State = try { walk(o) } catch (stop: Stop) { State.Frozen(stop.reason) }

    private fun walk(o: Observation): State {
        if (o.unreadable) freeze("unreadable")
        if (o.terminals.isEmpty() && o.stages.isEmpty() && o.manifests.isEmpty())
            return if (o.legacyJournal) freeze("legacy-journal-without-evidence") else State.Legacy
        val limit = EnrollmentStageCodec.MAX_ATTEMPTS
        if (o.stages.size > limit || o.manifests.size > limit || o.terminals.size > limit + 1) freeze("capacity")
        if (o.terminals.map { it.generation }.toSet().size != o.terminals.size) freeze("duplicate-terminal")
        if (o.stages.map { it.epoch }.toSet().size != o.stages.size) freeze("duplicate-stage")
        if (o.manifests.map { it.epoch }.toSet().size != o.manifests.size) freeze("duplicate-manifest")
        if (o.terminals.any { it.boundEpoch != LEGACY && !epochPattern.matches(it.boundEpoch) }) freeze("invalid-terminal")

        val usedTerminals = mutableSetOf<String>(); val usedStages = mutableSetOf<String>(); val usedManifests = mutableSetOf<String>()
        var predecessor = LEGACY
        var current: ActiveManifestCodec.Record? = null
        while (true) {
            val bound = o.terminals.filter { it.boundEpoch == predecessor }
            if (bound.isEmpty()) {
                if (predecessor == LEGACY && o.legacyJournal) freeze("legacy-journal-without-evidence")
                break
            }
            if (bound.size > 1) freeze("ambiguous-terminal")
            val terminal = bound.single(); usedTerminals.add(terminal.generation)
            if (!terminal.cleaned) freeze("cleanup-incomplete")

            val stages = o.stages.filter { it.terminalGeneration == terminal.generation }
            stages.forEach { usedStages.add(it.epoch) }
            if (stages.any { it.terminalCommitment != terminal.commitment }) freeze("stage-commitment")

            val manifests = o.manifests.filter { it.predecessorGeneration == terminal.generation }
            if (manifests.isEmpty()) freeze("awaiting-enrollment")
            if (manifests.size > 1) freeze("ambiguous-manifest")
            val manifest = manifests.single(); usedManifests.add(manifest.epoch)
            if (manifest.predecessorCommitment != terminal.commitment) freeze("manifest-commitment")
            val stage = stages.singleOrNull { it.epoch == manifest.epoch } ?: freeze("manifest-without-stage")
            if (stage.phase != EnrollmentStageCodec.Phase.PREPARED || stage.indexHash != manifest.indexHash ||
                stage.claimsHash != manifest.claimsHash) freeze("manifest-stage-mismatch")
            if (stages.any { it.epoch != manifest.epoch && it.phase != EnrollmentStageCodec.Phase.ABANDONED }) freeze("open-stage")
            current = manifest; predecessor = manifest.epoch
        }
        if (o.terminals.any { it.generation !in usedTerminals } || o.stages.any { it.epoch !in usedStages } ||
            o.manifests.any { it.epoch !in usedManifests }) freeze("unexplained-record")
        return current?.let { State.Active(it) } ?: State.Legacy
    }
}

/** Namespace allowlist for an ACTIVE epoch. Legacy and other epochs' namespaces never match. */
internal object EpochScope {
    private val contact = Regex("(chat-[a-f0-9]{20})(?:-draft|-pads)?")
    fun permits(manifest: ActiveManifestCodec.Record, namespace: String): Boolean {
        if (namespace == manifest.index || namespace == manifest.claims) return true
        val root = contact.matchEntire(namespace)?.groupValues?.get(1) ?: return false
        return manifest.roots.binarySearch(root) >= 0
    }
}
