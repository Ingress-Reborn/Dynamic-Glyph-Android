package org.szres.glyphlens.capture

import android.content.Context
import android.os.PowerManager

class PowerConditions(context: Context) {
    data class State(
        val lowPowerMode: Boolean,
        val thermalStatus: Int,
    ) {
        val isConstrained: Boolean
            get() = lowPowerMode || thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE

        val slowdown: String
            get() {
                val causes = mutableListOf<String>()
                if (lowPowerMode) causes += "Low Power Mode"
                when (thermalStatus) {
                    PowerManager.THERMAL_STATUS_SEVERE -> causes += "warm (serious)"
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_EMERGENCY,
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> causes += "warm (critical)"
                }
                return causes.ifEmpty { listOf("no") }.joinToString(", ")
            }

        val thermalName: String
            get() = when (thermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "none"
                PowerManager.THERMAL_STATUS_LIGHT -> "light"
                PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
                else -> "unknown"
            }
    }

    private val powerManager = context.getSystemService(PowerManager::class.java)

    val current: State
        get() = State(powerManager.isPowerSaveMode, powerManager.currentThermalStatus)
}
