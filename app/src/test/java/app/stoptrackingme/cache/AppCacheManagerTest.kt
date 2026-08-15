package app.stoptrackingme.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AppCacheManagerTest {
    @Test
    fun measuresNestedFilesAndClearsTheWholeCache() {
        val directory = Files.createTempDirectory("stoptracking-cache-test").toFile()
        try {
            directory.resolve("root.bin").writeBytes(ByteArray(3))
            directory.resolve("nested").mkdirs()
            directory.resolve("nested/child.bin").writeBytes(ByteArray(5))
            val manager = AppCacheManager(directory)

            assertEquals(CacheSnapshot(sizeBytes = 8L, fileCount = 2), manager.snapshot())

            val result = manager.clear()

            assertEquals(8L, result.freedBytes)
            assertTrue(result.isComplete)
            assertEquals(CacheSnapshot.Empty, result.after)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun formatsCacheSizesForTheSettingsCard() {
        assertEquals("0 B", formatCacheSize(0L))
        assertEquals("1.0 KB", formatCacheSize(1024L))
        assertEquals("1.5 MB", formatCacheSize(1_572_864L))
    }
}
