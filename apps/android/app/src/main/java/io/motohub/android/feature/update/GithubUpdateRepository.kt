package io.motohub.android.feature.update

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import org.json.JSONArray

data class GithubReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

data class GithubRelease(
    val tagName: String,
    val title: String,
    val notes: String,
    val isPrerelease: Boolean,
    val publishedAt: String?,
    val publishedAtEpochMillis: Long,
    val htmlUrl: String,
    val assets: List<GithubReleaseAsset>
) {
    val versionName: String = tagName.removePrefix("v").removePrefix("V")
    /** Monotonic Android build number encoded in tags such as build.71-r1. */
    val buildNumber: Int? = BUILD_NUMBER_PATTERN.find(versionName)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    val apkAsset: GithubReleaseAsset?
        get() = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

    private companion object {
        val BUILD_NUMBER_PATTERN = Regex("(?:^|-)build\\.(\\d+)(?:-|$)", RegexOption.IGNORE_CASE)
    }
}

/** Reads every published GitHub release, including prereleases. */
class GithubUpdateRepository(
    private val owner: String = "vincenzobpt",
    private val repository: String = "MOTO-HUB"
) {
    fun fetchReleases(): List<GithubRelease> {
        val allReleases = buildList {
            var page = 1
            while (true) {
                val pageJson = fetchPage(page)
                val pageReleases = parseReleases(pageJson)
                addAll(pageReleases)
                if (JSONArray(pageJson).length() < PAGE_SIZE) break
                page++
            }
        }
        return allReleases
            .distinctBy { it.tagName }
            .sortedWith(releaseComparator)
    }

    private fun fetchPage(page: Int): String {
        val endpoint = "https://api.github.com/repos/$owner/$repository/releases" +
            "?per_page=$PAGE_SIZE&page=$page"
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "MOTO-HUB-Android/${io.motohub.android.BuildConfig.VERSION_NAME}")
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("GitHub returned HTTP $status")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseReleases(jsonText: String): List<GithubRelease> {
        val releases = JSONArray(jsonText)
        return buildList(releases.length()) {
            for (index in 0 until releases.length()) {
                val release = releases.getJSONObject(index)
                if (release.optBoolean("draft", false)) continue
                val publishedAt = release.optString("published_at").takeIf { it.isNotBlank() }
                val assetsJson = release.optJSONArray("assets") ?: JSONArray()
                val assets = buildList(assetsJson.length()) {
                    for (assetIndex in 0 until assetsJson.length()) {
                        val asset = assetsJson.getJSONObject(assetIndex)
                        val name = asset.optString("name").trim()
                        val downloadUrl = asset.optString("browser_download_url").trim()
                        if (name.isNotEmpty() && downloadUrl.isNotEmpty()) {
                            add(
                                GithubReleaseAsset(
                                    name = name,
                                    downloadUrl = downloadUrl,
                                    sizeBytes = asset.optLong("size", 0L)
                                )
                            )
                        }
                    }
                }
                add(
                    GithubRelease(
                        tagName = release.optString("tag_name").trim(),
                        title = release.optString("name").trim(),
                        notes = release.optString("body"),
                        isPrerelease = release.optBoolean("prerelease", false),
                        publishedAt = publishedAt,
                        publishedAtEpochMillis = publishedAt?.let(::parseEpochMillis) ?: 0L,
                        htmlUrl = release.optString("html_url").trim(),
                        assets = assets
                    )
                )
            }
        }.filter { it.tagName.isNotBlank() }
            .sortedWith(releaseComparator)
    }

    companion object {
        private const val TIMEOUT_MILLIS = 15_000
        private const val PAGE_SIZE = 100

        internal val releaseComparator =
            compareByDescending<GithubRelease> { parseAppVersion(it.versionName) }
                .thenByDescending { it.publishedAtEpochMillis }

        internal fun parseEpochMillis(value: String): Long =
            runCatching { Instant.parse(value).toEpochMilli() }.getOrDefault(0L)

        internal fun parseAppVersion(raw: String): AppVersion? {
            val match = VERSION_PATTERN.matchEntire(raw.trim()) ?: return null
            val preRelease = match.groupValues[4].takeIf { it.isNotEmpty() }
                ?.split('.', '-', '_')
                .orEmpty()
            return AppVersion(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toInt(),
                preRelease = preRelease
            )
        }

        private val VERSION_PATTERN = Regex("(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+](.*))?")
    }
}

fun latestNewerApkRelease(
    releases: List<GithubRelease>,
    installedVersion: String,
    installedVersionCode: Int? = null
): GithubRelease? = releases
    .asSequence()
    .filter { it.apkAsset != null && it.isNewerThan(installedVersion, installedVersionCode) }
    .sortedWith(GithubUpdateRepository.releaseComparator)
    .firstOrNull()

data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: List<String>
) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int {
        compareValuesBy(this, other, AppVersion::major, AppVersion::minor, AppVersion::patch)
            .takeIf { it != 0 }
            ?.let { return it }
        if (preRelease.isEmpty() && other.preRelease.isNotEmpty()) return 1
        if (preRelease.isNotEmpty() && other.preRelease.isEmpty()) return -1
        for (index in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val left = preRelease[index]
            val right = other.preRelease[index]
            val result = compareIdentifiers(left, right)
            if (result != 0) return result
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    private fun compareIdentifiers(left: String, right: String): Int {
        val leftNumber = left.toIntOrNull()
        val rightNumber = right.toIntOrNull()
        return when {
            leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
            leftNumber != null -> -1
            rightNumber != null -> 1
            else -> left.compareTo(right, ignoreCase = true)
        }
    }
}

fun GithubRelease.isNewerThan(installedVersion: String, installedVersionCode: Int? = null): Boolean {
    val installed = GithubUpdateRepository.parseAppVersion(installedVersion) ?: return false
    val candidate = GithubUpdateRepository.parseAppVersion(versionName) ?: return false

    // Release tags and APK version names have historically drifted in their
    // suffix (for example build.71 vs build.71-r1). When both sides expose a
    // build number, the Android versionCode is the authoritative installed
    // state and prevents the same APK from being offered repeatedly.
    buildNumber?.let { candidateBuild ->
        installedVersionCode?.let { installedBuild ->
            if (candidateBuild <= installedBuild) return false
        }
    }
    return candidate > installed
}
