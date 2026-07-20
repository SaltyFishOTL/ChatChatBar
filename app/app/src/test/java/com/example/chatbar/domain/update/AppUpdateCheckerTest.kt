package com.example.chatbar.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {
    @Test
    fun `missing patch version matches release patch zero`() {
        assertEquals(0, compareReleaseVersions("v1.0.0", "1.0"))
        assertFalse(isReleaseVersionNewer("v1.0.0", "1.0"))
    }

    @Test
    fun `newer patch and minor releases are updates`() {
        assertTrue(isReleaseVersionNewer("1.0.1", "1.0.0"))
        assertTrue(isReleaseVersionNewer("v1.1.0", "1.0.9"))
    }

    @Test
    fun `older release is not update`() {
        assertFalse(isReleaseVersionNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun `release tag is extracted from github release url`() {
        assertEquals(
            "v1.0.1",
            releaseTagFromUrl("https://github.com/SaltyFishOTL/ChatChatBar/releases/tag/v1.0.1")
        )
    }

    @Test
    fun `non release tag url has no release tag`() {
        assertEquals(null, releaseTagFromUrl("https://github.com/SaltyFishOTL/ChatChatBar/releases/latest"))
    }

    @Test
    fun `release notes include stable releases between current and latest`() {
        val notes = releaseNotesBetween(
            releases = listOf(
                GitHubRelease(tagName = "v1.2.0", name = "1.2.0", body = "note 120", htmlUrl = "latest"),
                GitHubRelease(tagName = "v1.1.5", name = "1.1.5", body = "note 115", htmlUrl = "middle"),
                GitHubRelease(tagName = "v1.1.0", name = "1.1.0", body = "old", htmlUrl = "old"),
                GitHubRelease(tagName = "v1.3.0", name = "1.3.0", body = "future", htmlUrl = "future"),
                GitHubRelease(tagName = "v1.1.6-beta", name = "1.1.6 beta", body = "beta", prerelease = true)
            ),
            currentVersion = "1.1.0",
            latestVersion = "1.2.0"
        )

        assertEquals(listOf("v1.2.0", "v1.1.5"), notes.map { it.version })
        assertEquals(listOf("note 120", "note 115"), notes.map { it.body })
    }

    @Test
    fun `published signed apk asset is selected`() {
        val release = GitHubRelease(
            tagName = "v1.3.0",
            assets = listOf(
                GitHubReleaseAsset(
                    name = "ChatBar-1.3.0-unsigned.apk",
                    browserDownloadUrl = "https://example.invalid/unsigned.apk"
                ),
                GitHubReleaseAsset(
                    name = "ChatBar-1.3.0.apk",
                    browserDownloadUrl = "https://example.invalid/release.apk",
                    contentType = "application/vnd.android.package-archive",
                    size = 1234L
                )
            )
        )

        assertEquals(
            AppUpdateAsset(
                name = "ChatBar-1.3.0.apk",
                downloadUrl = "https://example.invalid/release.apk",
                sizeBytes = 1234L
            ),
            release.resolveApkAsset("owner", "repo")
        )
    }

    @Test
    fun `predictable workflow asset url is used when api asset data is unavailable`() {
        val asset = GitHubRelease(tagName = "v1.3.0").resolveApkAsset("owner", "repo")

        assertEquals("ChatBar-1.3.0.apk", asset?.name)
        assertEquals(
            "https://github.com/owner/repo/releases/download/v1.3.0/ChatBar-1.3.0.apk",
            asset?.downloadUrl
        )
    }
}
