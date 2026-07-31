package app.stoptrackingme.rules

import app.stoptrackingme.TestFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleSelectionTest {
    @Test
    fun conflictPausesUntilOneRuleIsSelected() {
        val base = TestFixtures.builtInRule()
        val first = InstalledRule("first", base)
        val second = InstalledRule(
            "second",
            base.copy(id = "second", source = RuleSource(RuleSourceKind.LOCAL, "second.json")),
        )

        assertTrue(
            RuleSelection.resolve(base.target.packageName, 100, listOf(first, second), null) is
                ActiveRuleResolution.Conflict,
        )
        val selected = RuleSelection.resolve(
            base.target.packageName,
            100,
            listOf(first, second),
            "second",
        ) as ActiveRuleResolution.Active
        assertSame(second, selected.installed)
    }

    @Test
    fun incompatibleVersionDoesNotCreateConflict() {
        val base = TestFixtures.builtInRule()
        val compatible = InstalledRule("compatible", base)
        val future = InstalledRule(
            "future",
            base.copy(
                id = "future",
                target = base.target.copy(minVersionCode = 1_000),
            ),
        )

        val result = RuleSelection.resolve(
            base.target.packageName,
            10,
            listOf(compatible, future),
            null,
        ) as ActiveRuleResolution.Active
        assertEquals("compatible", result.installed.key)
    }

    @Test
    fun unknownPackageHasNoRule() {
        val base = TestFixtures.builtInRule()
        val result = RuleSelection.resolve(
            "example.unknown",
            1,
            listOf(InstalledRule("one", base)),
            null,
        )

        assertSame(ActiveRuleResolution.NoRule, result)
    }

    @Test
    fun staleExplicitSelectionPausesInsteadOfSilentlyFallingBack() {
        val base = TestFixtures.builtInRule()
        val remaining = InstalledRule("remaining", base)

        val result = RuleSelection.resolve(
            base.target.packageName,
            1,
            listOf(remaining),
            "missing-selected-rule",
        )

        assertTrue(result is ActiveRuleResolution.InvalidSelection)
    }

    @Test
    fun boundedRuleDoesNotRunWhenInstalledVersionCannotBeVerified() {
        val base = TestFixtures.builtInRule()
        val bounded = InstalledRule(
            "bounded",
            base.copy(target = base.target.copy(minVersionCode = 1)),
        )

        val result = RuleSelection.resolve(
            base.target.packageName,
            versionCode = null,
            rules = listOf(bounded),
            selectedKey = null,
        )

        assertSame(ActiveRuleResolution.NoRule, result)
    }
}
