package com.iway1231.novoyavorivskplus

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class ElectricityStatusTest {
    private fun outage(status: String = "OFF"): JsonObject = JsonObject().apply {
        addProperty("from_time", "11:00")
        addProperty("to_time", "12:00")
        addProperty("status", status)
    }

    @Test fun activeIntervalIsOff() {
        assertEquals(ElectricityState.OFF, evaluateElectricityState("2026-10-16", listOf(outage()), LocalDateTime.of(2026, 10, 16, 11, 30)))
    }

    @Test fun beforeIntervalIsNotOff() {
        assertEquals(ElectricityState.UNKNOWN, evaluateElectricityState("2026-10-16", listOf(outage()), LocalDateTime.of(2026, 10, 16, 10, 59)))
    }

    @Test fun afterIntervalIsNotOff() {
        assertEquals(ElectricityState.UNKNOWN, evaluateElectricityState("2026-10-16", listOf(outage()), LocalDateTime.of(2026, 10, 16, 12, 2)))
    }

    @Test fun apiOnStatusWinsWhenNoActiveOutage() {
        assertEquals(ElectricityState.ON, evaluateElectricityState("2026-10-16", listOf(outage()), LocalDateTime.of(2026, 10, 16, 12, 2), "ON"))
    }

    @Test fun intervalMarkedOnIsNotAnOutage() {
        assertEquals(ElectricityState.ON, evaluateElectricityState("2026-10-16", listOf(outage("ON")), LocalDateTime.of(2026, 10, 16, 11, 30)))
    }

    @Test fun missingApiDataIsUnknown() {
        assertEquals(ElectricityState.UNKNOWN, evaluateElectricityState(null, listOf(outage()), LocalDateTime.of(2026, 10, 16, 12, 2)))
    }

    @Test fun freshResponseWithoutOldIntervalClearsStaleOutage() {
        val afterOldPlan = evaluateElectricityState("2026-10-16", listOf(outage()), LocalDateTime.of(2026, 10, 16, 12, 2))
        val afterFreshResponse = evaluateElectricityState("2026-10-16", emptyList(), LocalDateTime.of(2026, 10, 16, 12, 2))
        assertEquals(ElectricityState.UNKNOWN, afterOldPlan)
        assertEquals(ElectricityState.ON, afterFreshResponse)
    }
}
