package com.atelier.manim.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.atelier.manim.AtelierApp
import com.atelier.manim.MainActivity
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class RenderForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val action = intent?.action
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Atelier"
        val detail = intent?.getStringExtra(EXTRA_DETAIL) ?: "Running task..."
        val progress = intent?.getFloatExtra(EXTRA_PROGRESS, -1f) ?: -1f
        val notif = buildNotification(title, detail, progress)
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0

        if (action == ACTION_CANCEL) {
            isActive.set(false)
            runCatching { AtelierApp.instance.coordinator.cancelActiveTask() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_STOP) {
            if (!isActive.get()) runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notif, fgsType) }
            isActive.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val ok = runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notif, fgsType); true }.getOrDefault(false)
        isActive.set(ok)
        if (!ok) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isActive.set(false)
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Atelier Rendering & Execution", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Shows progress during Manim, LaTeX, and Python execution."
                        setShowBadge(false)
                    }
                )
            }
        }
    }

    private fun buildNotification(title: String, detail: String, progress: Float): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this, 1, Intent(this, RenderForegroundService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setContentText(detail).setOngoing(true).setSilent(true)
            .setContentIntent(openIntent).addAction(0, "Cancel", cancelIntent)
        if (progress in 0f..1f) b.setProgress(100, (progress * 100f).roundToInt(), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    companion object {
        private const val CHANNEL_ID = "atelier_render_channel"
        private const val NOTIFICATION_ID = 4102
        private const val ACTION_START = "com.atelier.manim.action.START"
        private const val ACTION_STOP = "com.atelier.manim.action.STOP"
        private const val ACTION_CANCEL = "com.atelier.manim.action.CANCEL"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_DETAIL = "detail"
        private const val EXTRA_PROGRESS = "progress"
        private val isActive = AtomicBoolean(false)

        fun update(context: Context, title: String, detail: String, progress: Float = -1f) {
            val intent = Intent(context, RenderForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title); putExtra(EXTRA_DETAIL, detail); putExtra(EXTRA_PROGRESS, progress)
            }
            val ok = runCatching {
                if (isActive.get()) context.startService(intent) else ContextCompat.startForegroundService(context, intent)
                true
            }.getOrDefault(false)
            if (ok) isActive.set(true)
        }

        fun stop(context: Context) {
            if (!isActive.getAndSet(false)) return
            runCatching { context.stopService(Intent(context, RenderForegroundService::class.java)) }
        }
    }
}
