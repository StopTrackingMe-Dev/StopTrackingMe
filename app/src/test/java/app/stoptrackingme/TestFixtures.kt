package app.stoptrackingme

import app.stoptrackingme.rules.AppRule
import app.stoptrackingme.rules.RuleParser

internal object TestFixtures {
    fun builtInRuleBytes(name: String = "bilibili"): ByteArray {
        return TestFixtures::class.java.getResourceAsStream("/$name.json")?.use { it.readBytes() }
            ?: error("找不到外置测试规则：$name")
    }

    fun builtInRule(name: String = "bilibili"): AppRule =
        RuleParser().parse(builtInRuleBytes(name)).rules.single()
}
