package app.stoptrackingme.presentation

import android.content.Context
import androidx.core.content.edit

enum class ResultPresentationMode {
    APP_PAGE,
    ACCESSIBILITY_OVERLAY,
    ;

    val opensResultActivityAutomatically: Boolean
        get() = this == APP_PAGE

    companion object {
        fun fromStored(value: String?): ResultPresentationMode =
            entries.firstOrNull { it.name == value } ?: APP_PAGE
    }
}

object ResultPresentationPreferences {
    private const val PREFERENCES = "result_presentation"
    private const val KEY_MODE = "mode"

    fun get(context: Context): ResultPresentationMode = ResultPresentationMode.fromStored(
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null),
    )

    fun set(context: Context, mode: ResultPresentationMode) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putString(KEY_MODE, mode.name)
        }
    }
}
