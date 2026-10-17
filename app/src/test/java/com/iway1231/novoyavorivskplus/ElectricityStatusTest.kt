package com.iway1231.novoyavorivskplus

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
}
