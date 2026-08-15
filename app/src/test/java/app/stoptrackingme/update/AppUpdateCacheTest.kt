package app.stoptrackingme.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AppUpdateCacheTest {
    @Test
    fun preparingDownloadKeepsOnlyTheCurrentCompleteApk() {
        val directory = Files.createTempDirectory("stoptracking-update-cache-test").toFile()
        try {
            val current = directory.resolve("StopTrackingMe-v2.apk").apply { writeText("current") }
            val old = directory.resolve("StopTrackingMe-v1.apk").apply { writeText("old") }
            val partial = directory.resolve("StopTrackingMe-v2.apk.part").apply { writeText("part") }

            AppUpdateCache.prepareForDownload(directory, current.name)

            assertTrue(current.isFile)
            assertFalse(old.exists())
            assertFalse(partial.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
