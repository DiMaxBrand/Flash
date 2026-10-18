package com.dimax.flash.update

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.concurrent.TimeUnit

class UpdatePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("update_prefs", Context.MODE_PRIVATE)

    var selectedChannel: UpdateChannel
        get() = UpdateChannel.fromId(prefs.getString(KEY_CHANNEL, UpdateChannel.BETA.id)!!)
        set(value) = prefs.edit { putString(KEY_CHANNEL, value.id) }

    /** Look for an update when the app is opened (on by default, on either channel). */
    var autoCheck: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CHECK, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_CHECK, value) }

    /** "Wait between updates", in hours; 0 = off. After Flash has been updated, automatic checks and
     * the auto-popup stay quiet until this much time has passed. */
    var minUpdateIntervalHours: Int
        get() = prefs.getInt(KEY_MIN_INTERVAL_HOURS, 0)
        set(value) = prefs.edit { putInt(KEY_MIN_INTERVAL_HOURS, value) }

    /** True while the "wait between updates" window after the app's last install/update is still
     * running, measured from the OS's own package record. Only automatic paths consult this; the
     * manual "Check now" ignores it. */
    fun isWithinQuietWindow(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean {
        val hours = minUpdateIntervalHours
        if (hours <= 0) return false
        val lastUpdate =
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            } catch (_: Exception) {
                return false
            }
        return nowMs - lastUpdate < TimeUnit.HOURS.toMillis(hours.toLong())
    }

    var pendingUpdateVersion: String?
        get() = prefs.getString(KEY_PENDING_VERSION, null)
        set(value) = prefs.edit { putString(KEY_PENDING_VERSION, value) }

    var pendingUpdateUrl: String?
        get() = prefs.getString(KEY_PENDING_URL, null)
        set(value) = prefs.edit { putString(KEY_PENDING_URL, value) }

    var pendingReleaseNotes: String?
        get() = prefs.getString(KEY_PENDING_RELEASE_NOTES, null)
        set(value) = prefs.edit { putString(KEY_PENDING_RELEASE_NOTES, value) }

    var pendingReleaseTitle: String?
        get() = prefs.getString(KEY_PENDING_RELEASE_TITLE, null)
        set(value) = prefs.edit { putString(KEY_PENDING_RELEASE_TITLE, value) }

    var pendingNoWifi: Boolean
        get() = prefs.getBoolean(KEY_PENDING_NO_WIFI, false)
        set(value) = prefs.edit { putBoolean(KEY_PENDING_NO_WIFI, value) }

    var activeDownloadId: Long
        get() = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        set(value) = prefs.edit { putLong(KEY_DOWNLOAD_ID, value) }

    var downloadedApkPath: String?
        get() = prefs.getString(KEY_DOWNLOADED_APK, null)
        set(value) = prefs.edit { putString(KEY_DOWNLOADED_APK, value) }

    var downloadedVersion: String?
        get() = prefs.getString(KEY_DOWNLOADED_VERSION, null)
        set(value) = prefs.edit { putString(KEY_DOWNLOADED_VERSION, value) }

    var hasInstalledUpdate: Boolean
        get() = prefs.getBoolean(KEY_HAS_INSTALLED, false)
        set(value) = prefs.edit { putBoolean(KEY_HAS_INSTALLED, value) }

    var sheetDismissedUntil: Long
        get() = prefs.getLong(KEY_SHEET_DISMISSED_UNTIL, 0L)
        set(value) = prefs.edit { putLong(KEY_SHEET_DISMISSED_UNTIL, value) }

    /** When the open-the-app update check last ran, for its rate limit (GitHub allows about 60
     * unauthenticated requests an hour per network address). */
    var lastLaunchCheckAt: Long
        get() = prefs.getLong(KEY_LAST_LAUNCH_CHECK, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_LAUNCH_CHECK, value) }

    // ---- "Flash was updated" ------------------------------------------------------------------
    // Written just before an install is handed to the system; read back on the first launch of that
    // version to show what's new.

    var justUpdatedVersion: String?
        get() = prefs.getString(KEY_JUST_UPDATED_VERSION, null)
        set(value) = prefs.edit { putString(KEY_JUST_UPDATED_VERSION, value) }

    var justUpdatedTitle: String?
        get() = prefs.getString(KEY_JUST_UPDATED_TITLE, null)
        set(value) = prefs.edit { putString(KEY_JUST_UPDATED_TITLE, value) }

    var justUpdatedNotes: String?
        get() = prefs.getString(KEY_JUST_UPDATED_NOTES, null)
        set(value) = prefs.edit { putString(KEY_JUST_UPDATED_NOTES, value) }

    /** The version whose update sheet (with release notes) the user was already shown, so
     * "Flash was updated" does not repeat it. */
    var updateSheetSeenVersion: String?
        get() = prefs.getString(KEY_SHEET_SEEN_VERSION, null)
        set(value) = prefs.edit { putString(KEY_SHEET_SEEN_VERSION, value) }

    fun rememberJustUpdated() {
        val version = downloadedVersion ?: pendingUpdateVersion ?: return
        if (updateSheetSeenVersion == version) {
            clearJustUpdated()
            return
        }
        prefs.edit {
            putString(KEY_JUST_UPDATED_VERSION, version)
            putString(KEY_JUST_UPDATED_TITLE, pendingReleaseTitle)
            putString(KEY_JUST_UPDATED_NOTES, pendingReleaseNotes)
        }
    }

    fun clearJustUpdated() {
        prefs.edit {
            remove(KEY_JUST_UPDATED_VERSION)
            remove(KEY_JUST_UPDATED_TITLE)
            remove(KEY_JUST_UPDATED_NOTES)
        }
    }

    /** True when the version now running is the one [rememberJustUpdated] recorded. */
    fun justUpdatedMatchesRunning(currentVersionRaw: String): Boolean {
        val remembered = justUpdatedVersion?.let { UpdateChecker.parseVersion(it) } ?: return false
        val current = UpdateChecker.parseVersion(UpdateChecker.stripBuildMeta(currentVersionRaw)) ?: return false
        return UpdateChecker.compareSemver(remembered, current) == 0
    }

    fun clearPending() {
        prefs.edit {
            remove(KEY_PENDING_VERSION)
            remove(KEY_PENDING_URL)
            remove(KEY_PENDING_NO_WIFI)
        }
    }

    fun downloadedApkExists(): Boolean {
        val path = downloadedApkPath ?: return false
        return java.io.File(android.net.Uri.parse(path).path ?: path).exists()
    }

    /** Drops only the "already downloaded and ready" pointer, not the pending title/notes. */
    fun clearDownloadedApk() {
        prefs.edit {
            remove(KEY_DOWNLOADED_APK)
            remove(KEY_DOWNLOADED_VERSION)
        }
    }

    fun clearDownload() {
        prefs.edit {
            remove(KEY_DOWNLOADED_APK)
            remove(KEY_DOWNLOADED_VERSION)
            remove(KEY_PENDING_RELEASE_NOTES)
            remove(KEY_PENDING_RELEASE_TITLE)
        }
    }

    /** Self-heals stale pending/downloaded state left over from before an update was installed:
     * the install is handed to the system without clearing these, so the first launch of the new
     * version would otherwise still point at what is now installed. */
    fun clearIfNotNewerThan(currentVersionRaw: String) {
        val current = UpdateChecker.parseVersion(UpdateChecker.stripBuildMeta(currentVersionRaw)) ?: return
        pendingUpdateVersion?.let { UpdateChecker.parseVersion(it) }?.let { pending ->
            if (UpdateChecker.compareSemver(pending, current) <= 0) clearPending()
        }
        downloadedVersion?.let { UpdateChecker.parseVersion(it) }?.let { downloaded ->
            if (UpdateChecker.compareSemver(downloaded, current) <= 0) {
                clearDownload()
                downloadedApkPath = null
            }
        }
    }

    companion object {
        private const val KEY_CHANNEL = "channel"
        private const val KEY_AUTO_CHECK = "auto_check"
        private const val KEY_MIN_INTERVAL_HOURS = "min_update_interval_hours"
        private const val KEY_PENDING_VERSION = "pending_version"
        private const val KEY_PENDING_URL = "pending_url"
        private const val KEY_PENDING_RELEASE_NOTES = "pending_release_notes"
        private const val KEY_PENDING_RELEASE_TITLE = "pending_release_title"
        private const val KEY_PENDING_NO_WIFI = "pending_no_wifi"
        private const val KEY_DOWNLOAD_ID = "download_id"
        private const val KEY_DOWNLOADED_APK = "downloaded_apk"
        private const val KEY_HAS_INSTALLED = "has_installed_update"
        private const val KEY_DOWNLOADED_VERSION = "downloaded_version"
        private const val KEY_SHEET_DISMISSED_UNTIL = "sheet_dismissed_until"
        private const val KEY_JUST_UPDATED_VERSION = "just_updated_version"
        private const val KEY_JUST_UPDATED_TITLE = "just_updated_title"
        private const val KEY_JUST_UPDATED_NOTES = "just_updated_notes"
        private const val KEY_SHEET_SEEN_VERSION = "sheet_seen_version"
        private const val KEY_LAST_LAUNCH_CHECK = "last_launch_check_at"
    }
}
