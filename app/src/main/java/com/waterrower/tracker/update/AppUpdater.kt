package com.waterrower.tracker.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.waterrower.tracker.data.WorkoutExporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

data class ReleaseInfo(
    val tagName: String,
    val title: String,
    val notes: String,
    val apkName: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
    val htmlUrl: String,
) {
    val formattedSizeMb: String
        get() = if (apkSizeBytes > 0L) {
            String.format(Locale.GERMANY, "%.1f MB", apkSizeBytes / (1024.0 * 1024.0))
        } else {
            ""
        }
}

sealed interface UpdateCheckResult {
    data class UpdateAvailable(val release: ReleaseInfo) : UpdateCheckResult
    data class UpToDate(val latestTag: String) : UpdateCheckResult
}

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val latestTag: String) : UpdateUiState
    data class Available(val release: ReleaseInfo) : UpdateUiState
    data class Downloading(val release: ReleaseInfo, val progress: Float) : UpdateUiState
    data class ReadyToInstall(
        val release: ReleaseInfo,
        val apkFile: File,
        val needsInstallPermission: Boolean = false,
    ) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

enum class InstallLaunchResult {
    LAUNCHED_INSTALLER,
    NEEDS_UNKNOWN_SOURCES_PERMISSION,
    FAILED,
}

/**
 * Lightweight, zero-extra-dependency GitHub Releases self-updater for WaterRower.
 * Only touches the network when explicitly triggered by the user from Settings.
 */
object AppUpdater {

    const val GITHUB_OWNER = "langerdennis"
    const val GITHUB_REPO = "waterrower-tracker"
    const val LATEST_RELEASE_API_URL =
        "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MAX_REDIRECTS = 5

