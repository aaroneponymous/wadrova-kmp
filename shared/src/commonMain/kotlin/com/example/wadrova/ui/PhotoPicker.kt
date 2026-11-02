package com.example.wadrova.ui

import androidx.compose.runtime.Composable
import com.example.wadrova.data.PickedPhoto

sealed interface PhotoPickResult {
    data object Cancelled : PhotoPickResult
    data object Failed : PhotoPickResult
    data class Picked(val photo: PickedPhoto) : PhotoPickResult
}

/** What came back from one multi-selection, in the order selected. Nothing at all means it was cancelled. */
data class PhotoBatch(val photos: List<PickedPhoto>, val failed: Int = 0) {
    val isCancelled: Boolean get() = photos.isEmpty() && failed == 0
}

/** Returns a function that opens the system photo picker, which needs no photo-library permission. */
@Composable
expect fun rememberPhotoPicker(onResult: (PhotoPickResult) -> Unit): () -> Unit

/** Like [rememberPhotoPicker], but the user selects up to [maxItems] photos and confirms them together. */
@Composable
expect fun rememberMultiPhotoPicker(maxItems: Int, onResult: (PhotoBatch) -> Unit): () -> Unit
