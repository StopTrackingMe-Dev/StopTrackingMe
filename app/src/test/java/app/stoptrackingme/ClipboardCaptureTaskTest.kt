package app.stoptrackingme

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
}
