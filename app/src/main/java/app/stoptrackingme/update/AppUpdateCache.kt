package app.stoptrackingme.update

import java.io.File

internal object AppUpdateCache {
    const val DIRECTORY_NAME = "updates"

    fun directory(cacheDirectory: File): File = File(cacheDirectory, DIRECTORY_NAME)

    /** Keeps only a reusable, fully downloaded APK for the release about to be fetched. */
    fun prepareForDownload(updateDirectory: File, targetFileName: String) {
        updateDirectory.listFiles().orEmpty().forEach { file ->
            if (file.name != targetFileName && !deleteTree(file)) {
                throw AppUpdateException("无法清理旧的更新缓存")
            }
        }
    }

    fun clear(cacheDirectory: File) {
        val updateDirectory = directory(cacheDirectory)
        updateDirectory.listFiles().orEmpty().forEach { file -> deleteTree(file) }
        runCatching { updateDirectory.delete() }
    }

    private fun deleteTree(file: File): Boolean {
        var deleted = true
        if (file.isDirectory) {
            file.listFiles().orEmpty().forEach { child ->
                if (!deleteTree(child)) deleted = false
            }
        }
        return runCatching { (!file.exists() || file.delete()) && deleted }.getOrDefault(false)
    }
}
