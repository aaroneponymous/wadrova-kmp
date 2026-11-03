package com.example.wadrova.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.example.wadrova.data.GarmentPhotoStore
import com.example.wadrova.data.GeneratedImageStore
import com.example.wadrova.data.PhotoSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/** Decodes stored images off the main thread with a small cache. Image ids are immutable, so entries never go stale. */
class PhotoBitmapLoader(private val photos: GarmentPhotoStore, private val generated: GeneratedImageStore) {
    private val cache = mutableMapOf<String, LinkedHashMap<String, ImageBitmap>>()

    /** Returns null if the file is missing or can't be decoded. Call from the main thread. */
    suspend fun load(id: String, size: PhotoSize): ImageBitmap? =
        cached(size.name, id, if (size == PhotoSize.DISPLAY) 4 else 24) { photos.read(id, size) }

    /** Returns null if the file is missing or can't be decoded. Call from the main thread. */
    suspend fun loadGenerated(id: String): ImageBitmap? = cached("generated", id, 4) { generated.read(id) }

    private suspend fun cached(group: String, id: String, capacity: Int, read: suspend () -> ByteArray?): ImageBitmap? {
        val entries = cache.getOrPut(group) { LinkedHashMap() }
        entries.remove(id)?.let { hit ->
            entries[id] = hit
            return hit
        }
        val bitmap = try {
            read()?.let { bytes -> withContext(Dispatchers.Default) { bytes.decodeToImageBitmap() } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        entries[id] = bitmap
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return bitmap
    }
}

val LocalPhotoLoader = staticCompositionLocalOf<PhotoBitmapLoader?> { null }

private sealed interface ImageLoad {
    data object Loading : ImageLoad
    data object Unavailable : ImageLoad
    data class Ready(val bitmap: ImageBitmap) : ImageLoad
}

@Composable
private fun LoadedImage(
    key: Any,
    description: String,
    modifier: Modifier,
    fallback: @Composable () -> Unit,
    load: suspend (PhotoBitmapLoader) -> ImageBitmap?,
) {
    val loader = LocalPhotoLoader.current
    val state by produceState<ImageLoad>(ImageLoad.Loading, key, loader) {
        value = loader?.let { load(it) }?.let { ImageLoad.Ready(it) } ?: ImageLoad.Unavailable
    }
    when (val current = state) {
        ImageLoad.Loading -> Unit
        ImageLoad.Unavailable -> fallback()
        is ImageLoad.Ready -> Image(current.bitmap, description, modifier, contentScale = ContentScale.Fit)
    }
}

/** Shows a stored photo uncropped, or [fallback] if the file is unavailable. */
@Composable
fun PhotoOrFallback(
    photoId: String,
    size: PhotoSize,
    description: String,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) = LoadedImage(photoId to size, description, modifier, fallback) { it.load(photoId, size) }

/** Shows a generated image uncropped, or [fallback] if the file is unavailable. */
@Composable
fun GeneratedImageOrFallback(
    imageId: String,
    description: String,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) = LoadedImage(imageId, description, modifier, fallback) { it.loadGenerated(imageId) }

/**
 * The garment's approved extraction where it has one, then its photo, then its illustration,
 * trimmed to its outline if [trimIllustration] is set.
 */
@Composable
fun GarmentVisual(
    item: WardrobeItem,
    modifier: Modifier = Modifier,
    size: PhotoSize = PhotoSize.THUMBNAIL,
    trimIllustration: Boolean = false,
) {
    val photoOrIllustration: @Composable () -> Unit = {
        val photoId = item.photoId
        if (photoId == null) {
            GarmentIllustration(item, modifier, trimIllustration)
        } else {
            PhotoOrFallback(photoId, size, "Photo of ${item.name}", modifier) { GarmentIllustration(item, modifier, trimIllustration) }
        }
    }
    val extractedId = item.extractedImageId
    if (extractedId == null) {
        photoOrIllustration()
    } else {
        GeneratedImageOrFallback(extractedId, "Photo of ${item.name}", modifier, photoOrIllustration)
    }
}
