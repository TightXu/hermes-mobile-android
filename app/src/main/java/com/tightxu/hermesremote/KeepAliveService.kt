package com.tightxu.hermesremote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Short-lived foreground keep-alive.
 *
 * When the chat goes to the background (screen lock, app switch), Android may
 * freeze or kill the process and its WebSocket. This service raises the
 * process priority and holds a partial wake lock for at most 10 minutes, so a
 * short lock/unlock usually keeps the PTY socket alive. It is NOT a permanent
 * background connection: the chat screen starts it on ON_STOP, stops it on
 * ON_START/leave, and the app still reconnects on resume if the OEM killed it.
 */
class KeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private val stopRunnable = Runnable { stopSelf() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notify_keepalive_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HermesRemote:chat")
                .apply { acquire(MAX_MS) }
        }
        handler.removeCallbacks(stopRunnable)
        handler.postDelayed(stopRunnable, MAX_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopRunnable)
        runCatching { wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notify_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { setShowBadge(false) }
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "hermes_remote_keepalive"
        private const val NOTIFICATION_ID = 41
        private const val MAX_MS = 10 * 60 * 1000L

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, KeepAliveService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        }
    }
}
