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
    internal fun recordGlobal(result: GlobalRetirementMachine.Result) {
        global = result
        incomplete = if (result.completed) 0 else 1
        failed = false // Global access remains terminal even when retry succeeds.
    }
    fun recover(context: Context) {
        failed = true
        try {
            if (GlobalRetirement.isFrozen(context.noBackupFilesDir)) {
                recordGlobal(GlobalRetirement.recover(context))
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
