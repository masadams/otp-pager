package org.example.otppager

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.SystemClock

/**
 * One screen-lock confirmation per visit. Moving between the app's own screens, the QR scanner
 * or a quick file picker keeps it unlocked; the app relocks once none of its screens have been
 * visible for [GRACE_MS], and always after the process restarts. Main thread only.
 */
internal object AppLock : Application.ActivityLifecycleCallbacks {
    const val GRACE_MS = 60_000L
    private var started = 0
    private var hiddenAt = 0L
    var unlocked = false
        private set
    /** Changes on every relock so a screen auto-prompts once per locked period, not in a loop. */
    var epoch = 0
        private set
    fun unlock() { unlocked = true }
    fun lock() { if (unlocked) epoch++; unlocked = false }
    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0 && hiddenAt != 0L && SystemClock.elapsedRealtime() - hiddenAt >= GRACE_MS) lock()
        hiddenAt = 0L
    }
    override fun onActivityStopped(activity: Activity) {
        if (started > 0 && --started == 0) hiddenAt = SystemClock.elapsedRealtime()
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
