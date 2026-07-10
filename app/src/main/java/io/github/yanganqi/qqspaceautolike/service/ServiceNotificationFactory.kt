package io.github.yanganqi.qqspaceautolike.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.yanganqi.qqspaceautolike.R
import io.github.yanganqi.qqspaceautolike.ui.MainActivity

class ServiceNotificationFactory(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)
    private var lastText: String? = null

    init {
        createChannel()
    }

    fun showRunning(text: String) {
        if (text == lastText) return
        lastText = text

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_running_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openMainActivityIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                context.getString(R.string.notification_action_stop),
                stopIntent(),
            )
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // On Android 13+, the user may deny the notification permission.
        }
    }

    fun cancel() {
        lastText = null
        manager.cancel(NOTIFICATION_ID)
    }

    private fun stopIntent(): PendingIntent {
        val intent = Intent(context, ServiceCommandReceiver::class.java).apply {
            action = ServiceCommandReceiver.ACTION_STOP
        }
        return PendingIntent.getBroadcast(
            context,
            2002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun openMainActivityIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            2001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "qq_auto_like_runtime"
        private const val NOTIFICATION_ID = 1001
    }
}

