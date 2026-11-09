package org.szres.glyphlens.capture

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerConditionsTest {
    @Test
    fun onlyLowPowerOrSevereHeatConstrainsAnalysis() {
        assertFalse(PowerConditions.State(false, PowerManager.THERMAL_STATUS_MODERATE).isConstrained)
        assertTrue(PowerConditions.State(true, PowerManager.THERMAL_STATUS_NONE).isConstrained)
        assertTrue(PowerConditions.State(false, PowerManager.THERMAL_STATUS_SEVERE).isConstrained)
        assertTrue(PowerConditions.State(false, PowerManager.THERMAL_STATUS_CRITICAL).isConstrained)
    }

    @Test
    fun slowdownRetainsIndependentCauses() {
        assertEquals("no", PowerConditions.State(false, PowerManager.THERMAL_STATUS_LIGHT).slowdown)
        assertEquals(
            "Low Power Mode, warm (serious)",
            PowerConditions.State(true, PowerManager.THERMAL_STATUS_SEVERE).slowdown,
        )
        assertEquals(
            "warm (critical)",
            PowerConditions.State(false, PowerManager.THERMAL_STATUS_SHUTDOWN).slowdown,
        )
    }
}
