package org.example.otppager

import android.content.Context

/**
 * Explicit, local-only fresh start after a verified global wipe (slice B3e).
 *
 * Order, all under the lifecycle lock: verify the newest terminal is CLEANED with its full
 * format checks; verify every retained cohort in the lineage still authenticates; reuse a
 * verified un-activated PREPARED attempt or stage a new empty one; commit the `OTPACT01`
 * manifest (sequence 1, initial root only) as the **last** durable step; require the lineage
 * to resolve ACTIVE for exactly that epoch; only then release the in-process freeze latch.
 * Interruption before the manifest leaves a PREPARED attempt (still frozen, retryable).
 * No relay packet or peer can trigger this; it needs a fresh in-process [Token].
 */
internal object FreshEnrollment {
    class Token internal constructor(internal val directory: String, internal val generation: String, internal val commitment: String)
    private val tokens = mutableMapOf<String, Token>()

    /** Eligible only when the lineage is awaiting enrollment after a verified, sealed cleanup. */
    fun eligible(context: Context): Boolean = try { synchronized(VaultLifecycle.lock) {
        EnrollmentStaging.latestCompleted(context); verifyCohorts(context); true
    } } catch (_: Exception) { false }

    fun confirm(context: Context): Token = synchronized(VaultLifecycle.lock) {
        val latest = EnrollmentStaging.latestCompleted(context)
        Token(context.noBackupFilesDir.absolutePath, latest.generation, latest.commitment).also { tokens[it.directory] = it }
    }

    fun cancel(token: Token) = synchronized(VaultLifecycle.lock) { if (tokens[token.directory] === token) tokens.remove(token.directory); Unit }

    fun enroll(context: Context, token: Token): ActiveManifestCodec.Record = synchronized(VaultLifecycle.lock) {
        val directory = context.noBackupFilesDir.absoluteFile
        check(token.directory == directory.path && tokens[directory.path] === token) { "Fresh local confirmation required" }
        tokens.remove(directory.path) // One use, even if a later step fails.
        val latest = EnrollmentStaging.latestCompleted(context)
        check(latest.generation == token.generation && latest.commitment == token.commitment) { "Wipe evidence changed" }
        verifyCohorts(context)
        val pending = EnrollmentStaging.inspect(context).singleOrNull {
            it.phase == EnrollmentStageCodec.Phase.PREPARED && it.terminalGeneration == latest.generation
        }
        val stage = pending ?: EnrollmentStaging.prepare(context, EnrollmentStaging.confirmation(context))
        check(stage.phase == EnrollmentStageCodec.Phase.PREPARED && stage.terminalGeneration == latest.generation)
        check(EnrollmentStaging.inspect(context).any { it == stage }) // Staged artifacts re-verified.
        verifyCohorts(context)
        val manifest = ActiveManifestCodec.record(stage.epoch, latest.generation, latest.commitment, stage.indexHash,
            stage.claimsHash, 1, listOf(stage.root))
        EpochManifests.create(directory, manifest) // Activation commit: the last durable write.
        EpochState.invalidate(directory)
        val state = EpochState.resolve(directory)
        check(state is EpochLineage.State.Active && state.manifest == manifest) { "Activation did not resolve" }
        GlobalRetirement.reopenAfterActivation(directory)
        RetirementStartup.clearGlobal()
        manifest
    }

    /** Every terminal's sealed cohort, read by commitment through the lineage only. */
    private fun verifyCohorts(context: Context) {
        val directory = context.noBackupFilesDir.absoluteFile
        val chain = EpochState.detail(directory).chain
        check(chain.isNotEmpty())
        RetainedGuardReader.verifyChain(directory, chain)
    }
}
