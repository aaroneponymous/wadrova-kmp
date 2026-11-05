package com.example.wadrova.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.example.wadrova.domain.GarmentCategory
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Studio Clean, version 1: a deterministic way to present a garment's real cutout. It's composed at display time from
 * the stored transparent PNG, which is never changed or re-stored. Clean frames the garment's visible pixels at a
 * consistent size and position on a soft studio background with a faint floor shadow. It doesn't touch the pixels:
 * no recoloring, sharpening, smoothing, mask erosion or speck removal, and nothing is invented. It's not generated
 * product photography.
 */

/** The smallest rectangle holding every pixel with alpha > 0; [right] and [bottom] are exclusive. */
data class VisibleBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** The visible bounds of ARGB pixels (as `ImageBitmap.readPixels` returns them), or null if nothing is visible. */
fun visibleBounds(width: Int, height: Int, argb: IntArray): VisibleBounds? {
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            if ((argb[row + x] ushr 24) != 0) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                bottom = y
            }
        }
    }
    return if (right < 0) null else VisibleBounds(left, top, right + 1, bottom + 1)
}

/** Where Clean draws the garment and its shadow inside a frame, in the frame's pixels. */
data class CleanLayout(
    val garmentLeft: Float,
    val garmentTop: Float,
    val garmentWidth: Float,
    val garmentHeight: Float,
    val shadowLeft: Float,
    val shadowTop: Float,
    val shadowWidth: Float,
    val shadowHeight: Float,
    /** Frame pixels per source pixel. Never above [CLEAN_MAX_UPSCALE]. */
    val scale: Float,
)

/** Padding on every side of the frame, as a fraction of its smaller side. */
const val CLEAN_PADDING = 0.09f

/**
 * A small cutout is shown smaller rather than enlarged into blur: at most 4 frame pixels per source pixel. (A 1600 px
 * upload gives cutouts of roughly 70 to 600 px.)
 */
const val CLEAN_MAX_UPSCALE = 4f

/**
 * How much of the padded area a garment's longer fitting side fills. The values make categories read at a believable
 * relative size, as a product page does: a coat fills the frame, a shoe or bag sits smaller.
 */
fun cleanFill(category: GarmentCategory): Float = when (category) {
    GarmentCategory.OUTERWEAR, GarmentCategory.DRESS -> 0.94f
    GarmentCategory.BOTTOM -> 0.9f
    GarmentCategory.TOP -> 0.86f
    GarmentCategory.FOOTWEAR -> 0.64f
    GarmentCategory.ACCESSORY -> 0.62f
}

/**
 * Deterministic: the same frame, bounds and category always give the same layout. The garment keeps its aspect ratio
 * (one uniform scale), is never cropped (the whole visible area fits inside the padding), is centered horizontally,
 * and sits slightly above the middle, where the eye expects the subject, with a faint shadow just under its lowest
 * edge.
 */
fun cleanLayout(frameWidth: Float, frameHeight: Float, bounds: VisibleBounds, category: GarmentCategory): CleanLayout {
    val pad = min(frameWidth, frameHeight) * CLEAN_PADDING
    val availableWidth = frameWidth - 2 * pad
    val availableHeight = frameHeight - 2 * pad
    val fill = cleanFill(category)
    val fit = min(availableWidth * fill / bounds.width, availableHeight * fill / bounds.height)
    val scale = min(fit, CLEAN_MAX_UPSCALE)
    val width = bounds.width * scale
    val height = bounds.height * scale
    val left = (frameWidth - width) / 2
    // Optical center at 46 % of the height, kept inside the padding.
    val top = (frameHeight * 0.46f - height / 2).coerceIn(pad, max(pad, frameHeight - pad - height))
    val shadowWidth = max(width * 0.72f, frameWidth * 0.18f).coerceAtMost(availableWidth)
    val shadowHeight = max(frameHeight * 0.03f, 4f)
    return CleanLayout(
        garmentLeft = left,
        garmentTop = top,
        garmentWidth = width,
        garmentHeight = height,
        shadowLeft = (frameWidth - shadowWidth) / 2,
        shadowTop = top + height - shadowHeight * 0.45f,
        shadowWidth = shadowWidth,
        shadowHeight = shadowHeight,
        scale = scale,
    )
}

private class CleanSource(val bitmap: ImageBitmap, val bounds: VisibleBounds)

private sealed interface CleanLoad {
    data object Loading : CleanLoad
    data object Unavailable : CleanLoad
    data class Ready(val source: CleanSource) : CleanLoad
}

/**
 * The Clean presentation of [cutoutId] for [item], filling its modifier's bounds. Shows [fallback] when the cutout
 * file is missing or has no visible pixels, so the original presentation's fallbacks still apply.
 */
@Composable
fun StudioCleanVisual(item: WardrobeItem, cutoutId: String, modifier: Modifier = Modifier, fallback: @Composable () -> Unit) {
    val loader = LocalPhotoLoader.current
    val state by produceState<CleanLoad>(CleanLoad.Loading, cutoutId, loader) {
        val bitmap = loader?.loadCutout(cutoutId)
        val bounds = bitmap?.let { loader.cleanBounds(cutoutId, it) }
        value = if (bitmap != null && bounds != null) CleanLoad.Ready(CleanSource(bitmap, bounds)) else CleanLoad.Unavailable
    }
    when (val current = state) {
        CleanLoad.Loading -> Unit
        CleanLoad.Unavailable -> fallback()
        is CleanLoad.Ready -> CleanCanvas(current.source, item, modifier)
    }
}

@Composable
private fun CleanCanvas(source: CleanSource, item: WardrobeItem, modifier: Modifier) {
    val category = item.garment.category
    Canvas(modifier.semantics { contentDescription = "${item.name}, Clean view" }) {
        drawRect(Brush.verticalGradient(listOf(WadrovaColors.Ivory, WadrovaColors.Studio)))
        val layout = cleanLayout(size.width, size.height, source.bounds, category)
        // A faint floor shadow: a soft ellipse, the same on both platforms. It never touches the garment's pixels.
        drawOval(
            Brush.radialGradient(
                listOf(WadrovaColors.Charcoal.copy(alpha = 0.10f), WadrovaColors.Charcoal.copy(alpha = 0f)),
                center = Offset(layout.shadowLeft + layout.shadowWidth / 2, layout.shadowTop + layout.shadowHeight / 2),
                radius = layout.shadowWidth / 2,
            ),
            topLeft = Offset(layout.shadowLeft, layout.shadowTop),
            size = Size(layout.shadowWidth, layout.shadowHeight),
        )
        // One draw of the stored pixels: only the visible region, scaled once, no other processing.
        drawImage(
            source.bitmap,
            srcOffset = IntOffset(source.bounds.left, source.bounds.top),
            srcSize = IntSize(source.bounds.width, source.bounds.height),
            dstOffset = IntOffset(layout.garmentLeft.roundToInt(), layout.garmentTop.roundToInt()),
            dstSize = IntSize(layout.garmentWidth.roundToInt().coerceAtLeast(1), layout.garmentHeight.roundToInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.High,
        )
    }
}

/** Reads the cutout's alpha once, off the main thread. */
internal suspend fun computeVisibleBounds(bitmap: ImageBitmap): VisibleBounds? = try {
    withContext(Dispatchers.Default) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.readPixels(pixels)
        visibleBounds(bitmap.width, bitmap.height, pixels)
    }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
