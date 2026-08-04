package app.stoptrackingme.update

import android.content.Context
import androidx.core.content.edit

internal object AppUpdatePreferences {
    private const val PREFERENCES = "app_updates"
    private const val KEY_LAST_SUCCESSFUL_CHECK = "last_successful_check"
    private const val KEY_DISMISSED_TAG = "dismissed_tag"
    private const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1_000

    fun shouldAutomaticallyCheck(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val lastCheck = preferences(context).getLong(KEY_LAST_SUCCESSFUL_CHECK, 0L)
        return lastCheck <= 0L || now - lastCheck >= AUTO_CHECK_INTERVAL_MS || now < lastCheck
    }

    fun recordSuccessfulCheck(context: Context, now: Long = System.currentTimeMillis()) {
        preferences(context).edit { putLong(KEY_LAST_SUCCESSFUL_CHECK, now) }
    }

    fun isDismissed(context: Context, tagName: String): Boolean =
        preferences(context).getString(KEY_DISMISSED_TAG, null) == tagName

    fun dismiss(context: Context, tagName: String) {
        preferences(context).edit { putString(KEY_DISMISSED_TAG, tagName) }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
