package com.iway1231.novoyavorivskplus

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
