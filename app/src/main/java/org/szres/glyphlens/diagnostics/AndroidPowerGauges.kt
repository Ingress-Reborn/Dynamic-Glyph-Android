package org.szres.glyphlens.diagnostics

import android.content.Context
import android.os.Debug
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/** Real process CPU, PSS and (when a context is supplied) system power gauges. */
class AndroidPowerGauges(context: Context? = null) : PowerGaugeReader {
    @Volatile
    private var powerManager = context?.applicationContext?.getSystemService(PowerManager::class.java)

    fun attach(context: Context) {
        if (powerManager == null) {
            powerManager = context.applicationContext.getSystemService(PowerManager::class.java)
        }
    }

    override fun read(): PowerGaugeSnapshot = PowerGaugeSnapshot(
        cpuTimeNanoseconds = processCpuNanoseconds(),
        thermalStatus = runCatching { powerManager?.currentThermalStatus }.getOrNull(),
        lowPowerMode = runCatching { powerManager?.isPowerSaveMode }.getOrNull(),
        memoryFootprintBytes = Debug.getPss().takeIf { it > 0 }?.times(1024),
    )

    private fun processCpuNanoseconds(): Long? {
        val ticksPerSecond = Os.sysconf(OsConstants._SC_CLK_TCK).takeIf { it > 0 } ?: return null
        return try {
            // Field 2 (comm) can contain spaces and ')', so split after its final parenthesis.
            val stat = File("/proc/self/stat").readText()
            val fields = stat.substringAfterLast(')').trim().split(Regex("\\s+"))
            val userTicks = fields.getOrNull(11)?.toLongOrNull() ?: return null
            val systemTicks = fields.getOrNull(12)?.toLongOrNull() ?: return null
            (userTicks + systemTicks) * 1_000_000_000L / ticksPerSecond
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }
}
