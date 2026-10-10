package com.iway1231.novoyavorivskplus

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.gson.JsonObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class OutageNotificationReceiver : BroadcastReceiver() {
    companion object {
        fun showTest(context: Context) {
            val intent = Intent(context, OutageNotificationReceiver::class.java)
                .putExtra("minutes_before", 10)
                .putExtra("address", "Тестова адреса")
                .putExtra("start", "тест")
                .putExtra("notification_id", 900001)
            context.sendBroadcast(intent)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val minutes = intent.getIntExtra("minutes_before", 0)
        val address = intent.getStringExtra("address").orEmpty()
        val start = intent.getStringExtra("start").orEmpty()
        val channelId = "outage_notifications"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Відключення світла", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val title = "Наближається відключення світла"
        val text = if (address.isBlank()) {
            "Через $minutes хв — початок о $start"
        } else {
            "$address: через $minutes хв — початок о $start"
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, channelId)
        } else {
            Notification.Builder(context)
        }
        manager.notify(intent.getIntExtra("notification_id", 0), builder
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .build())
    }
}

object OutageNotificationScheduler {
    private const val PREF_IDS = "scheduled_outage_notification_ids"
    private val leads = listOf(30, 20, 10)

    fun schedule(context: Context, section: JsonObject?, address: String) {
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val oldIds = preferences.getStringSet(PREF_IDS, emptySet()).orEmpty()
        oldIds.forEach { idText ->
            val id = idText.toIntOrNull() ?: return@forEach
            val oldIntent = PendingIntent.getBroadcast(
                context, id, Intent(context, OutageNotificationReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (oldIntent != null) alarm.cancel(oldIntent)
        }
        val newIds = mutableSetOf<String>()
        val dateText = section?.get("date")?.takeUnless { it.isJsonNull }?.asString ?: return clearIds(preferences)
        val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: return clearIds(preferences)
        val intervals = section.get("intervals")?.takeIf { it.isJsonArray }?.asJsonArray ?: return clearIds(preferences)
        val now = LocalDateTime.now()
        intervals.forEachIndexed { index, element ->
            if (!element.isJsonObject) return@forEachIndexed
            val item = element.asJsonObject
            val status = item.get("status")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            if (status.equals("ON", ignoreCase = true)) return@forEachIndexed
            val fromText = item.get("from_time")?.takeUnless { it.isJsonNull }?.asString ?: return@forEachIndexed
            val parts = fromText.split(":")
            val hour = parts.getOrNull(0)?.toIntOrNull() ?: return@forEachIndexed
            val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val outageStart = date.atTime(hour, minute)
            leads.forEach { lead ->
                val trigger = outageStart.minusMinutes(lead.toLong())
                if (!trigger.isAfter(now)) return@forEach
                val id = (trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() xor index.toLong() xor lead.toLong()).toInt() and Int.MAX_VALUE
                val intent = Intent(context, OutageNotificationReceiver::class.java)
                    .putExtra("minutes_before", lead)
                    .putExtra("address", address)
                    .putExtra("start", fromText)
                    .putExtra("notification_id", id)
                val pending = PendingIntent.getBroadcast(
                    context, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarm.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    trigger.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    pending
                )
                newIds += id.toString()
            }
        }
        preferences.edit().putStringSet(PREF_IDS, newIds).apply()
    }

    private fun clearIds(preferences: android.content.SharedPreferences) {
        preferences.edit().putStringSet(PREF_IDS, emptySet()).apply()
    }
}
