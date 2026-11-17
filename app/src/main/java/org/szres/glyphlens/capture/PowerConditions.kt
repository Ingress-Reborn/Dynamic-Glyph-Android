package org.szres.glyphlens.capture

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock

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

    private var cached: State? = null
    private var cachedAtMillis = 0L

    /**
     * Read for every analysed frame. Both values come from system services and change rarely,
     * so one reading serves the frames of the next second.
     */
    val current: State
        get() {
            val now = SystemClock.elapsedRealtime()
            cached?.let { if (now - cachedAtMillis in 0 until REFRESH_MILLIS) return it }
            return State(powerManager.isPowerSaveMode, powerManager.currentThermalStatus).also {
                cached = it
                cachedAtMillis = now
            }
        }

    private companion object {
        const val REFRESH_MILLIS = 1_000L
    }
}
