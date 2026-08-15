package app.stoptrackingme.cache

import java.io.File
import java.util.Locale

internal data class CacheSnapshot(
    val sizeBytes: Long,
    val fileCount: Int,
) {
    companion object {
        val Empty = CacheSnapshot(sizeBytes = 0L, fileCount = 0)
    }
}

internal data class CacheClearResult(
    val before: CacheSnapshot,
    val after: CacheSnapshot,
) {
    val freedBytes: Long
        get() = (before.sizeBytes - after.sizeBytes).coerceAtLeast(0L)

    val isComplete: Boolean
        get() = after.fileCount == 0
}

/** Best-effort management for files under the app-owned cache directory. */
internal class AppCacheManager(
    private val cacheDirectory: File,
) {
    fun snapshot(): CacheSnapshot {
        var sizeBytes = 0L
        var fileCount = 0

        fun visit(file: File) {
            if (file.isDirectory) {
                file.listFiles().orEmpty().forEach(::visit)
            } else if (file.isFile) {
                val length = file.length().coerceAtLeast(0L)
                sizeBytes = if (Long.MAX_VALUE - sizeBytes < length) {
                    Long.MAX_VALUE
                } else {
                    sizeBytes + length
                }
                if (fileCount < Int.MAX_VALUE) fileCount += 1
            }
        }

        runCatching { visit(cacheDirectory) }
        return CacheSnapshot(sizeBytes = sizeBytes, fileCount = fileCount)
    }

    fun clear(): CacheClearResult {
        val before = snapshot()
        cacheDirectory.listFiles().orEmpty().forEach(::deleteTree)
        return CacheClearResult(before = before, after = snapshot())
    }

    private fun deleteTree(file: File) {
        if (file.isDirectory) {
            file.listFiles().orEmpty().forEach(::deleteTree)
        }
        runCatching { file.delete() }
    }
}

internal fun formatCacheSize(sizeBytes: Long): String {
    val normalized = sizeBytes.coerceAtLeast(0L).toDouble()
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = normalized
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }
    return if (unitIndex == 0) {
        "${value.toLong()} ${units[unitIndex]}"
    } else {
        String.format(Locale.ROOT, "%.1f %s", value, units[unitIndex])
    }
}
