package app.stoptrackingme

import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import androidx.core.content.edit
import app.stoptrackingme.automation.AutomationStage

object ServiceStatus {
    // Never persist a connection flag: a new process must start disconnected.
    @Volatile
    var connected: Boolean = false
        private set

    fun setConnected(context: Context, value: Boolean) {
        connected = value
        context.sendBroadcast(Intent(ACTION_CHANGED).setPackage(context.packageName))
    }

    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, ShareAccessibilityService::class.java)
        return context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { service ->
                val info = service.resolveInfo?.serviceInfo ?: return@any false
                ComponentName(info.packageName, info.name) == expected
            }
    }

    const val PREFERENCES = "service_state"
    const val KEY_MESSAGE = "message"
    const val ACTION_CHANGED = "app.stoptrackingme.STATE_CHANGED"
    const val READY_MESSAGE = "点分享时会自动净化"

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
