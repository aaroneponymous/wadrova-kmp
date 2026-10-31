package com.example.wadrova.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.runtime.Composable
import com.example.wadrova.data.PickedPhoto

@Composable
actual fun rememberPhotoPicker(onResult: (PhotoPickResult) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        onResult(if (uri == null) PhotoPickResult.Cancelled else PhotoPickResult.Picked(PickedPhoto(uri.toString())))
    }
    return { launcher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
}
