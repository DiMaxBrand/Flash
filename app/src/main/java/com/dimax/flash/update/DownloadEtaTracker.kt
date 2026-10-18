package com.dimax.flash.update

import android.content.Context
import android.text.format.Formatter
import com.dimax.flash.R
import kotlin.math.roundToLong

/**
 * Tracks download throughput and a smoothed ETA across repeated polls of the same download.
 * Originally lived inline in one caller (`UpdatesActivity`); `UpdateSheetFragment` polls the same
 * [UpdateDownloader.queryProgress] but never had this logic ported over, so its ETA text simply
 * never rendered — sharing one tracker instead of two independently-maintained copies is what
 * keeps that from silently happening again.
 */
class DownloadEtaTracker {
    // Speed comes from a sliding window of the last ~4s of (time, bytes) samples, not a single
    // 500ms delta — individual polls are bursty (the OS flushes to disk in chunks, not a smooth
    // stream), so a single-tick rate genuinely alternates between "burst" and "nothing" every
    // poll or two. Averaging over several real seconds of samples smooths that out at the source.
    private val samples = ArrayDeque<Pair<Long, Long>>() // elapsedRealtime ms to downloadedBytes
    private val windowMs = 4000L

    // The *displayed* ETA is its own gently-corrected countdown, not a fresh division result
    // redrawn every poll: between polls it ticks down by real elapsed time, then nudges a
    // quarter of the way toward the newly measured estimate. Averaging the displayed number
    // against itself (e.g. new = (old + fresh) / 2) decays geometrically and mathematically
    // never reaches zero; nudging a live countdown that's independently ticking down with real
    // time does reach zero, because actual elapsed seconds are doing the counting, not an
    // infinite series of halvings.
    private var displayedEtaSeconds: Double? = null
    private var lastPollAt = 0L

    /**
     * Feeds one poll's raw numbers in; returns the bytes/sec + smoothed ETA-seconds pair to
     * display, or null when there's nothing meaningful to show right now (not actively running,
     * or not enough samples yet).
     */
    fun sample(
        nowElapsedRealtimeMs: Long,
        downloadedBytes: Long,
        totalBytes: Long,
        activelyRunning: Boolean,
    ): Pair<Double, Double>? {
        if (!activelyRunning || totalBytes <= 0) {
            // Not actively running — reset so a stale rate/countdown doesn't carry over into the
            // next running stretch (e.g. after pause/resume).
            reset()
            return null
        }
        samples.addLast(nowElapsedRealtimeMs to downloadedBytes)
        while (samples.isNotEmpty() && nowElapsedRealtimeMs - samples.first().first > windowMs) {
            samples.removeFirst()
        }
        val (oldestAt, oldestBytes) = samples.first()
        val deltaMs = nowElapsedRealtimeMs - oldestAt
        val deltaBytes = downloadedBytes - oldestBytes
        val windowBps = if (deltaMs > 200 && deltaBytes >= 0) deltaBytes * 1000.0 / deltaMs else 0.0

        val elapsedSincePoll = if (lastPollAt != 0L) (nowElapsedRealtimeMs - lastPollAt) / 1000.0 else 0.0
        lastPollAt = nowElapsedRealtimeMs

        if (windowBps <= 0.0) return null
        val remainingBytes = totalBytes - downloadedBytes
        val rawEtaSeconds = remainingBytes / windowBps
        val current = displayedEtaSeconds
        val eta = if (current == null) {
            rawEtaSeconds
        } else {
            val ticked = (current - elapsedSincePoll).coerceAtLeast(0.0)
            ticked + (rawEtaSeconds - ticked) * 0.25
        }
        displayedEtaSeconds = eta
        return windowBps to eta
    }

    fun reset() {
        samples.clear()
        displayedEtaSeconds = null
        lastPollAt = 0L
    }
}

fun formatSpeedAndEta(context: Context, bytesPerSecond: Double, etaSecondsRaw: Double): String? {
    if (bytesPerSecond <= 0.0) return null
    val speedText = Formatter.formatShortFileSize(context, bytesPerSecond.toLong()) + "/s"
    // Rounded, not truncated: truncation always displays a smaller number than reality, which
    // reads as the countdown "sticking" a beat longer than it should before dropping.
    val etaSeconds = etaSecondsRaw.roundToLong().coerceAtLeast(0)
    val etaText = when {
        etaSeconds < 60 -> context.getString(R.string.download_eta_seconds, etaSeconds)
        etaSeconds < 3600 ->
            context.getString(R.string.download_eta_minutes_seconds, etaSeconds / 60, etaSeconds % 60)
        else ->
            context.getString(
                R.string.download_eta_hours_minutes,
                etaSeconds / 3600,
                (etaSeconds % 3600) / 60,
            )
    }
    return "$speedText · $etaText"
}
