package dev.friday.assistant.inbox

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.Notification
import dev.friday.assistant.R
import dev.friday.assistant.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Calendar

/** Arms the evening alarm that produces the day's summary. */
object DigestScheduler {
    fun schedule(context: Context) {
        val settings = Settings(context)
        val at = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, settings.digestHour); set(Calendar.MINUTE, settings.digestMinute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        // Not an "exact" alarm: those need a special permission, and a summary a few minutes late costs nothing.
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.timeInMillis, pending(context))
    }

    private fun pending(context: Context) = PendingIntent.getBroadcast(
        context, 77, Intent(context, DigestReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Runs when the evening alarm fires (and re-arms it after the phone restarts). */
class DigestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            DigestScheduler.schedule(app)
            return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // A receiver gets only a few seconds, so if the model is slow the plain summary is used.
                val text = withTimeoutOrNull(9_000) { Inbox.digest(app, english = true) } ?: Inbox.localDigest(app)
                post(app, text)
            } finally {
                DigestScheduler.schedule(app)
                pending.finish()
            }
        }
    }

    private fun post(context: Context, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("friday_digest", "Din ka summary", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 78, Intent(context, InboxActivity::class.java).putExtra(InboxActivity.EXTRA_SPEAK, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            78,
            Notification.Builder(context, "friday_digest")
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Aaj ka summary")
                .setContentText(text.take(90))
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}
