package com.iway1231.novoyavorivskplus

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ElectricityStatusTest {
    @Test fun plannedIntervalAtElevenThirtyDoesNotChangeUnknown() {
        assertEquals(ElectricityState.UNKNOWN, evaluateElectricityState())
    }

    @Test fun plannedIntervalAtElevenDoesNotChangeUnknown() {
        assertEquals(ElectricityState.UNKNOWN, evaluateElectricityState())
    }

    @Test fun plannedIntervalAtElevenFiftyNineDoesNotChangeUnknown() {
        assertEquals(ElectricityState.UNKNOWN, evaluateElectricityState())
    }

    @Test fun plannedIntervalBeforeStartDoesNotChangeUnknown() {
        assertEquals(ElectricityState.UNKNOWN, evaluateElectricityState())
    }

    @Test fun plannedIntervalAtTwelveDoesNotChangeUnknown() {
        assertEquals(ElectricityState.UNKNOWN, evaluateElectricityState())
    }

    @Test fun plannedIntervalAfterEndDoesNotChangeUnknown() {
        assertEquals(ElectricityState.UNKNOWN, evaluateElectricityState())
    }

    @Test fun onMarkedPlanDoesNotChangeUnknown() {
        assertEquals(ElectricityState.UNKNOWN, evaluateElectricityState())
    }

    @Test fun missingApiDataIsUnknown() {
        assertEquals(ElectricityState.UNKNOWN, evaluateElectricityState())
    }

    @Test fun freshResponseWithoutOldIntervalClearsStaleOutage() {
        val afterOldPlan = evaluateElectricityState()
        val afterFreshResponse = evaluateElectricityState()
        assertEquals(ElectricityState.UNKNOWN, afterOldPlan)
        assertEquals(ElectricityState.UNKNOWN, afterFreshResponse)
    }

    @Test fun groupScheduleOutageBecomesPlannedInterval() {
        val intervals = plannedIntervalsFromOutages(groupScheduleOutages(groupSchedule("2.2" to listOf("14:00-18:00")), "2.2"))
        assertEquals(1, intervals.size)
        assertEquals("14:00", intervals.single().get("from_time").asString)
        assertEquals("18:00", intervals.single().get("to_time").asString)
        assertEquals("OFF", intervals.single().get("status").asString)
    }

    @Test fun mobileHomeOutageBecomesPlannedInterval() {
        val section = JsonObject().apply {
            add("intervals", JsonArray())
            add("outages", JsonArray().apply { add("14:00-18:00") })
        }
        val intervals = plannedIntervalsFromOutages(outageStrings(section.get("outages")))
        assertEquals("14:00", intervals.single().get("from_time").asString)
        assertEquals("18:00", intervals.single().get("to_time").asString)
    }

    @Test fun groupScheduleDoesNotMixGroups() {
        val status = groupSchedule("2.2" to listOf("14:00-18:00"), "4.1" to listOf("18:00-22:00"))
        assertEquals(listOf("14:00-18:00"), groupScheduleOutages(status, "2.2"))
        assertEquals(listOf("18:00-22:00"), groupScheduleOutages(status, "4.1"))
        assertEquals(emptyList<String>(), groupScheduleOutages(status, "6.1"))
    }

    @Test fun emptyOutagesStayEmpty() {
        assertEquals(emptyList<JsonObject>(), plannedIntervalsFromOutages(emptyList()))
    }

    private fun groupSchedule(vararg groups: Pair<String, List<String>>): JsonObject = JsonObject().apply {
        addProperty("type", "GROUP_SCHEDULE")
        add("groups", JsonObject().apply {
            groups.forEach { (name, outages) ->
                add(name, JsonObject().apply {
                    addProperty("status", "OFF")
                    add("outages", JsonArray().apply { outages.forEach(::add) })
                })
            }
        })
    }
}
