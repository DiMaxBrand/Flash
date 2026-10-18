package com.example.flash.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.example.flash.R
import java.io.File

object UpdateDownloader {

    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    // Update APKs live in their own subfolder, never shared with received files or anything else.
    const val UPDATES_SUBDIR = "flash_updates"

    fun startDownload(context: Context, info: UpdateInfo): Long {
        // Only drops the stale "downloaded and ready" pointer from prefs, not the pending title/notes
        // the caller has just set for THIS download.
        UpdatePreferences(context).clearDownloadedApk()
        val subPath = "$UPDATES_SUBDIR/flash-update-${info.versionName}.apk"
        val destination = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), subPath)
        if (destination.exists()) destination.delete()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(info.downloadUrl))
            .setTitle("Flash ${info.versionName}")
            .setDescription("Downloading update…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, subPath)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        return dm.enqueue(request)
    }

    fun cancelDownload(context: Context, downloadId: Long) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.remove(downloadId)
    }

    fun queryProgress(context: Context, downloadId: Long): DownloadProgress {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(downloadId)
        dm.query(query).use { cursor ->
            if (!cursor.moveToFirst()) return DownloadProgress.Unknown
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val fraction = if (total > 0) downloaded.toFloat() / total.toFloat() else 0f
            return when (status) {
                DownloadManager.STATUS_RUNNING ->
                    DownloadProgress.InProgress(fraction, statusText = null, downloaded, total)
                DownloadManager.STATUS_PENDING ->
                    DownloadProgress.InProgress(
                        fraction,
                        context.getString(R.string.update_download_status_queued),
                        downloaded,
                        total,
                    )
                DownloadManager.STATUS_PAUSED ->
                    DownloadProgress.InProgress(fraction, pausedReasonText(context, reason), downloaded, total)
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    DownloadProgress.Complete(localUri)
                }
                DownloadManager.STATUS_FAILED ->
                    DownloadProgress.Failed(failedReasonText(context, reason))
                else -> DownloadProgress.Unknown
            }
        }
    }

    private fun pausedReasonText(context: Context, reason: Int): String = when (reason) {
        DownloadManager.PAUSED_WAITING_FOR_NETWORK ->
            context.getString(R.string.update_download_status_waiting_for_network)
        DownloadManager.PAUSED_WAITING_TO_RETRY -> context.getString(R.string.update_download_status_retrying)
        DownloadManager.PAUSED_QUEUED_FOR_WIFI ->
            context.getString(R.string.update_download_status_waiting_for_wifi)
        else -> context.getString(R.string.update_download_status_paused)
    }

    private fun failedReasonText(context: Context, reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> context.getString(R.string.update_download_error_insufficient_space)
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> context.getString(R.string.update_download_error_device_not_found)
        DownloadManager.ERROR_CANNOT_RESUME -> context.getString(R.string.update_download_error_cannot_resume)
        DownloadManager.ERROR_HTTP_DATA_ERROR -> context.getString(R.string.update_download_error_http_data)
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> context.getString(R.string.update_download_error_too_many_redirects)
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> context.getString(R.string.update_download_error_unhandled_http_code)
        DownloadManager.ERROR_FILE_ERROR -> context.getString(R.string.update_download_error_file_error)
        else -> context.getString(R.string.update_download_error_generic)
    }

    fun installApk(context: Context, filePath: String) {
        val file = File(Uri.parse(filePath).path ?: filePath)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    sealed class DownloadProgress {
        data class InProgress(
            val fraction: Float,
            val statusText: String? = null,
            val downloadedBytes: Long = 0L,
            val totalBytes: Long = 0L,
        ) : DownloadProgress()
        data class Complete(val localUri: String) : DownloadProgress()
        data class Failed(val reasonText: String) : DownloadProgress()
        object Unknown : DownloadProgress()
    }
}
