package app.stoptrackingme

import android.content.Context
import androidx.core.content.edit

/** Stores whether the one-time setup recommendations have been acknowledged. */
internal object FirstRunPreferences {
    private const val PREFERENCES = "first_run"
    private const val KEY_SETUP_GUIDE_COMPLETED = "setup_guide_completed"

    fun isSetupGuideCompleted(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_SETUP_GUIDE_COMPLETED, false)

    fun markSetupGuideCompleted(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit(commit = true) {
                putBoolean(KEY_SETUP_GUIDE_COMPLETED, true)
            }
    }
}
