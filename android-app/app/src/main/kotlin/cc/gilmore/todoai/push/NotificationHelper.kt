package cc.gilmore.todoai.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import cc.gilmore.todoai.MainActivity
import cc.gilmore.todoai.R

object NotificationHelper {
    const val CHANNEL_ID = "todo_reminders"
    const val EXTRA_TODO_ID = "todo_id"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.reminder_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    fun showReminder(context: Context, todoId: String?, title: String, body: String?) {
        ensureChannel(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (todoId != null) putExtra(EXTRA_TODO_ID, todoId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            todoId?.hashCode() ?: 0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder) // placeholder glyph — see res/drawable/ic_launcher_foreground.xml's note on real icon art
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val notificationId = todoId?.hashCode() ?: System.currentTimeMillis().toInt()
        androidx.core.app.NotificationManagerCompat.from(context).apply {
            // POST_NOTIFICATIONS is requested at runtime from MainActivity;
            // if it was denied, this call is a documented no-op on API 33+
            // rather than a crash, which is what we want here.
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                notify(notificationId, notification)
            }
        }
    }
}
