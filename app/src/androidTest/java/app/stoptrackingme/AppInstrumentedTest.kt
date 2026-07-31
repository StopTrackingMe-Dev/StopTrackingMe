package app.stoptrackingme

import android.content.Intent
import android.os.Build
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
    fun builtInRulesArePackagedAndValid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        listOf("bilibili.json", "xiaohongshu.json", "zhihu.json").forEach { name ->
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
}
