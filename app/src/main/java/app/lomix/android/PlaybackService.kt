package app.lomix.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground service that OWNS the [PlayerController] (v1.1).
 *
 * The Activity binds to this service to reach the controller; it never
 * releases it. Closing or swiping away the Activity therefore keeps the
 * music playing, the notification offers Play/Pause and Stop, and the
 * Stop action is what finally tears everything down.
 */
class PlaybackService : Service() {

    lateinit var controller: PlayerController
        private set

    inner class LocalBinder : Binder() {
        fun service(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        createChannel()
        controller = PlayerController(this)
        controller.onStateChanged = { updateNotification() }
        startForegroundWith(buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> controller.togglePlayPause()
            ACTION_STOP -> {
                controller.pauseAll()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.release()
        super.onDestroy()
    }

    private fun startForegroundWith(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val hasSong = ::controller.isInitialized && controller.hasActiveSource
        val playing = ::controller.isInitialized && controller.wantsToPlay

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(
                if (hasSong) controller.activeTitle else "Lomix"
            )
            .setContentText(
                if (hasSong) controller.mixSummary() else "Gana chunein aur mix banayein"
            )
            .setContentIntent(openIntent)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (hasSong) {
            builder.addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play",
                toggleIntent
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
        }
        return builder.build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "app.lomix.android.action.TOGGLE"
        const val ACTION_STOP = "app.lomix.android.action.STOP"
        const val ACTION_REFRESH = "app.lomix.android.action.REFRESH"
        private const val CHANNEL_ID = "lomix_playback"
        private const val NOTIFICATION_ID = 1001
    }
}
