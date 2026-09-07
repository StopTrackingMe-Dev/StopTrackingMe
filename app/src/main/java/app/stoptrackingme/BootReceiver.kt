package app.stoptrackingme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager

/** AccessibilityService is bound by Android; never start it as an ordinary service. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
            )
        ) return
        // No rules, preferences or share data are needed before the first unlock.
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        context.sendBroadcast(Intent(ACTION_RECOVER).setPackage(context.packageName))
    }

    companion object {
        const val ACTION_RECOVER = "app.stoptrackingme.RECOVER_AFTER_BOOT"
    }
}
