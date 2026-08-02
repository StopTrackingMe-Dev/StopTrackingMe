package app.stoptrackingme.rules

import app.stoptrackingme.TestFixtures
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class RuleParserTest {
    private val parser = RuleParser()

    @Test
    fun parsesBuiltInVersionedRule() {
        val bundle = parser.parse(TestFixtures.builtInRuleBytes())

        assertEquals(1, bundle.schemaVersion)
        assertEquals(1, bundle.rules.size)
        val rule = bundle.rules.single()
        assertEquals(RuleSourceKind.BUILTIN, rule.source.kind)
        assertTrue(rule.shareTriggerSelectors.isNotEmpty())
        assertTrue(rule.sharePanelFingerprint.size >= 2)
        assertEquals(5, rule.redirectPolicy.maxRedirects)
        assertTrue(rule.sharePreview?.titleSelectors?.isNotEmpty() == true)
        assertTrue("hdslb.com" in rule.sharePreview.orEmptyImageHosts())
        assertTrue("p" in rule.cleaningPolicy.forceKeep)
        assertTrue("t" in rule.cleaningPolicy.forceKeep)
    }

    @Test
    fun externalSourceCannotClaimToBeBuiltIn() {
        val source = RuleSource(RuleSourceKind.REMOTE, "https://rules.example/rules.json")
        val bundle = parser.parse(TestFixtures.builtInRuleBytes(), source)

        assertEquals(source, bundle.rules.single().source)
    }

    @Test
    fun rejectsDangerousCapabilityField() {
        val bytes = mutate { root ->
            root.getAsJsonArray("rules")[0].asJsonObject.addProperty("script", "click()")
        }

        val error = assertThrows(RuleValidationException::class.java) {
            parser.parse(bytes)
        }
        assertTrue(error.message.orEmpty().contains("不允许"))
    }

    @Test
    fun rejectsRegexUnsupportedByLinearEngine() {
        val bytes = mutate { root ->
            root.getAsJsonArray("rules")[0].asJsonObject
                .getAsJsonObject("clipboardExtraction")
                .addProperty("urlRegex", "(?=https)https://.+")
        }

        assertThrows(RuleValidationException::class.java) {
            parser.parse(bytes)
        }
    }

    @Test
    fun rejectsOversizedBundle() {
        assertThrows(RuleValidationException::class.java) {
            parser.parse(ByteArray(RuleParser.MAX_BUNDLE_BYTES + 1))
        }
    }

    @Test
    fun rejectsRedirectLimitAboveCodeLimit() {
        val bytes = mutate { root ->
            root.getAsJsonArray("rules")[0].asJsonObject
                .getAsJsonObject("redirectPolicy")
                .addProperty("maxRedirects", 6)
        }

        assertThrows(RuleValidationException::class.java) {
            parser.parse(bytes)
        }
    }

    @Test
    fun rejectsUnsupportedPreviewSelectorType() {
        val bytes = mutate { root ->
            val preview = root.getAsJsonArray("rules")[0].asJsonObject
                .getAsJsonObject("sharePreview")
            preview.getAsJsonArray("titleSelectors")[0].asJsonObject
                .addProperty("type", "CSS_SELECTOR")
        }

        assertThrows(RuleValidationException::class.java) {
            parser.parse(bytes)
        }
    }

    @Test
    fun rejectsUnknownTopLevelField() {
        val bytes = mutate { root -> root.addProperty("actions", "anything") }

        assertThrows(RuleValidationException::class.java) {
            parser.parse(bytes)
        }
    }

    @Test
    fun rejectsDuplicateJsonKeys() {
        val original = String(TestFixtures.builtInRuleBytes(), StandardCharsets.UTF_8)
        val duplicate = original.replaceFirst(
            "\"schemaVersion\": 1",
            "\"schemaVersion\": 1, \"schemaVersion\": 1",
        )

        assertThrows(RuleValidationException::class.java) {
            parser.parse(duplicate.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun mutate(change: (com.google.gson.JsonObject) -> Unit): ByteArray {
        val root = JsonParser.parseString(
            String(TestFixtures.builtInRuleBytes(), StandardCharsets.UTF_8),
        ).asJsonObject
        change(root)
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun SharePreviewRule?.orEmptyImageHosts(): Set<String> = this?.imageAllowedHosts.orEmpty()
}
