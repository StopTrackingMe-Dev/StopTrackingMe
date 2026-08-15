package app.stoptrackingme

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings

internal fun isBatteryOptimizationDisabled(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java)
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

internal fun batteryOptimizationSettingsIntent(): Intent =
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
