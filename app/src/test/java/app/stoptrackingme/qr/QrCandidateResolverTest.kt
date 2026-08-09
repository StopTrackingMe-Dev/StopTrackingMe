package app.stoptrackingme.qr

import app.stoptrackingme.TestFixtures
import app.stoptrackingme.rules.InstalledRule
import app.stoptrackingme.rules.RuleSource
import app.stoptrackingme.rules.RuleSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCandidateResolverTest {
    private val bilibili = TestFixtures.builtInRule("bilibili")

    @Test
    fun acceptsOnlyCompleteHttpOrHttpsQrContent() {
        assertEquals(
            "https://www.bilibili.com/video/BV1?utm_source=x",
            QrCandidateResolver.strictWebUrl(
                " https://www.bilibili.com/video/BV1?utm_source=x ",
            ),
        )
        assertNull(QrCandidateResolver.strictWebUrl("复制 https://b23.tv/example"))
        assertNull(QrCandidateResolver.strictWebUrl("javascript:alert(1)"))
        assertNull(QrCandidateResolver.strictWebUrl("https://user@example.com/path"))
    }

    @Test
    fun keepsAllDetectionsAndMarksUnsupportedContentWithoutRules() {
        val detections = listOf(
            detection("https://www.bilibili.com/video/BV1"),
            detection("not a url"),
            detection("https://example.com/path"),
        )

        val candidates = QrCandidateResolver.resolve(
            detections,
            listOf(InstalledRule("bilibili", bilibili)),
        )

        assertEquals(3, candidates.size)
        assertEquals(1, candidates[0].ruleCandidates.size)
        assertTrue(candidates[1].ruleCandidates.isEmpty())
        assertTrue(candidates[2].ruleCandidates.isEmpty())
    }

    @Test
    fun overlappingRulesRemainAnExplicitQrRuleChoice() {
        val second = bilibili.copy(
            id = "second",
            source = RuleSource(RuleSourceKind.LOCAL, "second.json"),
        )

        val candidate = QrCandidateResolver.resolve(
            listOf(detection("https://b23.tv/example")),
            listOf(
                InstalledRule("built-in", bilibili),
                InstalledRule("local", second),
            ),
        ).single()

        assertEquals(
            listOf("built-in", "local"),
            candidate.ruleCandidates.map { it.installed.key },
        )
    }

    private fun detection(rawValue: String) = DetectedQrCode(
        rawValue = rawValue,
        cornerPoints = listOf(
            QrPoint(0f, 0f),
            QrPoint(100f, 0f),
            QrPoint(100f, 100f),
            QrPoint(0f, 100f),
        ),
        boundingBox = QrBounds(0f, 0f, 100f, 100f),
    )
}
