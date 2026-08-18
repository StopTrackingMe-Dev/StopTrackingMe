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
    fun selectsFirstCompatibleAbiAsset() {
        val release = AppUpdateManifestParser.parse(
            """
            {
              "tag_name": "v1.0.0",
              "assets": [
                {
                  "name": "app-universal-release.apk",
                  "size": 30000000,
                  "digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v1.0.0/app-universal-release.apk"
                },
                {
                  "name": "app-armeabi-v7a-release.apk",
                  "size": 14000000,
                  "digest": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v1.0.0/app-armeabi-v7a-release.apk"
                },
                {
                  "name": "app-arm64-v8a-release.apk",
                  "size": 16000000,
                  "digest": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                  "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v1.0.0/app-arm64-v8a-release.apk"
                }
              ]
            }
            """.trimIndent(),
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )

        assertEquals("app-arm64-v8a-release.apk", release.asset.fileName)
        assertEquals("arm64-v8a", release.asset.targetAbi)
        assertEquals(16000000L, release.asset.sizeBytes)
    }

    @Test
    fun distinguishesX86_64FromX86AssetNames() {
        val release = AppUpdateManifestParser.parse(
            """
            {
              "tag_name": "v1.0.0",
              "assets": [
                {
                  "name": "app-x86-release.apk",
                  "size": 16000000,
                  "digest": "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                  "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v1.0.0/app-x86-release.apk"
                },
                {
                  "name": "app-x86_64-release.apk",
                  "size": 17000000,
                  "digest": "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                  "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v1.0.0/app-x86_64-release.apk"
                }
              ]
            }
            """.trimIndent(),
            supportedAbis = listOf("x86_64", "x86"),
        )

        assertEquals("app-x86_64-release.apk", release.asset.fileName)
        assertEquals("x86_64", release.asset.targetAbi)
    }

    @Test
    fun usesNextCompatibleAbiBeforeUniversalFallback() {
        val release = AppUpdateManifestParser.parse(
            """
            {
              "tag_name": "v1.0.0",
              "assets": [
                {
                  "name": "app-universal-release.apk",
                  "size": 30000000,
                  "digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v1.0.0/app-universal-release.apk"
                },
                {
                  "name": "app-armeabi-v7a-release.apk",
                  "size": 14000000,
                  "digest": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                  "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v1.0.0/app-armeabi-v7a-release.apk"
                }
              ]
            }
            """.trimIndent(),
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a"),
        )

        assertEquals("app-armeabi-v7a-release.apk", release.asset.fileName)
        assertEquals("armeabi-v7a", release.asset.targetAbi)
    }

    @Test
    fun fallsBackToUniversalApkWhenNoAbiAssetMatches() {
        val release = AppUpdateManifestParser.parse(
            """
            {
              "tag_name": "v1.0.0",
              "assets": [
                {
                  "name": "app-arm64-v8a-release.apk",
                  "size": 16000000,
                  "digest": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                  "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v1.0.0/app-arm64-v8a-release.apk"
                },
                {
                  "name": "app-universal-release.apk",
                  "size": 30000000,
                  "digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v1.0.0/app-universal-release.apk"
                }
              ]
            }
            """.trimIndent(),
            supportedAbis = listOf("x86_64", "x86"),
        )

        assertEquals("app-universal-release.apk", release.asset.fileName)
        assertNull(release.asset.targetAbi)
    }

    @Test
    fun rejectsReleaseWithoutCompatibleOrUniversalApk() {
        assertThrows(AppUpdateException::class.java) {
            AppUpdateManifestParser.parse(
                """
                {
                  "tag_name": "v1.0.0",
                  "assets": [
                    {
                      "name": "app-arm64-v8a-release.apk",
                      "size": 16000000,
                      "digest": "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                      "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v1.0.0/app-arm64-v8a-release.apk"
                    },
                    {
                      "name": "app-armeabi-v7a-release.apk",
                      "size": 14000000,
                      "digest": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                      "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v1.0.0/app-armeabi-v7a-release.apk"
                    }
                  ]
                }
                """.trimIndent(),
                supportedAbis = listOf("x86_64", "x86"),
            )
        }
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
    fun selectsTheRequestedVariantBeforeChoosingAnAbi() {
        val release = AppUpdateManifestParser.parse(
            """
            {
              "tag_name": "v2.0.0",
              "versionCode": 20,
              "mirrorUrl": "https://1813680010.cdn.123clouddisk.com/1813680010/s/StopTrackingMe/app-release.apk",
              "assets": [
                {
                  "name": "app-full-universal-release.apk",
                  "variant": "full",
                  "size": 30000000,
                  "digest": "sha256:${"a".repeat(64)}",
                  "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v2.0.0/app-full-universal-release.apk"
                },
                {
                  "name": "app-minimal-universal-release.apk",
                  "variant": "minimal",
                  "size": 10000000,
                  "digest": "sha256:${"b".repeat(64)}",
                  "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v2.0.0/app-minimal-universal-release.apk"
                }
              ]
            }
            """.trimIndent(),
            requestedVariant = AppVariant.MINIMAL,
        )

        assertEquals(AppVariant.MINIMAL, release.variant)
        assertEquals("app-minimal-universal-release.apk", release.asset.fileName)
        assertNull(release.asset.targetAbi)
    }

    @Test
    fun doesNotFallBackToFullWhenMinimalAssetIsMissing() {
        assertThrows(AppUpdateException::class.java) {
            AppUpdateManifestParser.parse(
                """
                {
                  "tag_name": "v2.0.0",
                  "assets": [{
                    "name": "app-full-universal-release.apk",
                    "size": 30000000,
                    "digest": "sha256:${"a".repeat(64)}",
                    "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v2.0.0/app-full-universal-release.apk"
                  }]
                }
                """.trimIndent(),
                requestedVariant = AppVariant.MINIMAL,
            )
        }
    }

    @Test
    fun rootVariantAndMirrorApplyToGenericAbiAssets() {
        val release = AppUpdateManifestParser.parse(
            """
            {
              "tagName": "v2.0.0",
              "variant": "minimal",
              "mirrorUrl": "https://mirror.example/releases/app-release.apk",
              "assets": [{
                "name": "app-arm64-v8a-release.apk",
                "abi": "arm64-v8a",
                "size": 10000000,
                "digest": "sha256:${"a".repeat(64)}",
                "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v2.0.0/app-arm64-v8a-release.apk"
              }]
            }
            """.trimIndent(),
            requestedVariant = AppVariant.MINIMAL,
            supportedAbis = listOf("arm64-v8a"),
        )

        assertEquals(AppVariant.MINIMAL, release.variant)
        assertEquals("arm64-v8a", release.asset.targetAbi)
        assertEquals(
            "https://mirror.example/releases/app-release.apk",
            release.asset.mirrorUrl,
        )
    }

    @Test
    fun rejectsInvalidExplicitVariantOrAbi() {
        val invalidVariant = """
            {
              "tag_name": "v2.0.0",
              "assets": [{
                "name": "app-full-universal-release.apk",
                "variant": "lite",
                "size": 10000000,
                "digest": "sha256:${"a".repeat(64)}",
                "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v2.0.0/app-full-universal-release.apk"
              }]
            }
        """.trimIndent()
        val invalidAbi = """
            {
              "tag_name": "v2.0.0",
              "assets": [{
                "name": "app-full-universal-release.apk",
                "variant": "full",
                "abi": "mips",
                "size": 10000000,
                "digest": "sha256:${"a".repeat(64)}",
                "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v2.0.0/app-full-universal-release.apk"
              }]
            }
        """.trimIndent()

        assertThrows(AppUpdateException::class.java) {
            AppUpdateManifestParser.parse(invalidVariant)
        }
        assertThrows(AppUpdateException::class.java) {
            AppUpdateManifestParser.parse(invalidAbi)
        }
    }

    @Test
    fun rejectsNonPositiveVersionCode() {
        assertThrows(AppUpdateException::class.java) {
            AppUpdateManifestParser.parse(
                """
                {
                  "tag_name": "v2.0.0",
                  "versionCode": 0,
                  "assets": [{
                    "name": "app-release.apk",
                    "size": 10000000,
                    "digest": "sha256:${"a".repeat(64)}",
                    "browser_download_url": "https://github.com/StopTrackingMe-Dev/StopTrackingMe/releases/download/v2.0.0/app-release.apk"
                  }]
                }
                """.trimIndent(),
            )
        }
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
