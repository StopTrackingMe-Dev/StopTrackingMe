package app.stoptrackingme

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import app.stoptrackingme.automation.AutomationStage

object ServiceStatus {
    const val PREFERENCES = "service_state"
    const val KEY_MESSAGE = "message"
    const val ACTION_CHANGED = "app.stoptrackingme.STATE_CHANGED"

    fun update(context: Context, message: String, stage: AutomationStage? = null) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit {
            putString(KEY_MESSAGE, message.take(200))
        }
        context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
        if (stage != null) {
            android.util.Log.i(LOG_TAG, "automation_stage=${stage.name}")
        }
    }

    private const val LOG_TAG = "StopTracking"
}
