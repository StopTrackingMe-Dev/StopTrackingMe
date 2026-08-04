package app.stoptrackingme.update

import android.content.Context
import androidx.core.content.edit

internal object AppUpdatePreferences {
    private const val PREFERENCES = "app_updates"
    private const val KEY_DISMISSED_TAG = "dismissed_tag"

    fun isDismissed(context: Context, tagName: String): Boolean =
        preferences(context).getString(KEY_DISMISSED_TAG, null) == tagName

    fun dismiss(context: Context, tagName: String) {
        preferences(context).edit { putString(KEY_DISMISSED_TAG, tagName) }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
