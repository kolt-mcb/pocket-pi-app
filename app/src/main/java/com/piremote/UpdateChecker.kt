package com.piremote

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.Locale

private const val TAG = "PiUpdater"
private val VERSION_CODE_RE = Regex("""versionCode:\s*(\d+)""")
private val VERSION_NAME_RE = Regex("""versionName:\s*(\S+)""")

// Default release endpoint. The repo currently lives at this URL; if it ever
// moves, override via the constructor at call-site. The release tag "latest"
// is the rolling pre-release published by CI on every push to master.
private const val DEFAULT_RELEASE_API =
    "https://api.github.com/repos/kolt-mcb/pocket-pi-app/releases/tags/latest"

/**
 * Result of a successful update check. [versionCode] is what the latest
 * release advertises in its body (`versionCode: N` line written by the CI
 * workflow). [apkUrl] is the direct download URL for the APK asset.
 */
data class LatestRelease(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val publishedAt: String,
)

/**
 * Read-only — checks GitHub Releases for a newer build, downloads the APK,
 * and fires the OS package installer. Doesn't auto-check on launch; the
 * UI calls [fetchLatest] explicitly so we don't burn bandwidth (each
 * download is ~40 MB).
 *
 * @param ctx Application context for file I/O.
 * @param releaseApiUrl GitHub Releases API endpoint.
 * @param authToken Optional GitHub personal access token to raise API rate
 *   limits from 60/hr (unauthenticated) to 5,000/hr (authenticated).
 */
class UpdateChecker(
    private val ctx: Context,
    private val releaseApiUrl: String = DEFAULT_RELEASE_API,
    authToken: String? = null,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .apply {
            if (!authToken.isNullOrEmpty()) {
                // Use Bearer token — more widely supported than the older
                // "token <hex>" scheme. Token should be read-only.
                val trimmed = authToken.trim()
                if (trimmed.isNotBlank() && (trimmed.lowercase(Locale.ROOT).startsWith("ghp_") ||
                        trimmed.lowercase(Locale.ROOT).startsWith("github_pat_"))) {
                    addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("Authorization", "Bearer $trimmed")
                            .build()
                        chain.proceed(request)
                    }
                }
            }
        }
        .build()

    /** Running app's installed versionCode. Pulled at runtime from
     *  PackageManager so it's always accurate even if BuildConfig isn't
     *  enabled in this project. */
    fun currentVersionCode(): Long = try {
        @Suppress("DEPRECATION")
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        // longVersionCode is API 28+; minSdk is 26 so we still need the cast.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pi.longVersionCode
        else pi.versionCode.toLong()
    } catch (e: Exception) {
        Log.w(TAG, "currentVersionCode failed: ${e.message}")
        0L
    }

    fun currentVersionName(): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    /**
     * Fetch the latest release metadata from GitHub. Returns null if the
     * request fails (network down, repo not public yet, etc.) or the
     * response doesn't include a parseable versionCode + APK asset.
     */
    suspend fun fetchLatest(): LatestRelease? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(releaseApiUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "release api ${resp.code} — ${resp.message}")
                    return@use null
                }
                val body = resp.body?.string() ?: return@use null
                val parsed = JP.p(body) ?: return@use null
                val releaseBody = Js.gets(parsed, "body") ?: ""
                val publishedAt = Js.gets(parsed, "published_at") ?: ""
                // versionCode + versionName come from lines the CI workflow
                // stamps into the release body. Format: `versionCode: NN` /
                // `versionName: <shortsha>`. Tolerate whitespace.
                val codeMatch = VERSION_CODE_RE.find(releaseBody)
                val nameMatch = VERSION_NAME_RE.find(releaseBody)
                if (codeMatch == null) {
                    Log.w(TAG, "release body missing versionCode line")
                    return@use null
                }
                val versionCode = codeMatch.groupValues[1].toLongOrNull() ?: return@use null
                val versionName = nameMatch?.groupValues?.get(1) ?: "?"
                // Find the .apk asset.
                val assets = parsed["assets"] as? List<*> ?: return@use null
                val apkUrl = assets.asSequence()
                    .filterIsInstance<Map<*, *>>()
                    .mapNotNull { Js.gets(it, "browser_download_url") }
                    .firstOrNull { it.endsWith(".apk") }
                    ?: return@use null
                LatestRelease(versionCode, versionName, apkUrl, publishedAt)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchLatest failed: ${e.message}")
            null
        }
    }

    /**
     * Download the APK to the app's cache. Returns the downloaded file or
     * null on failure. Caller should pass to [launchInstaller].
     */
    suspend fun downloadApk(release: LatestRelease, onProgress: (Int) -> Unit = {}): File? = withContext(Dispatchers.IO) {
        val cacheDir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        // Clear any previous APKs to avoid stale files filling the cache.
        cacheDir.listFiles()?.forEach { it.delete() }
        val outFile = File(cacheDir, "pi-remote-v${release.versionCode}.apk")
        try {
            val req = Request.Builder().url(release.apkUrl).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "download ${resp.code}")
                    return@use null
                }
                val total = resp.body?.contentLength() ?: -1L
                val source = resp.body?.source() ?: return@use null
                outFile.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        val n = source.read(buf)
                        if (n == -1) break
                        out.write(buf, 0, n)
                        written += n
                        if (total > 0) {
                            val pct = ((written * 100) / total).toInt().coerceIn(0, 100)
                            onProgress(pct)
                        }
                    }
                }
                outFile
            }
        } catch (e: Exception) {
            Log.w(TAG, "downloadApk failed: ${e.message}")
            outFile.delete()
            null
        }
    }

    /**
     * Hand the downloaded APK to the OS package installer. The user sees
     * Android's standard "install this app?" sheet and confirms.
     *
     * Requires REQUEST_INSTALL_PACKAGES permission in the manifest. The user
     * also has to grant "Install unknown apps" for this app in Settings the
     * first time; the installer Intent will prompt for that if missing.
     */
    fun launchInstaller(apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            ctx, ctx.packageName + ".updates", apk
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startActivity for installer failed", e)
        }
    }
}
