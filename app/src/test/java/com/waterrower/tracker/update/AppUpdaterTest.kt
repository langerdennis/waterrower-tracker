package com.waterrower.tracker.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {

    @Test
    fun isNewerVersion_comparesTwoAndThreePartSemverNumerically() {
        assertTrue(AppUpdater.isNewerVersion("0.4", "v0.4.1"))
        assertTrue(AppUpdater.isNewerVersion("0.4.1", "v0.5"))
        assertTrue(AppUpdater.isNewerVersion("0.9", "v0.10"))
        assertTrue(AppUpdater.isNewerVersion("0.4.1-dev.25", "v0.4.2"))
        assertTrue(AppUpdater.isNewerVersion("0.4.1", "v1.0.0"))

        assertFalse(AppUpdater.isNewerVersion("0.4.1", "v0.4.1"))
        assertFalse(AppUpdater.isNewerVersion("0.4.1", "v0.4"))
        assertFalse(AppUpdater.isNewerVersion("0.4.1-dev.25", "v0.4.1"))
        assertFalse(AppUpdater.isNewerVersion("0.5.0", "v0.4.9"))
        assertFalse(AppUpdater.isNewerVersion("0.4.1", ""))
    }

    @Test
    fun parseLatestReleaseJson_selectsSignedReleaseApkOverDebugApk() {
        val json = """
            {
              "tag_name": "v0.5",
              "name": "v0.5 — WaterRower",
              "body": "## What's Changed\n* New feature",
              "draft": false,
              "prerelease": false,
              "html_url": "https://github.com/langerdennis/waterrower-tracker/releases/tag/v0.5",
              "assets": [
                {
                  "name": "waterrower-tracker-v0.5-debug.apk",
                  "size": 50000000,
                  "browser_download_url": "https://github.com/langerdennis/waterrower-tracker/releases/download/v0.5/waterrower-tracker-v0.5-debug.apk"
                },
                {
                  "name": "waterrower-tracker-v0.5.apk",
                  "size": 41816504,
                  "browser_download_url": "https://github.com/langerdennis/waterrower-tracker/releases/download/v0.5/waterrower-tracker-v0.5.apk"
                }
              ]
            }
        """.trimIndent()

        val release = AppUpdater.parseLatestReleaseJson(json)
        assertNotNull(release)
        assertEquals("v0.5", release!!.tagName)
        assertEquals("v0.5 — WaterRower", release.title)
        assertEquals("waterrower-tracker-v0.5.apk", release.apkName)
        assertEquals(41816504L, release.apkSizeBytes)
        assertTrue(release.notes.contains("New feature"))
    }

    @Test
    fun parseLatestReleaseJson_rejectsDraftPrereleaseAndUntrustedUrls() {
        val draftJson = """
            {
              "tag_name": "v0.6",
              "draft": true,
              "prerelease": false,
              "assets": [
                {
                  "name": "app.apk",
                  "size": 1000,
                  "browser_download_url": "https://github.com/langerdennis/waterrower-tracker/releases/download/v0.6/app.apk"
                }
              ]
            }
        """.trimIndent()
        assertNull(AppUpdater.parseLatestReleaseJson(draftJson))

        val badHostJson = """
            {
              "tag_name": "v0.6",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "app.apk",
                  "size": 1000,
                  "browser_download_url": "https://evil.example.com/app.apk"
                }
              ]
            }
        """.trimIndent()
        assertNull(AppUpdater.parseLatestReleaseJson(badHostJson))

        assertTrue(AppUpdater.isAllowedDownloadUrl("https://github.com/langerdennis/waterrower-tracker/releases/download/v0.4.1/a.apk"))
        assertTrue(AppUpdater.isAllowedDownloadUrl("https://objects.githubusercontent.com/some/path"))
        assertFalse(AppUpdater.isAllowedDownloadUrl("http://github.com/insecure.apk"))
        assertFalse(AppUpdater.isAllowedDownloadUrl("https://attacker.com/fake.apk"))
    }
}
