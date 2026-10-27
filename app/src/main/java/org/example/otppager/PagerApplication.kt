package org.example.otppager

import android.app.Application
import android.content.Context

internal object RetirementStartup {
    @Volatile var failed = false
        private set
    @Volatile var incomplete = 0
        private set
    fun recover(context: Context) {
        failed = true
        try {
            incomplete = VaultRetirement.recoverPending(context).count { !it.completed }
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
