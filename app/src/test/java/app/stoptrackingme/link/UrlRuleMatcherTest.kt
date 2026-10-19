package app.stoptrackingme.link

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.style.TextDecoration
import app.stoptrackingme.MainActivity
import app.stoptrackingme.TestFixtures
import app.stoptrackingme.rules.InstalledRule
import app.stoptrackingme.rules.RuleSource
import app.stoptrackingme.rules.RuleSourceKind
import app.stoptrackingme.ui.theme.LinkBlue
import app.stoptrackingme.unsupportedLinkDialogMessage
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
    fun overlappingRulesUseFirstRuleInPriorityOrder() {
        val base = TestFixtures.builtInRule("bilibili")
        val other = base.copy(
            id = "other",
            source = RuleSource(RuleSourceKind.LOCAL, "other.json"),
        )

        val result = UrlRuleMatcher.resolve(
            "https://b23.tv/example",
            listOf(InstalledRule("one", base), InstalledRule("two", other)),
        ) as UrlRuleResolution.Active

        assertEquals("one", result.candidate.installed.key)
    }

    @Test
    fun unsupportedLinkDialogMessageIncludesBlueClickableRulesIssueLink() {
        var opened = false
        val message = unsupportedLinkDialogMessage("example.com") { opened = true }

        assertEquals(
            "example.com 暂无规则，链接会保持原样。你可以在 https://github.com/StopTrackingMe-Dev/rules/issues 提交 issue。",
            message.text,
        )
        val linkRange = message.getLinkAnnotations(0, message.length).single()
        assertEquals(
            MainActivity.RULES_ISSUES_URL,
            message.text.substring(linkRange.start, linkRange.end),
        )
        val clickable = linkRange.item as LinkAnnotation.Clickable
        assertEquals(LinkBlue, clickable.styles?.style?.color)
        assertEquals(TextDecoration.Underline, clickable.styles?.style?.textDecoration)
        clickable.linkInteractionListener?.onClick(clickable)
        assertTrue(opened)
    }
}

