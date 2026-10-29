package org.example.otppager

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Intent
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
    /**
     * Every screen except the main one calls this first in onResume. While locked it closes the
     * screen and hands over to the main screen's unlock prompt, so nothing private renders.
     */
    fun gate(activity: Activity): Boolean {
        if (unlocked || activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure != true) return true
        activity.startActivity(Intent(activity, NetworkActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        activity.finish()
        return false
    }
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
