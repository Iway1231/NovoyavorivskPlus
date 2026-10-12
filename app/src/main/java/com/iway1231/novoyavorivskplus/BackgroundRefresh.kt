package com.iway1231.novoyavorivskplus

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.os.Build
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.iway1231.novoyavorivskplus.api.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Refreshes every saved group while the UI is closed. A failed request is left in
 * the cache and the next alarm retries it, so temporary API downtime is harmless. */
class BackgroundRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == ACTION_REFRESH || intent?.action == null) {
            schedule(context)
            val pending = goAsync()
            val finished = AtomicBoolean(false)
            CoroutineScope(Dispatchers.IO).launch {
                try { refresh(context.applicationContext) } finally {
                    if (finished.compareAndSet(false, true)) pending.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.iway1231.novoyavorivskplus.REFRESH_CACHE"
        private const val INTERVAL_MS = 10 * 60 * 1000L

        fun schedule(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, BackgroundRefreshReceiver::class.java).setAction(ACTION_REFRESH)
            val pending = PendingIntent.getBroadcast(
                context, 7101, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.cancel(pending)
            alarm.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, INTERVAL_MS, INTERVAL_MS, pending)
        }

        private suspend fun refresh(context: Context) {
            ApiClient.initialize(context)
            val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            val savedEntries = preferences.getString("saved_locations", "").orEmpty()
                .split("||").mapNotNull { item ->
                    val parts = item.split("::", limit = 4)
                    if (parts.size >= 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
                }
            val groups = (savedEntries.map { it.first } + preferences.getString("naftogaz_group", "").orEmpty())
                .filter(String::isNotBlank).distinct()
            runCatching {
                val freshGroups = ApiClient.api.getNaftogazGroups()
                preferences.edit().putString("cache_groups", freshGroups.joinToString(prefix = "[", postfix = "]") { it.toString() })
                    .putLong("cache_groups_time", System.currentTimeMillis()).apply()
            }
            val power = runCatching { ApiClient.api.getPowerStatus() }.getOrNull()
            if (power != null) preferences.edit().putString("cache_power", power.toString())
                .putLong("cache_power_time", System.currentTimeMillis()).apply()
            groups.forEach { group ->
                val isLoe = group.startsWith("loe:")
                val apiGroup = group.removePrefix("loe:")
                val cacheId = (if (isLoe) "loe_" else "naftogaz_") + apiGroup.replace(".", "_")
                val key = "cache_home_$cacheId"
                val old = runCatching { preferences.getString(key, null)?.let { JsonParser.parseString(it).asJsonObject } }.getOrNull()
                val fresh = runCatching { ApiClient.api.getMobileHome(if (isLoe) "loe" else "naftogaz", apiGroup) }.getOrNull() ?: return@forEach
                val now = System.currentTimeMillis()
                val oldSchedule = electricityFingerprint(old)
                val newSchedule = electricityFingerprint(fresh)
                preferences.edit().remove("cache_home_old_$cacheId").putString(key, fresh.toString())
                    .putLong("cache_home_time_$cacheId", now).apply()
                val electricity = fresh.getAsJsonArray("sections")?.firstOrNull {
                    it.isJsonObject && it.asJsonObject.get("id")?.asString == "electricity"
                }?.asJsonObject
                val address = savedEntries.firstOrNull { it.first == group }?.second.orEmpty()
                if (electricity != null) OutageNotificationScheduler.schedule(context, electricity, address)
                // An electricity section can exist before the operator publishes
                // its schedule. Notify only after the API supplies a date and at
                // least one interval, and only when that published data changes.
                if (hasPublishedSchedule(electricity) && newSchedule != oldSchedule) {
                    notifySchedule(
                        context,
                        if (isLoe) "loe" else "naftogaz",
                        scheduleWasChanged = oldSchedule.isNotBlank()
                    )
                }
            }
        }

        private fun electricityFingerprint(home: JsonObject?): String {
            val section = home?.getAsJsonArray("sections")?.firstOrNull {
                it.isJsonObject && it.asJsonObject.get("id")?.asString == "electricity"
            }?.asJsonObject ?: return ""
            return section.toString()
        }

        private fun hasPublishedSchedule(section: JsonObject?): Boolean {
            val date = section?.get("date")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            val intervals = section?.get("intervals")?.takeIf { it.isJsonArray }?.asJsonArray
            return date.isNotBlank() && intervals != null && intervals.size() > 0
        }

        private fun notifySchedule(context: Context, operator: String, scheduleWasChanged: Boolean) {
            val channelId = "schedule_$operator"
            val title = if (operator == "loe") "Львівобленерго" else "Нафтогаз Тепло"
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(NotificationChannel(channelId, "Нові графіки: $title", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(context, channelId)
            else Notification.Builder(context)
            manager.notify((operator.hashCode() and 0x7fffffff), builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(if (scheduleWasChanged) "Увага: графік змінився" else "З’явився новий графік")
                .setContentText(if (scheduleWasChanged) "Перевірте оновлений графік: $title" else "Опубліковано графік відключень: $title")
                .setAutoCancel(true).build())
        }
    }
}
