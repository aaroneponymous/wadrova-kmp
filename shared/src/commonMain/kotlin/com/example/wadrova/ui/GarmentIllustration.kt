package com.example.wadrova.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.min

private const val VIEWPORT_WIDTH = 100f
private const val VIEWPORT_HEIGHT = 120f

/**
 * SVG path data on a 100 x 120 viewport: the filled silhouette, then its stroked details. One per
 * category, drawn for a piece until it has a photo.
 */
enum class GarmentIllustrationType(val shape: String, val details: String) {
    TEE(
        "M36 18 L24 22 L8 38 L20 50 L28 43 L28 104 L72 104 L72 43 L80 50 L92 38 L76 22 L64 18 Q50 28 36 18 Z",
        "M31 98 L69 98",
    ),
    TROUSERS(
        "M32 10 L68 10 L78 112 L56 112 L50 42 L44 112 L22 112 Z",
        "M32 17 L68 17 M50 17 L50 42",
    ),
    DRESS(
        "M40 10 L44 10 L46 24 L54 24 L56 10 L60 10 L64 32 L58 50 L78 112 L22 112 L42 50 L36 32 Z",
        "M42 50 L58 50",
    ),
    JACKET(
        "M38 16 L26 21 L14 36 L8 92 L20 94 L27 54 L26 100 L74 100 L73 54 L80 94 L92 92 L86 36 L74 21 L62 16 L50 38 Z",
        "M38 16 L42 42 L50 38 L58 42 L62 16 M50 38 L50 100",
    ),
    SNEAKER(
        "M10 62 L12 44 L32 44 L52 60 L82 68 Q92 72 90 82 L10 82 Z",
        "M10 75 L90 75 M36 48 L30 56 M44 54 L38 62",
    ),
    BAG(
        "M20 42 L80 42 L86 100 L14 100 Z",
        "M34 42 Q34 14 50 14 Q66 14 66 42",
    ),
}

/** Room around a trimmed outline for its stroke. */
private const val TRIM_MARGIN = 2f

private val outlineBounds = mutableMapOf<GarmentIllustrationType, Rect>()

/** The area the drawing covers within the viewport, with room for its strokes. */
private val GarmentIllustrationType.outline: Rect
    get() = outlineBounds.getOrPut(this) {
        val shape = PathParser().parsePathString(shape).toPath().getBounds()
        val details = PathParser().parsePathString(details).toPath().getBounds()
        Rect(
            minOf(shape.left, details.left) - TRIM_MARGIN,
            minOf(shape.top, details.top) - TRIM_MARGIN,
            maxOf(shape.right, details.right) + TRIM_MARGIN,
            maxOf(shape.bottom, details.bottom) + TRIM_MARGIN,
        )
    }

/** Height over width of the drawing when trimmed to its outline. */
val GarmentIllustrationType.trimmedAspect: Float
    get() = outline.height / outline.width

/** The part of the viewport's width the trimmed drawing spans. */
val GarmentIllustrationType.trimmedShare: Float
    get() = outline.width / VIEWPORT_WIDTH

/**
 * Draws the item's illustration, fitted and centered without cropping. [trimmed] fits the
 * drawing's own outline instead of the whole viewport, leaving out the empty space around it.
 */
@Composable
fun GarmentIllustration(item: WardrobeItem, modifier: Modifier = Modifier, trimmed: Boolean = false) {
    val type = item.illustration
    val shape = remember(type) { PathParser().parsePathString(type.shape).toPath() }
    val details = remember(type) { PathParser().parsePathString(type.details).toPath() }
    val frame = if (trimmed) type.outline else Rect(0f, 0f, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
    val line = if (item.tint.luminance() < 0.2f) Color.White.copy(alpha = 0.45f) else WadrovaColors.Charcoal.copy(alpha = 0.4f)
    val outline = WadrovaColors.Charcoal.copy(alpha = 0.35f)

    Canvas(modifier.semantics { contentDescription = "Illustration of ${item.name}" }) {
        val fit = min(size.width / frame.width, size.height / frame.height)
        val left = (size.width - frame.width * fit) / 2 - frame.left * fit
        val top = (size.height - frame.height * fit) / 2 - frame.top * fit
        translate(left, top) {
            scale(fit, fit, pivot = Offset.Zero) {
                drawPath(shape, item.tint)
                drawPath(shape, outline, style = Stroke(width = 0.8f))
                val detailsColor = if (type == GarmentIllustrationType.BAG) outline else line
                drawPath(details, detailsColor, style = Stroke(width = if (type == GarmentIllustrationType.BAG) 2.5f else 1f))
            }
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
