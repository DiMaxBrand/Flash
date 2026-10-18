package com.dimax.flash.ui.workbench

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.io.File

/** What a transferred item is: a picture, a video (shown by its first frame) or any other file. */
enum class MediaKind { IMAGE, VIDEO, FILE }

/** Display data for a Uri that is not a plain picture: its kind, name and (videos) a first-frame poster. */
class MediaInfo(val kind: MediaKind, val name: String, val poster: Bitmap? = null)

/**
 * Where grid cells, the orbit and the cards look up how to draw a Uri. Snapshot state, so a poster that
 * arrives later redraws everything that shows it. Uris that are not here are plain pictures.
 */
object MediaRegistry {
    val infos = mutableStateMapOf<Uri, MediaInfo>()
}

fun kindOfMime(mime: String?): MediaKind = when {
    mime == null -> MediaKind.FILE
    mime.startsWith("image/") -> MediaKind.IMAGE
    mime.startsWith("video/") -> MediaKind.VIDEO
    else -> MediaKind.FILE
}

fun mimeOfName(name: String): String? =
    MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())

fun kindOfFile(file: File): MediaKind = kindOfMime(mimeOfName(file.name))

fun displayName(context: Context, uri: Uri): String {
    if (uri.scheme == "content") {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0)?.let { n -> return n }
            }
        }
    }
    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    // Received files are cached as "<index>_<name>": show (and resend) the real name
    return if (uri.scheme == "file") name.replaceFirst(Regex("^\\d+_"), "") else name
}

/** First frame of a video, at most [maxSide] px on its long side, or null. */
fun videoPoster(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
    val r = MediaMetadataRetriever()
    try {
        r.setDataSource(context, uri)
        val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        val scale = minOf(1f, maxSide.toFloat() / maxOf(frame.width, frame.height))
        if (scale >= 1f) frame
        else Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1), (frame.height * scale).toInt().coerceAtLeast(1), true)
    } finally {
        r.release()
    }
}.getOrNull()

/** Draws a Uri according to the registry: pictures as usual, videos by their poster with a play mark, files as a tile. */
@Composable
fun MediaImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val info = (model as? Uri)?.let { MediaRegistry.infos[it] }
    when (info?.kind) {
        MediaKind.VIDEO -> Box(modifier) {
            val poster = info.poster
            if (poster != null) {
                Image(poster.asImageBitmap(), contentDescription, Modifier.fillMaxSize(), contentScale = contentScale)
            } else {
                FileTile(info.name, video = true, modifier = Modifier.fillMaxSize())
            }
            PlayMark(Modifier.align(Alignment.Center))
        }
        MediaKind.FILE -> FileTile(info.name, video = false, modifier = modifier)
        else -> AsyncImage(model = model, contentDescription = contentDescription, contentScale = contentScale, modifier = modifier)
    }
}

@Composable
fun PlayMark(modifier: Modifier = Modifier) {
    Box(
        modifier.size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center
    ) { Text("▶", color = Color.White, fontSize = 14.sp) }
}

/** A tile for a file without a picture: its extension large, its name small. */
@Composable
fun FileTile(name: String, video: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.secondaryContainer).padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val ext = name.substringAfterLast('.', "").uppercase().take(5).ifEmpty { if (video) "VIDEO" else "FILE" }
            Text(ext, color = MaterialTheme.colorScheme.onSecondaryContainer, fontSize = 18.sp, maxLines = 1)
            Text(
                name, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}
