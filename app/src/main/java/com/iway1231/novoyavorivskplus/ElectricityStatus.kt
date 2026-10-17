package com.iway1231.novoyavorivskplus

import com.google.gson.JsonObject

/**
 * Returns the current state supported by the API contract. Planned schedule
 * intervals are rendered separately and do not establish a factual state.
 */
internal enum class ElectricityState { UNKNOWN }

internal fun evaluateElectricityState(): ElectricityState {
    // The backend currently exposes only planned schedule data. It does not
    // expose a factual current state, so every result remains UNKNOWN.
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

private val outageRangePattern = Regex("^\\s*(\\d{1,2}:\\d{2})\\s*-\\s*(\\d{1,2}:\\d{2})\\s*$")

/** Converts the backend's compact HH:mm-HH:mm outage strings to schedule rows. */
internal fun plannedIntervalsFromOutages(outages: Iterable<String>): List<JsonObject> = outages.mapNotNull { raw ->
    val match = outageRangePattern.matchEntire(raw) ?: return@mapNotNull null
    val startValue = intervalMinuteValue(match.groupValues[1]) ?: return@mapNotNull null
    val endValue = intervalMinuteValue(match.groupValues[2]) ?: return@mapNotNull null
    if (endValue <= startValue) return@mapNotNull null
    JsonObject().apply {
        addProperty("from_time", "%02d:%02d".format(startValue / 60, startValue % 60))
        addProperty("to_time", "%02d:%02d".format(endValue / 60, endValue % 60))
        addProperty("status", "OFF")
    }
}

internal fun outageStrings(element: com.google.gson.JsonElement?): List<String> =
    element?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { value ->
        value.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asString
    }.orEmpty()

/** Reads the group-specific outages from a GROUP_SCHEDULE status response. */
internal fun groupScheduleOutages(status: JsonObject?, group: String): List<String> {
    if (status?.get("type")?.takeUnless { it.isJsonNull }?.asString != "GROUP_SCHEDULE") return emptyList()
    val groupKey = group.removePrefix("loe:")
    val groups = status.get("groups")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
    val groupData = groups.get(groupKey)?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
    return outageStrings(groupData.get("outages"))
}
