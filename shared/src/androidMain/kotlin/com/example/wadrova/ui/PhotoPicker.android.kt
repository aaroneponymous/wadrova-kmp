package com.example.wadrova.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.example.wadrova.data.PickedPhoto

@Composable
actual fun rememberPhotoPicker(onResult: (PhotoPickResult) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        onResult(if (uri == null) PhotoPickResult.Cancelled else PhotoPickResult.Picked(PickedPhoto(uri.toString())))
    }
    return { launcher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
}

@Composable
actual fun rememberMultiPhotoPicker(maxItems: Int, onResult: (PhotoBatch) -> Unit): () -> Unit {
    // The multiple-selection contract rejects a limit below two, so one slot uses the single picker.
    val contract = remember(maxItems) { PickMultipleVisualMedia(maxItems.coerceAtLeast(2)) }
    val multiple = rememberLauncherForActivityResult(contract) { uris ->
        onResult(PhotoBatch(uris.map { PickedPhoto(it.toString()) }))
    }
    val single = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        onResult(PhotoBatch(listOfNotNull(uri).map { PickedPhoto(it.toString()) }))
    }
    return {
        val request = PickVisualMediaRequest(PickVisualMedia.ImageOnly)
        if (maxItems > 1) multiple.launch(request) else single.launch(request)
    }
}