    private val ALLOWED_DOWNLOAD_HOSTS = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com",
    )

    suspend fun checkForUpdate(currentVersionName: String): Result<UpdateCheckResult> = try {
        val release = withContext(Dispatchers.IO) {
            val body = fetchText(LATEST_RELEASE_API_URL, currentVersionName)
            parseLatestReleaseJson(body)
                ?: error("Kein installierbares APK-Release auf GitHub gefunden.")
        }
        val result = if (isNewerVersion(currentVersionName, release.tagName)) {
            UpdateCheckResult.UpdateAvailable(release)
        } else {
            UpdateCheckResult.UpToDate(release.tagName)
        }
        Result.success(result)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }

    suspend fun downloadApk(
        context: Context,
        release: ReleaseInfo,
        currentVersionName: String,
        onProgress: (Float) -> Unit,
    ): Result<File> = try {
        require(isAllowedDownloadUrl(release.apkDownloadUrl)) {
            "Unsichere Download-URL abgelehnt."
        }
        val finalFile = withContext(Dispatchers.IO) {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            // Clean up stale cached APKs before downloading
            updatesDir.listFiles()?.forEach { it.delete() }

            val safeTag = release.tagName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val partFile = File(updatesDir, "waterrower-$safeTag.apk.part")
            val targetFile = File(updatesDir, "waterrower-$safeTag.apk")

            val conn = openFollowingHttpsRedirects(release.apkDownloadUrl, currentVersionName)
            try {
                val totalBytes = conn.contentLengthLong.takeIf { it > 0L } ?: release.apkSizeBytes
                var downloaded = 0L
                var lastReportedPct = -1

                conn.inputStream.use { input ->
                    partFile.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0L) {
                                val fraction = (downloaded.toDouble() / totalBytes.toDouble())
                                    .toFloat()
                                    .coerceIn(0f, 1f)
                                val pct = (fraction * 100f).toInt()
                                if (pct != lastReportedPct) {
                                    lastReportedPct = pct
                                    onProgress(fraction)
                                }
                            }
                        }
                    }
                }
                if (downloaded <= 0L) {
                    partFile.delete()
                    error("Leere APK-Datei heruntergeladen.")
                }
                if (!partFile.renameTo(targetFile)) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }
                onProgress(1f)
                targetFile
            } finally {
                conn.disconnect()
            }
        }
        Result.success(finalFile)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }

    fun installApk(context: Context, apkFile: File): InstallLaunchResult {
        if (!apkFile.exists() || apkFile.length() <= 0L) {
            return InstallLaunchResult.FAILED
        }
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(settingsIntent)
                InstallLaunchResult.NEEDS_UNKNOWN_SOURCES_PERMISSION
            } catch (_: Throwable) {
                InstallLaunchResult.FAILED
            }
        }

        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            InstallLaunchResult.LAUNCHED_INSTALLER
        } catch (_: Throwable) {
            InstallLaunchResult.FAILED
        }
    }

    /**
     * Parses a GitHub `/releases/latest` JSON payload into [ReleaseInfo].
     * Prefers signed release APKs over `-debug.apk` assets.
     */
    @Suppress("UNCHECKED_CAST")
    fun parseLatestReleaseJson(json: String): ReleaseInfo? {
        val root = runCatching { WorkoutExporter.parseJsonObject(json) }.getOrNull() ?: return null
        val draft = root["draft"] as? Boolean ?: false
        val prerelease = root["prerelease"] as? Boolean ?: false
        if (draft || prerelease) return null

        val tagName = (root["tag_name"] as? String)?.trim().orEmpty()
        if (tagName.isBlank()) return null

        val title = (root["name"] as? String)?.trim().orEmpty().ifBlank { tagName }
        val notes = (root["body"] as? String)?.trim().orEmpty()
        val htmlUrl = (root["html_url"] as? String)?.trim().orEmpty()
        val assets = root["assets"] as? List<Map<String, Any?>> ?: return null

        val apkAssets = assets.filter { asset ->
            val name = (asset["name"] as? String)?.lowercase(Locale.US).orEmpty()
            val url = (asset["browser_download_url"] as? String).orEmpty()
            name.endsWith(".apk") && isAllowedDownloadUrl(url)
        }
        if (apkAssets.isEmpty()) return null

        val chosen = apkAssets.firstOrNull { asset ->
            val name = (asset["name"] as? String)?.lowercase(Locale.US).orEmpty()
            !name.contains("debug")
        } ?: apkAssets.first()

        val apkName = (chosen["name"] as? String).orEmpty()
        val apkUrl = (chosen["browser_download_url"] as? String).orEmpty()
        val apkSize = (chosen["size"] as? Number)?.toLong() ?: 0L

        return ReleaseInfo(
            tagName = tagName,
            title = title,
            notes = notes,
            apkName = apkName,
            apkDownloadUrl = apkUrl,
            apkSizeBytes = apkSize,
            htmlUrl = htmlUrl,
        )
    }

    /**
     * Compares numeric version components (e.g. `0.4` < `v0.4.1` < `v0.5` < `v0.10.0`).
     * Dev suffixes such as `0.4.1-dev.25` share core version `0.4.1`, so `v0.4.1` is not treated
     * as newer than `0.4.1-dev.25`, whereas `v0.4.2` is.
     */
    fun isNewerVersion(currentVersion: String, latestTag: String): Boolean {
        val currentParts = parseVersionParts(currentVersion)
        val latestParts = parseVersionParts(latestTag)
        if (latestParts.isEmpty()) return false
        if (currentParts.isEmpty()) return true

        val maxLen = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val cur = currentParts.getOrElse(i) { 0 }
            val lat = latestParts.getOrElse(i) { 0 }
            if (lat != cur) return lat > cur
        }
        return false
    }

    internal fun parseVersionParts(raw: String): List<Int> {
        val cleaned = raw.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
            .trim()
        if (cleaned.isBlank()) return emptyList()
        return cleaned.split('.').mapNotNull { part -> part.toIntOrNull() }
    }

    internal fun isAllowedDownloadUrl(url: String): Boolean {
        return try {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase(Locale.US)
            val host = uri.host?.lowercase(Locale.US)
            scheme == "https" && host != null && host in ALLOWED_DOWNLOAD_HOSTS
        } catch (_: Throwable) {
            false
        }
    }

    private fun fetchText(urlString: String, currentVersionName: String): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "WaterRower-Android/$currentVersionName")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                error("GitHub antwortete mit HTTP $code.")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun openFollowingHttpsRedirects(
        initialUrl: String,
        currentVersionName: String,
    ): HttpURLConnection {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS + 1) {
            require(isAllowedDownloadUrl(currentUrl)) {
                "Weiterleitung auf nicht erlaubten Host blockiert."
            }
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("User-Agent", "WaterRower-Android/$currentVersionName")
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) {
                    error("Unvollständige Weiterleitung (HTTP $code).")
                }
                currentUrl = location
            } else if (code in 200..299) {
                return conn
            } else {
                conn.disconnect()
                error("Download fehlgeschlagen (HTTP $code).")
            }
        }
        error("Zu viele Weiterleitungen beim APK-Download.")
    }
}
