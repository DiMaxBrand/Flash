package com.dimax.flash.update

data class UpdateInfo(
    val versionName: String,
    val channel: UpdateChannel,
    val downloadUrl: String,
    val releaseNotes: String,
    // GitHub release title (e.g. "Flash 0.12.0: ...") — blank falls back to just "Flash".
    val releaseTitle: String = "",
)
