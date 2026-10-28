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

    /**
     * Authenticated terminal evidence. [boundEpoch] is [LEGACY] for `OTPTERM1`. [roots] is the
     * sealed destroyed scope and [guards] the sealed retained reuse cohort; both stay
     * authoritative for every later epoch in the chain.
     */
    data class Terminal(val generation: String, val boundEpoch: String, val commitment: String, val cleaned: Boolean,
                        val roots: Set<String> = emptySet(), val guards: List<RetirementEvidenceCodec.Guard> = emptyList())

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
        /** [chain] lists every terminal from legacy onward; their retained cohorts all stay enforced. */
        data class Active(val manifest: ActiveManifestCodec.Record, val chain: List<Terminal> = emptyList()) : State
        data class Frozen(val reason: String) : State
    }

    private class Stop(val reason: String) : Exception(reason)
    private fun freeze(reason: String): Nothing = throw Stop(reason)

    /**
     * [lastEpoch] is the most recently linked epoch, even when a later wipe of it froze the
     * lineage; [chain] lists the terminals linked so far. Wipe recovery uses these to scope
     * destruction; access decisions use only [state].
     */
    data class Detail(val state: State, val lastEpoch: ActiveManifestCodec.Record?, val chain: List<Terminal>)
    private class Progress { var current: ActiveManifestCodec.Record? = null; val chain = mutableListOf<Terminal>() }

    fun resolve(o: Observation): State = detail(o).state

    fun detail(o: Observation): Detail {
        val progress = Progress()
        val state = try { walk(o, progress) } catch (stop: Stop) { State.Frozen(stop.reason) }
        return Detail(state, progress.current, progress.chain.toList())
    }

    private fun walk(o: Observation, progress: Progress): State {
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
        val chain = progress.chain
        while (true) {
            val bound = o.terminals.filter { it.boundEpoch == predecessor }
            if (bound.isEmpty()) {
                if (predecessor == LEGACY && o.legacyJournal) freeze("legacy-journal-without-evidence")
                break
            }
            if (bound.size > 1) freeze("ambiguous-terminal")
            val terminal = bound.single(); usedTerminals.add(terminal.generation); chain.add(terminal)
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
            // A root destroyed by an EARLIER wipe is never re-listed, even if its files were removed.
            // (This epoch's own later wipe naturally lists its roots; that is not reuse.)
            if (manifest.roots.any { root -> chain.any { root in it.roots } }) freeze("root-reuse")
            progress.current = manifest; predecessor = manifest.epoch
        }
        if (o.terminals.any { it.generation !in usedTerminals } || o.stages.any { it.epoch !in usedStages } ||
            o.manifests.any { it.epoch !in usedManifests }) freeze("unexplained-record")
        return progress.current?.let { State.Active(it, chain.toList()) } ?: State.Legacy
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
