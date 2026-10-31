package com.example.wadrova.ui

import androidx.compose.runtime.Composable
import com.example.wadrova.data.PickedPhoto

sealed interface PhotoPickResult {
    data object Cancelled : PhotoPickResult
    data object Failed : PhotoPickResult
    data class Picked(val photo: PickedPhoto) : PhotoPickResult
}

/** Returns a function that opens the system photo picker, which needs no photo-library permission. */
@Composable
expect fun rememberPhotoPicker(onResult: (PhotoPickResult) -> Unit): () -> Unit
