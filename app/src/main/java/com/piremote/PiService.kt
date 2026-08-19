package com.piremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

private const val CHANNEL_ID = "pi_remote_connection"
private const val NOTIFICATION_ID = 1001

// Separate channel for one-shot "pi finished a turn" pings. Keeps these out of
// the low-importance always-on connection notification so the user actually
// hears/sees them when the app is in the background.
private const val DONE_CHANNEL_ID = "pi_remote_done"
private const val DONE_NOTIFICATION_ID = 1002

/**
 * Foreground service that keeps the Pi WebSocket connection alive while the
 * app is in the background. Shows an ongoing "Connected to Pi" notification.
 */
class PiService : Service() {

    private val notificationMgr by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        val serverHost = intent?.getStringExtra(EXTRA_HOST) ?: "Pi"
        val busy = intent?.getBooleanExtra(EXTRA_BUSY, false) == true
        val msgCount = intent?.getIntExtra(EXTRA_MSG_COUNT, 0) ?: 0

        val notification = buildNotification(serverHost, busy, msgCount)
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Android 14+ FGS quotas (e.g. dataSync 6h/24h) can deny startForeground.
            // Mark quota as exhausted so the caller stops retrying, preventing
            // a start-fail-destroy loop that burns CPU while the WS stays alive.
            Log.w("PiService", "startForeground denied: ${e.message}")
            markQuotaFailed()
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(serverHost: String, busy: Boolean, msgCount: Int): Notification {
        val openAppIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val subText = when {
            busy -> "Thinking..."
            msgCount > 0 -> "$msgCount messages"
            else -> "Connected"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Pi Remote — Connected")
            .setContentText("Connected to $serverHost")
            .setSubText(subText)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Pi Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows active Pi agent connection"
        }
        notificationMgr.createNotificationChannel(channel)
        // Also register the done-channel here so the first notifyDone() call
        // (which can happen before the service starts in some flows) succeeds.
        ensureDoneChannel(this, notificationMgr)
    }

    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_BUSY = "busy"
        private const val EXTRA_MSG_COUNT = "msg_count"

        // When startForeground throws (Android 14+ FGS quota exhaustion),
        // we stop trying for the rest of this connection. Without this, the
        // busy-flow collector in ChatViewModel keeps calling start() →
        // startForeground throws → stopSelf → repeat, burning CPU.
        @Volatile private var quotaFailed = false

        // Whether the service is currently up. Android 12+ forbids *starting* a
        // foreground service from the background, but plain startService() to an
        // already-running one is fine — so notification updates that land after
        // the app is backgrounded use that path instead.
        @Volatile private var running = false

        /** Start or update the foreground service. */
        fun start(context: Context, serverHost: String, busy: Boolean = false, msgCount: Int = 0) {
            if (quotaFailed) return  // don't retry after quota exhaustion
            val intent = Intent(context, PiService::class.java).apply {
                putExtra(EXTRA_HOST, serverHost)
                putExtra(EXTRA_BUSY, busy)
                putExtra(EXTRA_MSG_COUNT, msgCount)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !running) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // A caller-side denial (most likely ForegroundServiceStartNotAllowed
                // — we raced the app going to the background) is transient, so this
                // deliberately does NOT latch quotaFailed: the next foreground
                // transition should get to try again. Only startForeground()
                // throwing inside onStartCommand means the quota is actually gone,
                // and that latches via markQuotaFailed(). Callers here are
                // transition-driven, not a retry loop, so there's nothing to spin.
                Log.w("PiService", "start denied: ${e.message}")
            }
        }

        /** Called by the service when startForeground throws inside onStartCommand. */
        internal fun markQuotaFailed() { quotaFailed = true }

        /** Stop the foreground service. Resets the quota flag so the next
         *  connect cycle gets a fresh try (the 24h quota window may have moved). */
        fun stop(context: Context) {
            quotaFailed = false
            running = false
            context.stopService(Intent(context, PiService::class.java))
        }

        /**
         * Post a one-shot "pi finished" heads-up notification. Caller decides
         * when this is appropriate (e.g. only when the app is backgrounded);
         * this method just builds and posts.
         */
        fun notifyDone(
            context: Context,
            host: String,
            summary: String?,
            durationMs: Long
        ) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureDoneChannel(context, nm)

            val openAppIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val seconds = (durationMs / 1000L).coerceAtLeast(0L)
            val body = buildString {
                if (!summary.isNullOrBlank()) append(summary).append(" · ")
                append("${seconds}s")
            }

            val notification = NotificationCompat.Builder(context, DONE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Pi is ready")
                .setContentText(body)
                .setSubText(host)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setContentIntent(pendingIntent)
                .build()

            try { nm.notify(DONE_NOTIFICATION_ID, notification) } catch (_: Exception) { /* perm denied / channel issue */ }
        }

        private fun ensureDoneChannel(context: Context, nm: NotificationManager) {
            if (nm.getNotificationChannel(DONE_CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                DONE_CHANNEL_ID,
                "Pi Ready",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Pings when pi finishes a turn while the app is backgrounded"
            }
            nm.createNotificationChannel(channel)
        }
    }
}
