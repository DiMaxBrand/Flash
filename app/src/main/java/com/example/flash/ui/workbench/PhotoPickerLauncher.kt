package com.example.flash.ui.workbench

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** The three things the "+" button can add. */
data class PhotoPickerState(
    val launchPhotos: () -> Unit,
    val launchVideos: () -> Unit,
    val launchFiles: () -> Unit
) {
    /** Kept for callers that only know about photos. */
    val launch: () -> Unit get() = launchPhotos
}

@Composable
fun rememberPhotoPicker(onPhotos: (List<Uri>) -> Unit, onMedia: (List<Uri>) -> Unit): PhotoPickerState {
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { onPhotos(it) }
    val videos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { onMedia(it) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { onMedia(it) }

    return remember(photos, videos, files) {
        PhotoPickerState(
            launchPhotos = { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            launchVideos = { videos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
            launchFiles = { files.launch(arrayOf("*/*")) }
        )
    }
}

/** Dropdown with Photos / Videos / Files, shown under the "+" button. */
@Composable
fun AddMenu(expanded: Boolean, onDismiss: () -> Unit, picker: PhotoPickerState) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("Photos") }, onClick = { onDismiss(); picker.launchPhotos() })
        DropdownMenuItem(text = { Text("Videos") }, onClick = { onDismiss(); picker.launchVideos() })
        DropdownMenuItem(text = { Text("Files") }, onClick = { onDismiss(); picker.launchFiles() })
    }
}
