package io.motohub.android.feature.update

import org.junit.Assert.assertTrue
import org.junit.Test

class GithubUpdateRepositoryTest {
    @Test
    fun `parses stable and prerelease versions in semver order`() {
        val beta = GithubUpdateRepository.parseAppVersion("0.9.0-beta.10")
        val nextBeta = GithubUpdateRepository.parseAppVersion("0.9.0-beta.11")
        val stable = GithubUpdateRepository.parseAppVersion("0.9.0")

        assertTrue(beta != null)
        assertTrue(nextBeta != null)
        assertTrue(stable != null)
        assertTrue(nextBeta!! > beta!!)
        assertTrue(stable!! > nextBeta)
    }

    @Test
    fun `selects only the latest apk release newer than installed version`() {
        val older = release("v0.9.0-beta.9", hasApk = true)
        val current = release("v0.9.0-beta.10", hasApk = true)
        val newerPrerelease = release("v0.9.0-beta.11", hasApk = true)
        val newest = release("v0.9.0-beta.12", hasApk = true)
        val newerWithoutApk = release("v0.9.0-beta.13", hasApk = false)

        val selected = latestNewerApkRelease(
            listOf(older, current, newerPrerelease, newest, newerWithoutApk),
            installedVersion = "0.9.0-beta.10"
        )

        assertTrue(selected?.tagName == "v0.9.0-beta.12")
    }

    @Test
    fun `does not offer the same build when tag suffix differs from installed version`() {
        val release = release("v0.9.0-beta.10-build.71-r1", hasApk = true)

        assertTrue(
            !release.isNewerThan(
                installedVersion = "0.9.0-beta.10-build.71",
                installedVersionCode = 71
            )
        )
    }

    @Test
    fun `uses build number when installed version name is stale`() {
        val release = release("v0.9.0-beta.10-build.72-r1", hasApk = true)

        assertTrue(
            release.isNewerThan(
                installedVersion = "0.9.0-beta.10-build.70-r1",
                installedVersionCode = 71
            )
        )
    }

    private fun release(tagName: String, hasApk: Boolean): GithubRelease = GithubRelease(
        tagName = tagName,
        title = "",
        notes = "",
        isPrerelease = tagName.contains("-"),
        publishedAt = null,
        publishedAtEpochMillis = 0L,
        htmlUrl = "",
        assets = if (hasApk) {
            listOf(GithubReleaseAsset("update.apk", "https://example.com/update.apk", 1L))
        } else {
            emptyList()
        }
    )

}
