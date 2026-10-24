package org.example.otppager

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

/** User-started text-message reception. No boot receiver, content previews or Google push. */
class ReceiveService : Service() {
    companion object {
        const val STOP = "org.example.otppager.STOP_RECEIVING"
        private const val CHANNEL = "receiving"
        private const val MESSAGES = "private-messages"
        @Volatile var running = false
        @Volatile var lastCheck = 0L
        @Volatile var lastProblem = ""
        fun enabled(context: Context) = context.getSharedPreferences("receiving", MODE_PRIVATE).getBoolean("enabled", false)
        fun enabled(context: Context, value: Boolean) { check(context.getSharedPreferences("receiving", MODE_PRIVATE).edit().putBoolean("enabled", value).commit()) }
        fun start(context: Context) {
            enabled(context, true)
            try { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, ReceiveService::class.java)) }
            catch (e: Exception) { enabled(context, false); throw e }
        }
        fun stop(context: Context) { enabled(context, false); context.stopService(Intent(context, ReceiveService::class.java)) }
    }
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    private val cycle = ReceiveCycle()
    private var delay = 30000L
    private lateinit var manager: NotificationManager
    private fun open(): PendingIntent = PendingIntent.getActivity(this, 301, Intent(this, NetworkActivity::class.java).putExtra("openUnread", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun notice(detail: String): Notification {
        val stop = PendingIntent.getService(this, 302, Intent(this, ReceiveService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_chat).setContentTitle("OTP Pager · receiving")
            .setContentText(detail).setContentIntent(open()).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_SECRET).addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }
    override fun onCreate() {
        super.onCreate(); manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Background receiving", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(MESSAGES, "New private messages", NotificationManager.IMPORTANCE_DEFAULT).apply { lockscreenVisibility = Notification.VISIBILITY_SECRET })
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { enabled(this, false); stopSelf(); return START_NOT_STICKY }
        if (!enabled(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            ServiceCompat.startForeground(this, 301, notice("Checks continue when you close the app"), if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0)
        } catch (_: Exception) { enabled(this, false); stopSelf(); return START_NOT_STICKY }
        if (!running) { running = true; stopped = false; handler.post(poll) }
        return START_STICKY
    }
    private val poll = object : Runnable {
        override fun run() {
            if (stopped || !enabled(this@ReceiveService)) return
            StationRuntime.worker.execute {
                var text = "Receiving · checks may pause during deep sleep"
                try {
                    if (!stopped && !StationRuntime.uiVisible && !StationRuntime.pairingOpen) {
                        val lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "otp-pager:receive")
                        lock.acquire(60000)
                        try {
                            val result = cycle.poll(ChatBook(this@ReceiveService), allowed = { !stopped && !StationRuntime.uiVisible && !StationRuntime.pairingOpen })
                            val total = result.unread; val failed = result.failed; val attempted = result.attempted
                            if (result.newMessages && !StationRuntime.uiVisible && (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(this@ReceiveService).areNotificationsEnabled()) {
                                manager.notify(303, Notification.Builder(this@ReceiveService, MESSAGES).setSmallIcon(android.R.drawable.stat_notify_chat)
                                    .setContentTitle("New private message").setContentText("Open OTP Pager to read your messages")
                                    .setContentIntent(open()).setAutoCancel(true).setVisibility(Notification.VISIBILITY_SECRET).build())
                            }
                            if (failed == 0 && attempted > 0) { lastCheck = SystemClock.elapsedRealtime(); lastProblem = ""; delay = 30000L }
                            else if (failed > 0) { lastProblem = "Some chats could not connect"; delay = (delay * 2).coerceAtMost(120000L) }
                            text = if (failed > 0) "Connection needs attention · saved messages retained" else if (total > 0) "$total unread · open to read" else "Receiving · no new messages"
                        } finally { if (lock.isHeld) lock.release() }
                    } else text = if (StationRuntime.pairingOpen) "Paused during USB pairing" else "App open · foreground checks active"
                } catch (_: Exception) { lastProblem = "Receiver needs attention"; delay = 120000L; text = lastProblem }
                handler.post {
                    if (!stopped) {
                        try { manager.notify(301, notice(text)) } catch (_: SecurityException) {}
                        handler.postDelayed(this, delay)
                    }
                }
            }
        }
    }
    override fun onDestroy() { stopped = true; running = false; handler.removeCallbacksAndMessages(null); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy() }
    override fun onBind(intent: Intent?) = null
}
