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
    fun `atom fallback parses release notes when github api is unavailable`() {
        val releases = parseGitHubReleasesAtom(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>tag:github.com,2008:Repository/1/v1.3.5</id>
                <link rel="alternate" type="text/html"
                    href="https://github.com/owner/repo/releases/tag/v1.3.5"/>
                <title>ChatBar 1.3.5</title>
                <content type="html">&lt;h2&gt;更新内容&lt;/h2&gt;&lt;ul&gt;&lt;li&gt;支持参考文档 &amp;amp; 图片&lt;/li&gt;&lt;li&gt;输出更稳定&lt;/li&gt;&lt;/ul&gt;</content>
              </entry>
              <entry>
                <id>tag:github.com,2008:Repository/1/v1.3.4</id>
                <link rel="alternate" type="text/html"
                    href="https://github.com/owner/repo/releases/tag/v1.3.4"/>
                <title>ChatBar 1.3.4</title>
                <content type="html">&lt;p&gt;旧版本更新&lt;/p&gt;</content>
              </entry>
            </feed>
            """.trimIndent()
        )

        assertEquals(listOf("v1.3.5", "v1.3.4"), releases.map { it.tagName })
        assertEquals("ChatBar 1.3.5", releases.first().name)
        assertEquals(
            "更新内容\n- 支持参考文档 & 图片\n- 输出更稳定",
            releases.first().body
        )
    }

    @Test
    fun `atom prerelease entries do not become stable release notes`() {
        val releases = parseGitHubReleasesAtom(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <entry>
                <id>tag:github.com,2008:Repository/1/v1.4.0-beta</id>
                <link rel="alternate" type="text/html"
                    href="https://github.com/owner/repo/releases/tag/v1.4.0-beta"/>
                <title>ChatBar 1.4.0 beta</title>
                <content type="html">&lt;p&gt;测试版本&lt;/p&gt;</content>
              </entry>
              <entry>
                <id>tag:github.com,2008:Repository/1/v1.3.5</id>
                <link rel="alternate" type="text/html"
                    href="https://github.com/owner/repo/releases/tag/v1.3.5"/>
                <title>ChatBar 1.3.5</title>
                <content type="html">&lt;p&gt;正式版本&lt;/p&gt;</content>
              </entry>
            </feed>
            """.trimIndent()
        )

        val notes = releaseNotesBetween(
            releases = releases,
            currentVersion = "1.3.4",
            latestVersion = "1.3.5"
        )

        assertEquals(listOf("v1.3.5"), notes.map { it.version })
        assertEquals(listOf("正式版本"), notes.map { it.body })
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
