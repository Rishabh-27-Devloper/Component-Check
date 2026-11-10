package com.componentcheck.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.componentcheck.R
import com.componentcheck.ui.history.HistoryActivity
import com.componentcheck.ui.main.MainActivity

object NotificationHelper {

    const val CHANNEL_ID = "instagram_monitor_alerts"
    private const val CHANNEL_NAME = "Instagram Profile Changes"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High priority notifications when monitored Instagram profiles change"
                enableLights(true)
                lightColor = Color.MAGENTA
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 150, 250)
                setShowBadge(true)
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun showProfileChangeAlert(
        context: Context,
        username: String,
        changesSummary: List<String>
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, HistoryActivity::class.java).apply {
            putExtra("EXTRA_USERNAME", username)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            username.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "Instagram Alert: @$username"
        val body = changesSummary.joinToString("\n")

        val bigTextStyle = NotificationCompat.BigTextStyle()
            .setBigContentTitle(title)
            .setSummaryText("Profile Change Detected")
            .bigText(body)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_instagram)
            .setContentTitle(title)
            .setContentText(changesSummary.firstOrNull() ?: "Profile updated")
            .setStyle(bigTextStyle)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        manager.notify(username.hashCode(), notification)
    }
}
