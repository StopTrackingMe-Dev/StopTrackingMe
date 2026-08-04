package app.stoptrackingme.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManifestParserTest {
    @Test
    fun parsesGithubReleaseJsonAndPrefersCanonicalApk() {
        val release = AppUpdateManifestParser.parse(
            """
            {
              "html_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/tag/v0.1.3-alpha",
              "tag_name": "v0.1.3-alpha",
              "name": "v0.1.3-alpha Release",
              "prerelease": true,
              "published_at": "2026-08-04T00:00:00Z",
              "assets": [
                {
                  "name": "notes.txt",
                  "size": 100,
                  "browser_download_url": "https://github.com/example/notes.txt"
                },
                {
                  "name": "app-release.apk",
                  "size": 8620093,
                  "digest": "sha256:9fe90b9850946703f9df6c6aae4ef94af33b3477b5743c87a148a273c69b33dd",
                  "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v0.1.3-alpha/app-release.apk"
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("v0.1.3-alpha", release.tagName)
        assertEquals("0.1.3-alpha", release.versionName)
        assertNull(release.versionCode)
        assertTrue(release.prerelease)
        assertEquals("app-release.apk", release.asset.fileName)
        assertEquals(8620093L, release.asset.sizeBytes)
        assertEquals(
            "9fe90b9850946703f9df6c6aae4ef94af33b3477b5743c87a148a273c69b33dd",
            release.asset.sha256,
        )
    }

    @Test
    fun parsesNormalizedManifestFields() {
        val release = AppUpdateManifestParser.parse(
            """
            {
              "tagName": "v1.0.0",
              "versionName": "1.0.0",
              "versionCode": 20,
              "releaseUrl": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/tag/v1.0.0",
              "githubUrl": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v1.0.0/app-release.apk",
              "mirrorUrl": "https://1813680010.cdn.123clouddisk.com/1813680010/s/StopTrackingMe/v1.0.0/app-release.apk",
              "fileName": "app-release.apk",
              "sizeBytes": 1000000,
              "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            }
            """.trimIndent(),
        )

        assertEquals(20L, release.versionCode)
        assertFalse(release.prerelease)
        assertEquals(
            "https://1813680010.cdn.123clouddisk.com/1813680010/s/StopTrackingMe/v1.0.0/app-release.apk",
            release.asset.mirrorUrl,
        )
    }

    @Test
    fun rejectsApkWithoutSha256() {
        assertThrows(AppUpdateException::class.java) {
            AppUpdateManifestParser.parse(
                """
                {
                  "tag_name": "v1.0.0",
                  "assets": [{
                    "name": "app-release.apk",
                    "size": 1000000,
                    "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v1.0.0/app-release.apk"
                  }]
                }
                """.trimIndent(),
            )
        }
    }
}
