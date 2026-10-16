package com.iway1231.novoyavorivskplus

import com.google.gson.JsonObject
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Evaluates the current state from the API intervals without changing the
 * schedule that is shown to the user. A planned interval is active only on
 * its date and in the half-open range [from, to).
 */
internal enum class ElectricityState { ON, OFF, UNKNOWN }

internal fun evaluateElectricityState(
    scheduleDate: String?,
    intervals: List<JsonObject>,
    now: LocalDateTime,
    apiActualStatus: String? = null
): ElectricityState {
    val normalizedActual = apiActualStatus?.trim()?.uppercase()
    val date = scheduleDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    val activePlannedOutage = date == now.toLocalDate() && intervals.any { interval ->
        val intervalStatus = interval.get("status")?.takeUnless { it.isJsonNull }?.asString
        if (intervalStatus.equals("ON", ignoreCase = true)) return@any false
        val start = intervalMinuteValue(interval.get("from_time")?.takeUnless { it.isJsonNull }?.asString)
        val end = intervalMinuteValue(interval.get("to_time")?.takeUnless { it.isJsonNull }?.asString)
        if (start == null || end == null || end <= start) return@any false
        val minuteNow = now.hour * 60 + now.minute
        minuteNow in start until end
    }

    if (activePlannedOutage) return ElectricityState.OFF
    if (normalizedActual == "OFF") return ElectricityState.OFF
    if (normalizedActual == "ON") return ElectricityState.ON
    if (intervals.isEmpty()) return ElectricityState.ON
    if (intervals.all { interval ->
            interval.get("status")?.takeUnless { it.isJsonNull }?.asString.equals("ON", ignoreCase = true)
        }) return ElectricityState.ON

    // A schedule that is future or already finished does not prove the
    // physical state. Keep it visible in the graph but do not claim "ON".
    return ElectricityState.UNKNOWN
}

internal fun intervalMinuteValue(value: String?): Int? {
    if (value.isNullOrBlank()) return null
    val parts = value.split(":")
    if (parts.size != 2) return null
    val hours = parts[0].toIntOrNull() ?: return null
    val minutes = parts[1].toIntOrNull() ?: return null
    if (hours !in 0..24 || minutes !in 0..59 || (hours == 24 && minutes != 0)) return null
    return hours * 60 + minutes
}
