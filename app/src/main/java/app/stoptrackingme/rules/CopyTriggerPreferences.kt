package app.stoptrackingme.rules

import android.content.Context
import androidx.core.content.edit

object CopyTriggerPreferences {
    private const val PREFERENCES = "copy_trigger_preferences"

    fun get(context: Context, installed: InstalledRule): CopyTriggerMode {
        val stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(installed.key, null)
        return resolve(installed.rule.copyTriggerMode, stored)
    }

    fun set(context: Context, installed: InstalledRule, mode: CopyTriggerMode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putString(installed.key, mode.name)
        }
    }

    internal fun resolve(defaultMode: CopyTriggerMode, stored: String?): CopyTriggerMode =
        CopyTriggerMode.entries.firstOrNull { it.name == stored } ?: defaultMode
}
