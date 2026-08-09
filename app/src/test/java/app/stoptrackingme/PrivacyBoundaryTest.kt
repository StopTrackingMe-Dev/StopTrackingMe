package app.stoptrackingme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PrivacyBoundaryTest {
    @Test
    fun productionKotlinHasNoTargetSpecificApplicationConstants() {
        val text = mainSourceDirectory().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        listOf(
            "tv.danmaku.bili",
            "b23.tv",
            "frame_share",
            "spm_id_from",
            "vd_source",
            "com.xingin.xhs",
            "xhslink.cn",
            "xhslink.com",
            "moreOperateIV",
            "xsec_source",
            "shareRedId",
        ).forEach { forbidden ->
            assertFalse("$forbidden 应只存在于 JSON 规则中", text.contains(forbidden))
        }
    }

    @Test
    fun productionCodeDoesNotPersistOrLogCapturedLinks() {
        val text = mainSourceDirectory().walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertFalse(text.contains("KEY_LAST_LINK"))
        assertFalse(text.contains("Captured link"))
        assertFalse(
            Regex("""putString\s*\([^)]*(originalUrl|cleanedUrl|sourceText)""")
                .containsMatchIn(text),
        )
        assertTrue(text.contains("ShareSessionStore"))
    }

    @Test
    fun usageReportingCannotReadShareContentOrAccessibilityData() {
        val usageDirectory = File(mainSourceDirectory(), "app/stoptrackingme/usage")
        val text = usageDirectory.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        listOf(
            "ShareSessionStore",
            "ClipboardManager",
            "AccessibilityNodeInfo",
            "Intent.EXTRA_TEXT",
            "originalUrl",
            "cleanedUrl",
            "sourceText",
            "sourcePackage",
        ).forEach { forbidden ->
            assertFalse("使用统计代码不得读取或接收分享内容：$forbidden", text.contains(forbidden))
        }
        assertTrue(text.contains("shareCount"))
        assertTrue(text.contains("installationCode"))
    }

    @Test
    fun qrImageContentIsNeverLoggedOrWrittenToPreferences() {
        val qrDirectory = File(mainSourceDirectory(), "app/stoptrackingme/qr")
        val activity = File(mainSourceDirectory(), "app/stoptrackingme/QrImageActivity.kt")
        val text = buildString {
            qrDirectory.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { appendLine(it.readText()) }
            append(activity.readText())
        }

        assertFalse(text.contains("android.util.Log"))
        assertFalse(text.contains("getSharedPreferences"))
        assertFalse(
            Regex("""putString\s*\([^)]*(rawValue|cleanedUrl|sourceText)""")
                .containsMatchIn(text),
        )
    }

    private fun mainSourceDirectory(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
        )
        return candidates.firstOrNull(File::isDirectory)
            ?: error("找不到生产源码目录")
    }
}
