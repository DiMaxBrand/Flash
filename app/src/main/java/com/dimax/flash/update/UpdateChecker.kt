package com.dimax.flash.update

import com.dimax.flash.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks for a newer Flash release on GitHub. Tags follow the same scheme as Impulse's:
 * `MAJOR.MINOR.PATCH` for stable (built from `main`) and `MAJOR.MINOR.PATCH-beta.N` for beta
 * (built from `develop`). Anything else (the old `v0.10.3-name--Oct-17-sha` tags, for example) does
 * not parse and is simply ignored.
 */
class UpdateChecker {

    fun checkForUpdate(channel: UpdateChannel): CheckResult {
        // null = the check itself failed (no connection, GitHub erroring or rate-limiting, bad
        // answer). That must never be reported as "up to date".
        val releases = fetchReleases() ?: return CheckResult.CheckFailed
        val current = parseVersion(stripBuildMeta(BuildConfig.UPDATE_VERSION)) ?: return CheckResult.CheckFailed

        val best = releases
            .filter { !it.optBoolean("draft") && releaseMatchesChannel(it.optString("tag_name"), channel) }
            .mapNotNull { release ->
                val tag = release.optString("tag_name")
                val version = parseVersion(tag) ?: return@mapNotNull null
                val apkUrl = findApkAsset(release) ?: return@mapNotNull null
                Triple(version, apkUrl, tag) to release
            }
            .maxWithOrNull { (a, _), (b, _) -> compareSemver(a.first, b.first) }
            ?: return CheckResult.UpToDate
        val (bestTriple, bestRelease) = best

        val cmp = compareSemver(bestTriple.first, current)
        return when {
            cmp > 0 -> CheckResult.UpdateAvailable(
                UpdateInfo(
                    versionName = bestTriple.third,
                    channel = channel,
                    downloadUrl = bestTriple.second,
                    releaseNotes = bestRelease.optString("body"),
                    releaseTitle = bestRelease.optString("name"),
                )
            )
            // The best release on this channel is older than what is installed: the user switched
            // to a more stable channel than the build they are running.
            cmp < 0 -> CheckResult.ChannelBehind
            else -> CheckResult.UpToDate
        }
    }

    sealed class CheckResult {
        data class UpdateAvailable(val info: UpdateInfo) : CheckResult()
        object UpToDate : CheckResult()
        object ChannelBehind : CheckResult()
        object CheckFailed : CheckResult()
    }

    /** Up to [MAX_PAGES] pages of 100, newest first: every `develop` push is a release, so a
     * stable release can sit well down the list. */
    private fun fetchReleases(): List<JSONObject>? {
        val all = ArrayList<JSONObject>()
        for (page in 1..MAX_PAGES) {
            val batch = try {
                val connection = URL("https://api.github.com/repos/DiMaxBrand/Flash/releases?per_page=100&page=$page")
                    .openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                    if (connection.responseCode !in 200..299) return null
                    val array = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                    (0 until array.length()).map { array.getJSONObject(it) }
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                return null
            }
            all += batch
            if (batch.size < 100) break
        }
        return all
    }

    private fun findApkAsset(release: JSONObject): String? {
        val assets = release.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk")) return asset.optString("browser_download_url")
        }
        return null
    }

    private fun releaseMatchesChannel(tag: String, channel: UpdateChannel): Boolean {
        val base = stripBuildMeta(tag)
        val isStable = !base.contains('-')
        // Beta includes stable: a beta user also gets a stable release when one comes out.
        return when (channel) {
            UpdateChannel.STABLE -> isStable
            UpdateChannel.BETA -> isStable || base.contains("-beta.")
        }
    }

    companion object {
        private const val MAX_PAGES = 3

        fun stripBuildMeta(version: String): String = version.substringBefore('+')

        data class SemVer(
            val major: Int,
            val minor: Int,
            val patch: Int,
            val preType: String?,
            val preNum: Int,
        )

        fun parseVersion(raw: String): SemVer? {
            val base = stripBuildMeta(raw)
            val dashIdx = base.indexOf('-')
            val corePart = if (dashIdx >= 0) base.substring(0, dashIdx) else base
            val prePart = if (dashIdx >= 0) base.substring(dashIdx + 1) else null

            val parts = corePart.split('.')
            if (parts.size < 3) return null
            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts[1].toIntOrNull() ?: return null
            val patch = parts[2].toIntOrNull() ?: return null

            var preType: String? = null
            var preNum = 0
            if (prePart != null) {
                val dotIdx = prePart.lastIndexOf('.')
                if (dotIdx >= 0) {
                    preType = prePart.substring(0, dotIdx)
                    preNum = prePart.substring(dotIdx + 1).toIntOrNull() ?: 0
                } else {
                    preType = prePart
                }
            }
            return SemVer(major, minor, patch, preType, preNum)
        }

        /** True when [versionName] (a stored pending/downloaded version) is newer than what runs now. */
        fun isNewerThanInstalled(versionName: String?): Boolean {
            if (versionName == null) return false
            val current = parseVersion(stripBuildMeta(BuildConfig.UPDATE_VERSION)) ?: return true
            val candidate = parseVersion(versionName) ?: return false
            return compareSemver(candidate, current) > 0
        }

        fun compareSemver(a: SemVer, b: SemVer): Int {
            val core = compareValuesBy(a, b, { it.major }, { it.minor }, { it.patch })
            if (core != 0) return core
            // stable (no pre) > any pre-release of the same version
            if (a.preType == null && b.preType != null) return 1
            if (a.preType != null && b.preType == null) return -1
            if (a.preType == null && b.preType == null) return 0
            val preOrder = listOf("alpha", "beta", "rc")
            val ordCmp = preOrder.indexOf(a.preType).compareTo(preOrder.indexOf(b.preType))
            if (ordCmp != 0) return ordCmp
            return a.preNum.compareTo(b.preNum)
        }
    }
}
