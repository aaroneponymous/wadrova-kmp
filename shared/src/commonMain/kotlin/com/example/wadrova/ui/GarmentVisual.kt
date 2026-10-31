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
import com.example.wadrova.data.PhotoSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap

/** Decodes stored photos off the main thread with a small cache. Photo ids are immutable, so entries never go stale. */
class PhotoBitmapLoader(private val photos: GarmentPhotoStore) {
    private val cache = mutableMapOf<PhotoSize, LinkedHashMap<String, ImageBitmap>>()

    /** Returns null if the file is missing or can't be decoded. Call from the main thread. */
    suspend fun load(id: String, size: PhotoSize): ImageBitmap? {
        val entries = cache.getOrPut(size) { LinkedHashMap() }
        entries.remove(id)?.let { cached ->
            entries[id] = cached
            return cached
        }
        val bitmap = try {
            photos.read(id, size)?.let { bytes -> withContext(Dispatchers.Default) { bytes.decodeToImageBitmap() } }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        entries[id] = bitmap
        while (entries.size > size.cacheEntries) entries.remove(entries.keys.first())
        return bitmap
    }

    private val PhotoSize.cacheEntries: Int
        get() = if (this == PhotoSize.DISPLAY) 4 else 24
}

val LocalPhotoLoader = staticCompositionLocalOf<PhotoBitmapLoader?> { null }

private sealed interface PhotoLoad {
    data object Loading : PhotoLoad
    data object Unavailable : PhotoLoad
    data class Ready(val bitmap: ImageBitmap) : PhotoLoad
}

/** Shows a stored photo uncropped, or [fallback] if the file is unavailable. */
@Composable
fun PhotoOrFallback(
    photoId: String,
    size: PhotoSize,
    description: String,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit,
) {
    val loader = LocalPhotoLoader.current
    val load by produceState<PhotoLoad>(PhotoLoad.Loading, photoId, size, loader) {
        value = loader?.load(photoId, size)?.let { PhotoLoad.Ready(it) } ?: PhotoLoad.Unavailable
    }
    when (val current = load) {
        PhotoLoad.Loading -> Unit
        PhotoLoad.Unavailable -> fallback()
        is PhotoLoad.Ready -> Image(current.bitmap, description, modifier, contentScale = ContentScale.Fit)
    }
}

/** The garment's photo where it has one, otherwise its illustration. */
@Composable
fun GarmentVisual(item: WardrobeItem, modifier: Modifier = Modifier, size: PhotoSize = PhotoSize.THUMBNAIL) {
    val photoId = item.photoId
    if (photoId == null) {
        GarmentIllustration(item, modifier)
    } else {
        PhotoOrFallback(photoId, size, "Photo of ${item.name}", modifier) { GarmentIllustration(item, modifier) }
    }
}
