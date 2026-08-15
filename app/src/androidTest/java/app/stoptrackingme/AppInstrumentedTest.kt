package app.stoptrackingme

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.stoptrackingme.rules.RuleParser
import app.stoptrackingme.rules.RuleRepository
import app.stoptrackingme.rules.ActiveRuleResolution
import app.stoptrackingme.rules.RuleSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppInstrumentedTest {
    @Test
    fun mainActivityAcceptsPlainTextSystemShares() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context, MainActivity::class.java)
        val filters = context.packageManager
            .queryIntentActivities(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "https://example.com"),
                0,
            )

        assertTrue(
            filters.any {
                it.activityInfo.packageName == component.packageName &&
                    it.activityInfo.name == component.className
            },
        )
    }

    @Test
    fun qrImageActivityAcceptsImageSystemShares() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(context, QrImageActivity::class.java)
        val matches = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(
                    Intent.EXTRA_STREAM,
                    Uri.parse("content://app.stoptrackingme.test/source.png"),
                ),
            0,
        )

        assertTrue(
            matches.any {
                it.activityInfo.packageName == component.packageName &&
                    it.activityInfo.name == component.className
            },
        )
    }

    @Test
    fun builtInRulesArePackagedAndValid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf(
            "baidu-tieba.json",
            "bilibili.json",
            "netease-cloud-music.json",
            "xiaohongshu.json",
            "zhihu.json",
        ).forEach { name ->
            val bytes = context.assets.open("rules/$name").use { it.readBytes() }
            val rule = RuleParser().parse(bytes).rules.single()

            assertEquals(RuleSourceKind.BUILTIN, rule.source.kind)
            assertTrue(rule.copyLinkSelectors.isNotEmpty())
        }
    }

    @Test
    fun repositoryAlwaysLoadsRecoverableBuiltInRule() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = RuleRepository.get(context).reload()

        assertTrue(
            catalog.installedRules.any { installed ->
                installed.rule.source.kind == RuleSourceKind.BUILTIN
            },
        )
    }

    @Test
    fun batteryOptimizationStatusMatchesSystemExemptionState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val powerManager = context.getSystemService(PowerManager::class.java)

        assertEquals(
            powerManager.isIgnoringBatteryOptimizations(context.packageName),
            isBatteryOptimizationDisabled(context),
        )
    }

    @Test
    fun batteryOptimizationSettingsUsesUserManagedExemptionScreen() {
        assertEquals(
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
            batteryOptimizationSettingsIntent().action,
        )
    }

    @Test
    fun validatedLocalImportCreatesExplicitConflict() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = RuleRepository.get(context)
        val bytes = context.assets.open("rules/bilibili.json").use { it.readBytes() }
        val imported = repository.importLocal(bytes.inputStream()).rules.single()

        assertEquals(RuleSourceKind.LOCAL, imported.source.kind)
        assertTrue(
            repository.resolveActiveRule(imported.target.packageName) is
                ActiveRuleResolution.Conflict,
        )
    }

    @Test
    fun shareFactoryAlwaysBuildsTextPlainSystemChooser() {
        val chooser = ShareIntentFactory.createChooser("https://example.com/clean")
        val send = if (Build.VERSION.SDK_INT >= 33) {
            chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            chooser.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
        }

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertNotNull(send)
        assertEquals(Intent.ACTION_SEND, send?.action)
        assertEquals("text/plain", send?.type)
        assertEquals("https://example.com/clean", send?.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun imageShareFactoryGrantsOnlyTemporaryReadAccessWithClipData() {
        val uri = Uri.parse("content://app.stoptrackingme.qr.fileprovider/qr_images/result.png")
        val chooser = ImageShareIntentFactory.createChooser(uri, "image/png")
        val send = if (Build.VERSION.SDK_INT >= 33) {
            chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            chooser.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
        }

        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals(Intent.ACTION_SEND, send?.action)
        assertEquals("image/png", send?.type)
        assertEquals(uri, send?.clipData?.getItemAt(0)?.uri)
        assertTrue(
            ((send?.flags ?: 0) and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
        )
        assertEquals(0, (send?.flags ?: 0) and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }
}
