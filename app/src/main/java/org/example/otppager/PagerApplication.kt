package org.example.otppager

import android.app.Application
import android.content.Context

internal object RetirementStartup {
    @Volatile var failed = false
        private set
    @Volatile var incomplete = 0
        private set
    @Volatile var global: GlobalRetirementMachine.Result? = null
        private set
    internal fun recordGlobal(context: Context, result: GlobalRetirementMachine.Result) {
        global = result
        incomplete = if (result.completed) 0 else 1
        failed = false // Global access remains terminal even when retry succeeds.
        try { RetirementEvidence.recordCleanup(context, result) } catch (_: Exception) { /* No repair or retrospective capture. */ }
    }
    fun recover(context: Context) {
        failed = true
        try {
            val presence = RetirementEvidence.probe(context.noBackupFilesDir)
            check(presence != RetirementEvidence.Presence.UNAVAILABLE || GlobalRetirement.hasLegacyIntent(context.noBackupFilesDir)) {
                "Cannot establish terminal intent; block access without initiating destruction"
            }
            if (GlobalRetirement.isFrozen(context.noBackupFilesDir)) {
                recordGlobal(context, GlobalRetirement.recover(context))
            } else {
                global = null
                incomplete = VaultRetirement.recoverPending(context).count { !it.completed }
            }
            failed = false // Individual retired contacts stay blocked; unrelated contacts can operate.
        } catch (_: Exception) { /* Unreadable journal directory: leave all operational access disabled. */ }
    }
}

/** Android calls this before any activity, NFC service or receiving service in this process. */
class PagerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RetirementStartup.recover(this)
    }
}
