package app.stoptrackingme.update

import android.content.Context
import androidx.core.content.edit

internal object AppUpdatePreferences {
    private const val PREFERENCES = "app_updates"
    private const val KEY_DISMISSED_TAG = "dismissed_tag"

    fun isDismissed(context: Context, release: AppUpdateRelease): Boolean =
        preferences(context).getString(KEY_DISMISSED_TAG, null) == release.dismissKey

    fun dismiss(context: Context, release: AppUpdateRelease) {
        preferences(context).edit { putString(KEY_DISMISSED_TAG, release.dismissKey) }
    }

    private val AppUpdateRelease.dismissKey: String
        get() = "$tagName:${variant.wireName}"

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
