package app.stoptrackingme

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stoptrackingme.presentation.ResultPresentationMode
import app.stoptrackingme.presentation.ResultPresentationPreferences
import app.stoptrackingme.tiles.QuickLaunchTileService
import app.stoptrackingme.tiles.QuickTileAction
import app.stoptrackingme.usage.UsageReportingPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickTileInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun finishSetup() {
        UsageReportingPreferences.deny(context)
        FirstRunPreferences.markSetupGuideCompleted(context)
    }

    @Test
    fun packagedServicesMatchEditionAndAccessibilityCapability() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PERMISSIONS)
        val tiles = info.services.orEmpty().filter { it.permission == Manifest.permission.BIND_QUICK_SETTINGS_TILE }
        assertEquals(if (QrFeature.isAvailable) 3 else 2, tiles.size)
        assertTrue(tiles.all { it.exported })
        assertEquals(
            QrFeature.isAvailable,
            tiles.any { it.name == "app.stoptrackingme.tiles.QrImageTileService" },
        )
        assertEquals(
            BuildConfig.HAS_ACCESSIBILITY,
            info.services.orEmpty().any { it.permission == Manifest.permission.BIND_ACCESSIBILITY_SERVICE },
        )
        assertEquals(BuildConfig.HAS_ACCESSIBILITY, info.receivers.orEmpty().any { it.name == "app.stoptrackingme.BootReceiver" })
        assertEquals(BuildConfig.HAS_ACCESSIBILITY, info.requestedPermissions.orEmpty().contains(Manifest.permission.RECEIVE_BOOT_COMPLETED))
        if (!BuildConfig.HAS_ACCESSIBILITY) {
            assertFalse(ServiceStatus.isEnabled(context))
            ResultPresentationPreferences.set(context, ResultPresentationMode.ACCESSIBILITY_OVERLAY)
            assertEquals(ResultPresentationMode.APP_PAGE, ResultPresentationPreferences.get(context))
        }
    }

    @Test
    fun tileIntentsReuseMainTaskWithDistinctExplicitActions() {
        QuickTileAction.entries.forEach { action ->
            val intent = QuickLaunchTileService.createLaunchIntent(context, action)
            assertEquals(ComponentName(context, MainActivity::class.java), intent.component)
            assertEquals(action.intentAction, intent.action)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        }
    }

    @Test
    fun clipboardTileReadsAfterColdLaunchAndDoesNotRepeatOnRecreation() {
        setClipboardToNonUrl()
        withMainActivity(QuickTileAction.CLEAN_CLIPBOARD) { recreate ->
            compose.onNodeWithText("没有找到网页链接").assertIsDisplayed()
            recreate()
            compose.onNodeWithText("没有找到网页链接").assertDoesNotExist()
        }
    }

    @Test
    fun openTileDoesNotReadAndClipboardTileWorksOnWarmLaunch() {
        setClipboardToNonUrl()
        withMainActivity(QuickTileAction.OPEN_APP) { _ ->
            compose.onNodeWithText("没有找到网页链接").assertDoesNotExist()
            context.startActivity(QuickLaunchTileService.createLaunchIntent(context, QuickTileAction.CLEAN_CLIPBOARD))
            compose.waitUntil(5_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText("没有找到网页链接")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("没有找到网页链接").assertIsDisplayed()
        }
    }

    @Test
    fun pendingClipboardTileSurvivesSetupAndRecreation() {
        context.getSharedPreferences("first_run", 0).edit().clear().commit()
        setClipboardToNonUrl()
        withMainActivity(QuickTileAction.CLEAN_CLIPBOARD) { recreate ->
            compose.onNodeWithText("欢迎使用").assertIsDisplayed()
            compose.onNodeWithText("没有找到网页链接").assertDoesNotExist()
            recreate()
            compose.onNodeWithText("开始使用").performClick()
            compose.onNodeWithText("没有找到网页链接").assertIsDisplayed()
        }
    }

    @Test
    fun qrTileOpensPickerOnceOnlyInFull() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pickerIntent = MainActivity.createQrImagePickerIntent(context)
        val filter = IntentFilter(pickerIntent.action).apply { addDataType("image/*") }
        val monitor = instrumentation.addMonitor(
            filter,
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
            true,
        )
        try {
            withMainActivity(QuickTileAction.PICK_QR_IMAGE) { recreate ->
                compose.waitForIdle()
                if (QrFeature.isAvailable) compose.waitUntil(5_000) { monitor.hits == 1 }
                assertEquals(if (QrFeature.isAvailable) 1 else 0, monitor.hits)
                recreate()
                compose.waitForIdle()
                assertEquals(if (QrFeature.isAvailable) 1 else 0, monitor.hits)
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    // Class-based monitors keep observing recreation after MainActivity consumes a tile Intent.
    // ActivityScenario matches the original Intent action and loses that recreated activity.
    private fun withMainActivity(action: QuickTileAction, block: (recreate: () -> Unit) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        var activity: Activity? = null
        try {
            context.startActivity(QuickLaunchTileService.createLaunchIntent(context, action))
            activity = monitor.waitForActivityWithTimeout(5_000) ?: error("MainActivity did not start")
            compose.waitForIdle()
            block {
                instrumentation.runOnMainSync { activity!!.recreate() }
                activity = monitor.waitForActivityWithTimeout(5_000) ?: error("MainActivity did not recreate")
                compose.waitForIdle()
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.removeMonitor(monitor)
            instrumentation.waitForIdleSync()
        }
    }

    private fun setClipboardToNonUrl() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("tile test", "这里没有网页链接"))
        }
    }
}
