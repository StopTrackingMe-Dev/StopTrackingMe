package app.stoptrackingme.rules

import org.junit.Assert.assertEquals
import org.junit.Test

class SourcePriorityTest {
    private val remoteA = RuleSource(RuleSourceKind.REMOTE, "https://a.example/rules.json")
    private val remoteB = RuleSource(RuleSourceKind.REMOTE, "https://b.example/rules.json")
    private val local = RuleSource(RuleSourceKind.LOCAL, "local.json")

    @Test
    fun savedOrderIsKeptAndNewSourcesAreAppended() {
        val ordered = RuleSelection.orderSources(
            saved = listOf(RuleSelection.sourceId(local), RuleSelection.sourceId(remoteB)),
            present = listOf(remoteA, remoteB, local),
        )

        assertEquals(listOf(local, remoteB, remoteA), ordered)
    }

    @Test
    fun withoutSavedOrderSubscriptionsComeBeforeLocalImports() {
        val ordered = RuleSelection.orderSources(
            saved = emptyList(),
            present = listOf(local, remoteA),
        )

        assertEquals(listOf(remoteA, local), ordered)
    }

    @Test
    fun removedSourcesAreDroppedFromSavedOrder() {
        val ordered = RuleSelection.orderSources(
            saved = listOf(RuleSelection.sourceId(remoteB), RuleSelection.sourceId(remoteA)),
            present = listOf(remoteA),
        )

        assertEquals(listOf(remoteA), ordered)
    }
}
