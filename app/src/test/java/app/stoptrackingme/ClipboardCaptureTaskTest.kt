package app.stoptrackingme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClipboardCaptureTaskTest {
    @Test
    fun `transparent clipboard capture cannot reuse main app task`() {
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).firstOrNull(File::isFile)?.readText() ?: error("找不到 AndroidManifest.xml")
        val activity = Regex(
            """<activity\s+[^>]*android:name="\.ClipboardCaptureActivity"[^>]*/>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(manifest)?.value ?: error("找不到 ClipboardCaptureActivity 声明")

        assertTrue(activity.contains("android:noHistory=\"true\""))
        assertTrue(activity.contains("android:excludeFromRecents=\"true\""))
        assertTrue(activity.contains("android:taskAffinity=\"\""))
    }

    @Test
    fun `focus bridge removal is deferred outside EMUI focus callback`() {
        val source = listOf(
            File("src/main/java/app/stoptrackingme/ShareAccessibilityService.kt"),
            File("app/src/main/java/app/stoptrackingme/ShareAccessibilityService.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("找不到 ShareAccessibilityService.kt")
        val removal = Regex(
            """private fun removeClipboardFocusBridge\(\).*?\n    }\n\n    /\*\*""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(source)?.value ?: error("找不到剪贴板焦点桥接窗口的移除逻辑")

        assertTrue(source.contains("handler.post { beginClipboardFocusRead(sessionId, view) }"))
        assertTrue(removal.contains("removeView(view)"))
        assertFalse(removal.contains("removeViewImmediate(view)"))
    }
}
