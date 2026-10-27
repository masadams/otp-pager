package org.example.otppager

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** No index/engine/key access and no active wipe action. Safe to open while every vault is frozen. */
internal class GlobalRecoveryScreen(private val activity: Activity, private val isCurrent: () -> Boolean) {
    companion object {
        private val worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "pager-recovery-status").apply { isDaemon = true }
        }
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private fun label(value: String, sp: Float = 16f) = TextView(activity).apply {
        text = value; textSize = sp; setTextColor(Color.rgb(20, 20, 20)); typeface = Typeface.MONOSPACE
        setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, dp(8), 0, dp(8)); isSaveEnabled = false
    }
    private fun button(value: String, action: () -> Unit) = Button(activity).apply {
        text = value; textSize = 15f; isAllCaps = false; typeface = Typeface.MONOSPACE
        minHeight = dp(48); minimumHeight = dp(48); stateListAnimator = null; elevation = 0f
        setTextColor(Color.rgb(20, 20, 20)); setPadding(dp(14), dp(8), dp(14), dp(8))
        background = GradientDrawable().apply { setColor(Color.WHITE); setStroke(dp(1), Color.rgb(200, 200, 200)) }
        isSaveEnabled = false; filterTouchesWhenObscured = true; setOnClickListener { action() }
        isEnabled = false; alpha = 0.45f
    }
    private val status = label("Checking local cleanup…", 19f)
    private val retry = button("Retry local cleanup") { refresh(retryCleanup = true) }
    private val check = button("Check status") { refresh() }
    private var pending = false
    private lateinit var scroll: ScrollView
    fun show() {
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(12), dp(20), dp(20))
            addView(label("Local recovery", 25f))
            addView(status)
            addView(label("No remote wipe has been requested. Other devices may still hold their conversations."))
            addView(retry, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
            addView(check, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(label("Local cleanup removes conversation and draft keys, files and contact names. Minimal pad-reuse and retirement records remain. Flash, memory, backups and exported copies cannot be guaranteed erased.", 14f).apply { setPadding(0, dp(22), 0, dp(8)) })
            addView(label("Fresh enrollment is not available in this build. Retained security records must stay in place; clearing app data would remove local reuse protection.", 14f).apply { setPadding(0, dp(22), 0, dp(8)) })
        }
        scroll = ScrollView(activity).apply {
            setBackgroundColor(Color.rgb(250, 249, 246)); isFillViewport = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
            setPadding(0, 0, dp(8), 0); addView(body)
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(0, insets.systemWindowInsetTop, dp(8), insets.systemWindowInsetBottom); insets
            }
        }
        activity.setContentView(scroll); scroll.requestApplyInsets()
        refresh()
    }
    private fun refresh(retryCleanup: Boolean = false) {
        if (pending || !isCurrent()) return
        pending = true; retry.isEnabled = false; check.isEnabled = false; retry.alpha = 0.45f; check.alpha = 0.45f
        status.text = if (retryCleanup) "Retrying local cleanup…" else "Checking local cleanup…"
        val context = activity.applicationContext
        worker.execute {
            val observed = try {
                // Wait on a status worker, never the main thread or the messaging worker.
                // Dropping this screen does not cancel the independent destruction Future.
                if (retryCleanup) GlobalRetirement.retryExisting(context).get()
                GlobalRetirement.inspect(context)
            } catch (_: Exception) { GlobalRetirement.Status(GlobalRetirement.LocalState.UNAVAILABLE) }
            activity.runOnUiThread {
                if (!isCurrent()) return@runOnUiThread
                pending = false
                status.text = when (observed.state) {
                    GlobalRetirement.LocalState.COMPLETE -> "Local cleanup verified. All pairings remain disabled."
                    GlobalRetirement.LocalState.INCOMPLETE -> "Local cleanup is incomplete. All pairings remain disabled."
                    GlobalRetirement.LocalState.UNAVAILABLE -> "Local cleanup status is unavailable. All pairings remain disabled."
                    GlobalRetirement.LocalState.NOT_FROZEN -> "Recovery state changed. Close and reopen the app."
                }
                retry.isEnabled = observed.state != GlobalRetirement.LocalState.NOT_FROZEN && observed.state != GlobalRetirement.LocalState.COMPLETE
                retry.alpha = if (retry.isEnabled) 1f else 0.45f
                check.isEnabled = true; check.alpha = 1f
                // A retry is usually tapped after scrolling. Bring its result back into view.
                scroll.post { if (isCurrent()) scroll.scrollTo(0, 0) }
            }
        }
    }
}
