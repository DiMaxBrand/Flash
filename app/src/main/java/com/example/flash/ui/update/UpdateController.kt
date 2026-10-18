package com.example.flash.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.flash.BuildConfig
import com.example.flash.update.DownloadEtaTracker
import com.example.flash.update.UpdateChannel
import com.example.flash.update.UpdateChecker
import com.example.flash.update.UpdateDownloader
import com.example.flash.update.UpdateInfo
import com.example.flash.update.UpdatePreferences
import com.example.flash.update.formatSpeedAndEta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one place that runs the update flow (check, download, install). Both the Settings screen's
 * update section and the update bottom sheet read [ui] and call into this, so there is exactly one
 * copy of the logic. Ported from Impulse's UpdatesActivity + UpdateSheetFragment + UpdateCheckHelper,
 * without the nightly install and the bug-report matching.
 */
class UpdateController(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = UpdatePreferences(app)
    private var pollJob: Job? = null

    var ui by mutableStateOf(UpdatesUiState(currentVersion = BuildConfig.VERSION_NAME))
        private set

    /** The update bottom sheet is showing. */
    var sheetVisible by mutableStateOf(false)
        private set

    init {
        // Same self-heal as at the start of every automatic check: stale pending/downloaded state
        // for a version that is now already installed would keep re-offering it.
        prefs.clearIfNotNewerThan(BuildConfig.UPDATE_VERSION)
        refresh()
    }

    // ───────────────────────────── state ─────────────────────────────

    /** Re-reads prefs into [ui]. Cheap; called when a screen appears or a check finishes. */
    fun refresh() {
        val pendingVersion = prefs.pendingUpdateVersion
        val apkExists = prefs.downloadedApkExists() && UpdateChecker.isNewerThanInstalled(prefs.downloadedVersion)
        if (prefs.downloadedApkPath != null && !apkExists) prefs.clearDownload()

        val busy = ui.downloadPhase == DownloadPhase.PROCESSING || ui.downloadPhase == DownloadPhase.CANCELING ||
            (ui.downloadPhase == DownloadPhase.DOWNLOADING && pollJob?.isActive == true)
        val restoredPhase = when {
            busy -> ui.downloadPhase
            apkExists -> DownloadPhase.READY
            prefs.activeDownloadId != -1L -> DownloadPhase.DOWNLOADING
            prefs.pendingNoWifi && pendingVersion != null -> DownloadPhase.NO_WIFI_PENDING
            else -> DownloadPhase.IDLE
        }
        ui = ui.copy(
            currentVersion = BuildConfig.VERSION_NAME,
            selectedChannel = prefs.selectedChannel,
            autoCheck = prefs.autoCheck,
            minUpdateIntervalHours = prefs.minUpdateIntervalHours,
            canInstallApps = app.packageManager.canRequestPackageInstalls(),
            canInstallDirectly = app.packageManager.canRequestPackageInstalls(),
            downloadPhase = restoredPhase,
            pendingVersion = pendingVersion ?: if (restoredPhase == DownloadPhase.READY) prefs.downloadedVersion else null,
            releaseNotes = prefs.pendingReleaseNotes,
            releaseTitle = prefs.pendingReleaseTitle,
            isFirstUpdate = !prefs.hasInstalledUpdate,
        )
        if (restoredPhase == DownloadPhase.DOWNLOADING && pollJob?.isActive != true && prefs.activeDownloadId != -1L) {
            pollDownload(prefs.activeDownloadId)
        }
    }

    // ───────────────────────────── settings ─────────────────────────────

    fun setChannel(channel: UpdateChannel) {
        prefs.selectedChannel = channel
        ui = ui.copy(selectedChannel = channel)
    }

    fun setAutoCheck(enabled: Boolean) {
        prefs.autoCheck = enabled
        ui = ui.copy(autoCheck = enabled)
    }

    fun setMinInterval(hours: Int) {
        prefs.minUpdateIntervalHours = hours
        ui = ui.copy(minUpdateIntervalHours = hours)
    }

    /** Opens the system's "Install unknown apps" page for Flash. */
    fun openInstallPermissionSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: RuntimeException) {
            context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ───────────────────────────── checking ─────────────────────────────

    /** The Settings screen's "Check now": ignores the quiet window and the auto-check switch. */
    fun checkNow() {
        if (ui.checkStatus == CheckStatus.CHECKING) return
        ui = ui.copy(checkStatus = CheckStatus.CHECKING)
        scope.launch {
            val result = withContext(Dispatchers.IO) { UpdateChecker().checkForUpdate(prefs.selectedChannel) }
            when (result) {
                is UpdateChecker.CheckResult.UpToDate -> ui = ui.copy(checkStatus = CheckStatus.UP_TO_DATE)
                is UpdateChecker.CheckResult.ChannelBehind -> ui = ui.copy(checkStatus = CheckStatus.CHANNEL_BEHIND)
                is UpdateChecker.CheckResult.CheckFailed -> ui = ui.copy(checkStatus = CheckStatus.CHECK_FAILED)
                is UpdateChecker.CheckResult.UpdateAvailable -> {
                    onUpdateFound(result.info)
                    ui = ui.copy(checkStatus = CheckStatus.UPDATE_AVAILABLE)
                    refresh()
                    sheetVisible = true
                }
            }
        }
    }

    /**
     * Looks for an update when the app is opened (on either channel). At most once per five
     * minutes, because GitHub allows about 60 unauthenticated requests an hour per network address.
     */
    fun checkOnLaunchIfEligible() {
        if (!prefs.autoCheck) return
        if (prefs.isWithinQuietWindow(app)) return
        val now = System.currentTimeMillis()
        if (now - prefs.lastLaunchCheckAt < LAUNCH_CHECK_MIN_INTERVAL_MS) return
        prefs.lastLaunchCheckAt = now
        prefs.clearIfNotNewerThan(BuildConfig.UPDATE_VERSION)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try { UpdateChecker().checkForUpdate(prefs.selectedChannel) } catch (_: Exception) { null }
            }
            if (result is UpdateChecker.CheckResult.UpdateAvailable) onUpdateFound(result.info)
            refresh()
            if (shouldShowSheet()) sheetVisible = true
        }
    }

    /** Something already pending or downloaded from an earlier check: show the sheet again on launch. */
    fun showSheetIfPending() {
        refresh()
        if (shouldShowSheet()) sheetVisible = true
    }

    /** Stores the found release as pending and, on Wi-Fi, starts downloading it. */
    private suspend fun onUpdateFound(info: UpdateInfo) {
        // A different version's download can still be in flight: cancel it first, so two watchers
        // never race to write the same prefs.
        if (prefs.activeDownloadId != -1L && prefs.pendingUpdateVersion != null && prefs.pendingUpdateVersion != info.versionName) {
            withContext(Dispatchers.IO) { UpdateDownloader.cancelDownload(app, prefs.activeDownloadId) }
            prefs.activeDownloadId = -1L
            pollJob?.cancel()
        }
        prefs.pendingUpdateVersion = info.versionName
        prefs.pendingUpdateUrl = info.downloadUrl
        prefs.pendingReleaseNotes = info.releaseNotes
        prefs.pendingReleaseTitle = info.releaseTitle

        val alreadyDownloading = prefs.activeDownloadId != -1L && prefs.pendingUpdateVersion == info.versionName
        when {
            info.versionName == prefs.downloadedVersion && prefs.downloadedApkExists() -> Unit   // ready
            alreadyDownloading -> Unit
            UpdateDownloader.isWifiConnected(app) -> {
                prefs.pendingNoWifi = false
                prefs.sheetDismissedUntil = 0L
                prefs.activeDownloadId = UpdateDownloader.startDownload(app, info)
            }
            else -> prefs.pendingNoWifi = true
        }
    }

    private fun shouldShowSheet(): Boolean {
        // "Wait between updates": no automatic popup during the window, even for something pending
        if (prefs.isWithinQuietWindow(app)) return false
        val hasDownloaded = prefs.downloadedApkPath != null && prefs.downloadedApkExists() &&
            UpdateChecker.isNewerThanInstalled(prefs.downloadedVersion)
        val hasPending = UpdateChecker.isNewerThanInstalled(prefs.pendingUpdateVersion)
        if (!hasPending && !hasDownloaded) return false
        return System.currentTimeMillis() > prefs.sheetDismissedUntil
    }

    // ───────────────────────────── the sheet ─────────────────────────────

    fun onSheetShown() {
        refresh()
        // The user has now been shown this version's notes
        (prefs.pendingUpdateVersion ?: prefs.downloadedVersion)?.let { prefs.updateSheetSeenVersion = it }
    }

    /** Closing the sheet is not declining an update that is actively downloading. */
    fun dismissSheet() {
        sheetVisible = false
        when (ui.downloadPhase) {
            DownloadPhase.DOWNLOADING, DownloadPhase.PROCESSING, DownloadPhase.CANCELING -> Unit
            DownloadPhase.READY -> prefs.sheetDismissedUntil = System.currentTimeMillis() + 60 * 60 * 1000
            DownloadPhase.IDLE, DownloadPhase.NO_WIFI_PENDING ->
                prefs.sheetDismissedUntil = System.currentTimeMillis() + 24 * 60 * 60 * 1000
        }
    }

    fun startUserDownload() {
        val url = prefs.pendingUpdateUrl ?: return
        val version = prefs.pendingUpdateVersion ?: return
        val info = UpdateInfo(
            versionName = version,
            channel = prefs.selectedChannel,
            downloadUrl = url,
            releaseNotes = prefs.pendingReleaseNotes ?: "",
            releaseTitle = prefs.pendingReleaseTitle ?: "",
        )
        val id = UpdateDownloader.startDownload(app, info)
        prefs.activeDownloadId = id
        prefs.pendingNoWifi = false
        prefs.sheetDismissedUntil = 0L
        ui = ui.copy(
            downloadPhase = DownloadPhase.DOWNLOADING,
            downloadProgress = 0f,
            downloadStatusText = null,
            downloadSpeedText = null,
        )
        pollDownload(id)
    }

    fun showCancelConfirm() { ui = ui.copy(cancelConfirmVisible = true) }
    fun hideCancelConfirm() { ui = ui.copy(cancelConfirmVisible = false) }
    fun showInstallCard() { ui = ui.copy(showInstallCard = true) }

    fun cancelDownload() {
        val id = prefs.activeDownloadId
        ui = ui.copy(
            downloadPhase = DownloadPhase.CANCELING,
            cancelConfirmVisible = false,
            downloadStatusText = null,
            downloadSpeedText = null,
        )
        // Mark this id as no longer active BEFORE the (slow) cancel, so the poll loop stops
        // writing progress over the CANCELING state.
        prefs.activeDownloadId = -1L
        prefs.clearPending()
        scope.launch {
            if (id != -1L) withContext(Dispatchers.IO) { UpdateDownloader.cancelDownload(app, id) }
            sheetVisible = false
            ui = ui.copy(downloadPhase = DownloadPhase.IDLE)
        }
    }

    fun confirmInstall() {
        val path = prefs.downloadedApkPath ?: return
        prefs.hasInstalledUpdate = true
        UpdateDownloader.installApk(app, path)
    }

    // ───────────────────────────── download polling ─────────────────────────────

    private fun pollDownload(id: Long) {
        val trackedVersion = prefs.pendingUpdateVersion
        pollJob?.cancel()
        pollJob = scope.launch {
            val etaTracker = DownloadEtaTracker()
            while (true) {
                if (prefs.activeDownloadId != id) {
                    resolveIfFinishedElsewhere(trackedVersion)
                    return@launch
                }
                val progress = withContext(Dispatchers.IO) { UpdateDownloader.queryProgress(app, id) }
                if (prefs.activeDownloadId != id) {
                    resolveIfFinishedElsewhere(trackedVersion)
                    return@launch
                }
                when (progress) {
                    is UpdateDownloader.DownloadProgress.InProgress -> {
                        val sampled = etaTracker.sample(
                            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                            downloadedBytes = progress.downloadedBytes,
                            totalBytes = progress.totalBytes,
                            activelyRunning = progress.statusText == null,
                        )
                        ui = ui.copy(
                            downloadPhase = DownloadPhase.DOWNLOADING,
                            downloadProgress = progress.fraction,
                            downloadStatusText = progress.statusText,
                            downloadSpeedText = sampled?.let { (bps, eta) -> formatSpeedAndEta(app, bps, eta) },
                        )
                    }
                    is UpdateDownloader.DownloadProgress.Complete -> {
                        ui = ui.copy(downloadPhase = DownloadPhase.PROCESSING, downloadStatusText = null, downloadSpeedText = null)
                        prefs.downloadedVersion = prefs.pendingUpdateVersion
                        prefs.downloadedApkPath = progress.localUri
                        prefs.activeDownloadId = -1L
                        prefs.clearPending()
                        delay(800)
                        ui = ui.copy(downloadPhase = DownloadPhase.READY)
                        return@launch
                    }
                    is UpdateDownloader.DownloadProgress.Failed -> {
                        ui = ui.copy(downloadPhase = DownloadPhase.DOWNLOADING, downloadStatusText = progress.reasonText, downloadSpeedText = null)
                        prefs.activeDownloadId = -1L
                        delay(4000)
                        ui = ui.copy(downloadPhase = DownloadPhase.IDLE, downloadStatusText = null)
                        return@launch
                    }
                    else -> Unit
                }
                delay(500)
            }
        }
    }

    private fun resolveIfFinishedElsewhere(trackedVersion: String?) {
        if (trackedVersion != null && trackedVersion == prefs.downloadedVersion && prefs.downloadedApkExists()) {
            ui = ui.copy(downloadPhase = DownloadPhase.READY, downloadStatusText = null, downloadSpeedText = null)
        }
    }

    private companion object {
        const val LAUNCH_CHECK_MIN_INTERVAL_MS = 5 * 60 * 1000L
    }
}
