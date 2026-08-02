package app.stoptrackingme.link

import app.stoptrackingme.TestFixtures
import app.stoptrackingme.rules.InstalledRule
import app.stoptrackingme.rules.RuleSource
import app.stoptrackingme.rules.RuleSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlRuleMatcherTest {
    @Test
    fun matchesShortLinkHostWithoutSourcePackage() {
        val rule = TestFixtures.builtInRule("bilibili")

        val result = UrlRuleMatcher.resolve(
            "复制成功 https://b23.tv/example",
            listOf(InstalledRule("bilibili", rule)),
        ) as UrlRuleResolution.Active

        assertEquals("bilibili", result.candidate.installed.key)
    }

    @Test
    fun matchesSubdomainOfAllowedFinalHost() {
        val rule = TestFixtures.builtInRule("bilibili")

        val result = UrlRuleMatcher.resolve(
            "https://www.bilibili.com/video/BV1xx",
            listOf(InstalledRule("bilibili", rule)),
        )

        assertTrue(result is UrlRuleResolution.Active)
    }

    @Test
    fun unsupportedHostDoesNotUseUnrelatedCleaningPolicy() {
        val rule = TestFixtures.builtInRule("bilibili")

        val result = UrlRuleMatcher.resolve(
            "https://example.com/?utm_source=test",
            listOf(InstalledRule("bilibili", rule)),
        )

        assertTrue(result is UrlRuleResolution.Unsupported)
    }

    @Test
    fun overlappingRulesRequireExplicitChoice() {
        val base = TestFixtures.builtInRule("bilibili")
        val other = base.copy(
            id = "other",
            source = RuleSource(RuleSourceKind.LOCAL, "other.json"),
        )

        val result = UrlRuleMatcher.resolve(
            "https://b23.tv/example",
            listOf(InstalledRule("one", base), InstalledRule("two", other)),
        ) as UrlRuleResolution.Conflict

        assertEquals(listOf("one", "two"), result.candidates.map { it.installed.key })
    }
}
