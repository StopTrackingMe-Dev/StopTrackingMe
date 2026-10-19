package app.stoptrackingme

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BootRecoveryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun systemCanBindServiceBeforeUnlockWithPermissionProtection() {
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, ShareAccessibilityService::class.java), 0,
        )
        assertTrue(service.directBootAware)
        assertTrue(service.exported)
        assertEquals(Manifest.permission.BIND_ACCESSIBILITY_SERVICE, service.permission)
        val receiver = context.packageManager.getReceiverInfo(
            ComponentName(context, BootReceiver::class.java), 0,
        )
        assertTrue(receiver.directBootAware)
        assertTrue(!receiver.exported)
    }

    @Test
    fun bootAndUpdateOnlySignalRecoveryWithoutOpeningUiOrPrivateStorage() {
        val broadcasts = mutableListOf<Intent>()
        val recordingContext = object : ContextWrapper(context) {
            override fun sendBroadcast(intent: Intent) { broadcasts += intent }
            override fun startService(service: Intent): ComponentName? =
                error("Accessibility must only be bound by Android")
            override fun startForegroundService(service: Intent): ComponentName? =
                error("Boot recovery must not start a foreground service")
            override fun startActivity(intent: Intent) = error("Must not open UI at boot")
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                error("Boot receiver must not access credential encrypted preferences")
        }
        val receiver = BootReceiver()
        receiver.onReceive(recordingContext, Intent("unrelated.action"))
        assertTrue(broadcasts.isEmpty())
        listOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        ).forEach { receiver.onReceive(recordingContext, Intent(it)) }
        val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        assertEquals(if (unlocked) 3 else 0, broadcasts.size)
        assertTrue(broadcasts.all {
            it.action == BootReceiver.ACTION_RECOVER && it.`package` == context.packageName
        })
    }
}
