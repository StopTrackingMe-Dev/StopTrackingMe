package app.stoptrackingme

import android.content.Context
import androidx.core.content.edit

object ShareTextPreferences {
    private const val PREFERENCES = "share_text"
    private const val KEY_PRESERVE_ORIGINAL_TEXT = "preserve_original_text"

    fun preserveOriginalText(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(KEY_PRESERVE_ORIGINAL_TEXT, true)

    fun setPreserveOriginalText(context: Context, preserve: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_PRESERVE_ORIGINAL_TEXT, preserve)
        }
    }
}
