package app.stoptrackingme

import app.stoptrackingme.rules.AppRule
import app.stoptrackingme.rules.RuleParser
import java.io.File

internal object TestFixtures {
    fun builtInRuleBytes(name: String = "bilibili"): ByteArray {
        val candidates = listOf(
            File("src/main/assets/rules/$name.json"),
            File("app/src/main/assets/rules/$name.json"),
        )
        return candidates.firstOrNull(File::isFile)?.readBytes()
            ?: error("找不到内置测试规则：$name")
    }

    fun builtInRule(name: String = "bilibili"): AppRule =
        RuleParser().parse(builtInRuleBytes(name)).rules.single()
}
