package app.stoptrackingme.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateMirrorUrlTest {
    @Test
    fun changingVariantOnlyChangesTheMirrorFileName() {
        val base =
            "https://1813680010.cdn.123clouddisk.com/1813680010/s/StopTrackingMe/app-release.apk"

        assertEquals(
            "https://1813680010.cdn.123clouddisk.com/1813680010/s/StopTrackingMe/app-minimal-universal-release.apk",
            mirrorUrlWithFileName(base, "app-minimal-universal-release.apk"),
        )
    }

    @Test
    fun preservesEncodedDirectoryAndQueryWhenChangingFileName() {
        val base = "https://mirror.example/%E5%9B%BD%E5%86%85/releases/app-release.apk?token=a%2Fb"

        assertEquals(
            "https://mirror.example/%E5%9B%BD%E5%86%85/releases/app-full-arm64-v8a-release.apk?token=a%2Fb",
            mirrorUrlWithFileName(base, "app-full-arm64-v8a-release.apk"),
        )
    }

    @Test
    fun defaultMirrorAlsoServesAbiSpecificAssets() {
        val asset = asset(fileName = "app-minimal-arm64-v8a-release.apk", targetAbi = "arm64-v8a")

        assertEquals(
            "https://mirror.example/releases/app-minimal-arm64-v8a-release.apk",
            resolvedMirrorUrl(asset, "https://mirror.example/releases/app-release.apk"),
        )
        assertNull(resolvedMirrorUrl(asset, ""))
    }

    private fun asset(fileName: String, targetAbi: String?) = AppUpdateAsset(
        fileName = fileName,
        targetAbi = targetAbi,
        githubUrl = "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v1/$fileName",
        mirrorUrl = null,
        sizeBytes = 1_024,
        sha256 = "a".repeat(64),
        variant = AppVariant.MINIMAL,
    )
}
