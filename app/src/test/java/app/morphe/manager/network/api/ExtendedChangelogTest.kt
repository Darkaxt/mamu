package app.morphe.manager.network.api

import app.morphe.manager.network.dto.GitHubAsset
import app.morphe.manager.network.dto.GitHubRelease
import kotlin.test.Test
import kotlin.test.assertEquals

class ExtendedChangelogTest {
    @Test fun `revision suffix is retained for a stable private release`() {
        val stable = GitHubRelease(
            tagName = "v1.33.0-extended.2",
            body = "Native APK downloads",
            publishedAt = "2026-10-04T12:00:00Z",
            assets = listOf(GitHubAsset(
                name = "Mamu-1.33.0-extended.2.apk",
                downloadUrl = "https://github.com/Darkaxt/mamu/releases/download/v1.33.0-extended.2/Mamu-1.33.0-extended.2.apk"
            ))
        )
        val entries = extendedManagerChangelog(listOf(
            stable.copy(draft = true), stable.copy(prerelease = true),
            stable, stable.copy(assets = emptyList())
        ))
        assertEquals(1, entries.size)
        assertEquals("1.33.0-extended.2", entries.single().version)
        assertEquals("2026-10-04", entries.single().date)
        assertEquals("Native APK downloads", entries.single().content)
    }

    private fun release(version: String) = GitHubRelease(
        tagName = "v$version", publishedAt = "2026-10-16T20:34:46Z",
        assets = listOf(GitHubAsset("Mamu-$version.apk",
            "https://github.com/Darkaxt/mamu/releases/download/v$version/Mamu-$version.apk")))

    @Test fun `live GitHub lexical ordering selects ten over nine and sorts changelog`() {
        val releases = listOf("1.35.0.9", "1.35.0.8", "1.35.0.7", "1.35.0.10").map(::release)
        val expected = listOf("1.35.0.10", "1.35.0.9", "1.35.0.8", "1.35.0.7")
        for (input in listOf(releases, releases.reversed(), releases.drop(2) + releases.take(2))) {
            assertEquals(expected.map { "v$it" }, orderedExtendedManagerReleases(input).map { it.tagName })
            assertEquals(expected, extendedManagerChangelog(input).map { it.version })
        }
    }

    @Test fun `ineligible entries cannot displace stable numeric or legacy updates`() {
        val stable = release("1.35.0.10")
        val legacy = release("1.35.0-extended.9")
        val future = release("1.36.0.1")
        val inputs = listOf(future.copy(draft = true), future.copy(prerelease = true),
            future.copy(assets = emptyList()), future.copy(assets = listOf(GitHubAsset("foreign.apk",
                "https://github.com/Other/repo/releases/download/v1.36.0.1/foreign.apk"))), legacy, stable)
        assertEquals(listOf(stable, legacy), orderedExtendedManagerReleases(inputs))
    }

    @Test fun `malformed matching stable version fails discovery and changelog`() {
        val invalid = release("broken")
        kotlin.test.assertFailsWith<java.io.IOException> { orderedExtendedManagerReleases(listOf(invalid)) }
        kotlin.test.assertFailsWith<java.io.IOException> { extendedManagerChangelog(listOf(invalid)) }
    }
}
