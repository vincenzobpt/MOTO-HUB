package io.motohub.android.feature.update

import io.motohub.android.i18n.motoHubText

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.motohub.android.BuildConfig
import io.motohub.android.ui.components.MonoLabel

@Composable
fun GithubUpdateDialog(
    releases: List<GithubRelease>,
    isLoading: Boolean,
    error: String?,
    installingTag: String?,
    installingProgress: DownloadProgress?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onInstall: (GithubRelease) -> Unit,
    onSkip: (GithubRelease) -> Unit,
    onAllowUnknownSources: () -> Unit,
    canInstallUnknownSources: Boolean
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(motoHubText("MOTO-HUB updates")) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    motoHubText("Installed version: %1\$s (%2\$d)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
                if (isLoading) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                        Text(motoHubText("Checking GitHub releases..."))
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text(motoHubText("Retry"))
                    }
                }
                if (!isLoading && error == null && releases.isEmpty()) {
                    Text(motoHubText("No newer version is available."))
                }
                releases.forEach { release ->
                    ReleaseEntry(
                        release = release,
                        installing = installingTag == release.tagName,
                        progress = installingProgress?.takeIf { installingTag == release.tagName },
                        canInstallUnknownSources = canInstallUnknownSources,
                        onInstall = { onInstall(release) },
                        onSkip = { onSkip(release) },
                        onAllowUnknownSources = onAllowUnknownSources
                    )
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) { Text(motoHubText("Close")) }
        }
    )
}

@Composable
private fun ReleaseEntry(
    release: GithubRelease,
    installing: Boolean,
    progress: DownloadProgress?,
    canInstallUnknownSources: Boolean,
    onInstall: () -> Unit,
    onSkip: () -> Unit,
    onAllowUnknownSources: () -> Unit
) {
    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    release.versionName,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                if (release.isPrerelease) {
                    MonoLabel(motoHubText("PRE-RELEASE"))
                }
            }
            if (release.title.isNotBlank() && release.title != release.versionName) {
                Text(release.title, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
            Text(
                releaseNotesAnnotated(release.notes.ifBlank { "No release notes provided." }),
                style = MaterialTheme.typography.bodySmall
            )
            if (release.apkAsset == null) {
                Text(motoHubText("No APK asset attached to this release."), color = MaterialTheme.colorScheme.error)
            } else if (!canInstallUnknownSources) {
                OutlinedButton(onClick = onAllowUnknownSources, modifier = Modifier.fillMaxWidth()) {
                    Text(motoHubText("Allow APK installation"))
                }
            } else {
                if (installing) {
                    DownloadProgressRow(progress)
                }
                Button(
                    onClick = onInstall,
                    enabled = !installing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (installing) "Downloading..." else "Download and install")
                }
            }
            OutlinedButton(
                onClick = onSkip,
                enabled = !installing,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(motoHubText("Skip this version"))
            }
        }
    }
}

/** Compact, dependency-free Markdown presentation for GitHub release notes. */
internal fun releaseNotesAnnotated(markdown: String): AnnotatedString = buildAnnotatedString {
    markdown.lines().forEachIndexed { index, originalLine ->
        val line = originalLine.trimEnd()
        val content = when {
            line.startsWith("### ") -> line.removePrefix("### ")
            line.startsWith("## ") -> line.removePrefix("## ")
            line.startsWith("# ") -> line.removePrefix("# ")
            line.startsWith("- ") || line.startsWith("* ") -> "• ${line.drop(2)}"
            else -> line
        }
        val heading = line.startsWith("#")
        if (heading) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInlineMarkdown(content) }
        } else {
            appendInlineMarkdown(content)
        }
        if (index != markdown.lines().lastIndex) append('\n')
    }
}

private fun AnnotatedString.Builder.appendInlineMarkdown(text: String) {
    val token = Regex("\\*\\*([^*]+)\\*\\*|\\[([^]]+)]\\(([^)]+)\\)")
    var cursor = 0
    token.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        val bold = match.groups[1]?.value
        val label = match.groups[2]?.value
        val url = match.groups[3]?.value
        when {
            bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            label != null && url != null -> {
                withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append(label) }
                append(" ($url)")
            }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

@Composable
private fun DownloadProgressRow(progress: DownloadProgress?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val fraction = progress?.fraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(downloadStatusText(progress), style = MaterialTheme.typography.bodySmall)
    }
}

private fun downloadStatusText(progress: DownloadProgress?): String {
    if (progress == null) return "Starting download..."
    val downloadedMb = progress.bytesDownloaded / 1_000_000.0
    val totalMb = progress.totalBytes.takeIf { it > 0 }?.let { it / 1_000_000.0 }
    val percent = progress.fraction?.let { (it * 100).toInt() }
    return if (totalMb != null && percent != null) {
        "Downloading... %.1f MB / %.1f MB (%d%%)".format(downloadedMb, totalMb, percent)
    } else {
        "Downloading... %.1f MB".format(downloadedMb)
    }
}
