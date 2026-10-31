package com.example.wadrova.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

/** SVG path data on a 100 x 120 viewport: the filled silhouette, then its stroked details. */
enum class GarmentIllustrationType(val shape: String, val details: String) {
    TEE(
        "M36 18 L24 22 L8 38 L20 50 L28 43 L28 104 L72 104 L72 43 L80 50 L92 38 L76 22 L64 18 Q50 28 36 18 Z",
        "M31 98 L69 98",
    ),
    SHIRT(
        "M38 14 L26 19 L14 34 L8 98 L20 100 L28 52 L28 108 L72 108 L72 52 L80 100 L92 98 L86 34 L74 19 L62 14 L50 24 Z",
        "M38 14 L44 28 L50 24 L56 28 L62 14 M50 24 L50 108",
    ),
    KNIT(
        "M38 14 L26 19 L14 34 L8 98 L20 100 L28 52 L28 106 L72 106 L72 52 L80 100 L92 98 L86 34 L74 19 L62 14 Q50 24 38 14 Z",
        "M28 98 L72 98 M9 90 L21 92 M91 90 L79 92 M36 18 Q50 28 64 18",
    ),
    TROUSERS(
        "M32 10 L68 10 L78 112 L56 112 L50 42 L44 112 L22 112 Z",
        "M32 17 L68 17 M50 17 L50 42",
    ),
    JEANS(
        "M32 10 L68 10 L74 112 L55 112 L50 42 L45 112 L26 112 Z",
        "M32 17 L68 17 M50 17 L50 42 M36 17 Q38 30 31 32 M64 17 Q62 30 69 32",
    ),
    DRESS(
        "M40 10 L44 10 L46 24 L54 24 L56 10 L60 10 L64 32 L58 50 L78 112 L22 112 L42 50 L36 32 Z",
        "M42 50 L58 50",
    ),
    COAT(
        "M38 8 L26 13 L14 30 L8 88 L20 90 L27 50 L25 114 L75 114 L73 50 L80 90 L92 88 L86 30 L74 13 L62 8 L50 30 Z",
        "M38 8 L42 34 L50 30 L58 34 L62 8 M50 30 L50 114",
    ),
    JACKET(
        "M38 16 L26 21 L14 36 L8 92 L20 94 L27 54 L26 100 L74 100 L73 54 L80 94 L92 92 L86 36 L74 21 L62 16 L50 38 Z",
        "M38 16 L42 42 L50 38 L58 42 L62 16 M50 38 L50 100",
    ),
    SNEAKER(
        "M10 62 L12 44 L32 44 L52 60 L82 68 Q92 72 90 82 L10 82 Z",
        "M10 75 L90 75 M36 48 L30 56 M44 54 L38 62",
    ),
    BOOT(
        "M28 18 L56 18 L56 60 L82 72 Q90 76 88 92 L26 92 Z",
        "M26 84 L88 84 M28 28 L56 28",
    ),
    BAG(
        "M20 42 L80 42 L86 100 L14 100 Z",
        "M34 42 Q34 14 50 14 Q66 14 66 42",
    ),
    SCARF(
        "M30 12 Q50 26 70 12 L76 26 Q66 42 58 50 L68 108 L51 110 L50 66 L40 106 L24 100 L41 50 Q32 42 24 26 Z",
        "M26 22 Q50 42 74 22 M41 50 Q50 56 58 50 M66 98 L51 100 M38 97 L26 92",
    ),
    HAT(
        "M14 78 Q30 72 30 72 Q30 36 50 36 Q70 36 70 72 Q70 72 86 78 Q88 88 50 88 Q12 88 14 78 Z",
        "M30 72 Q50 64 70 72",
    ),
    BELT(
        "M8 54 L92 54 L92 68 L8 68 Z",
        "M26 50 L42 50 L42 72 L26 72 Z M34 61 L50 61",
    ),
}

/** Draws the item's illustration, fitted and centered without cropping. */
@Composable
fun GarmentIllustration(item: WardrobeItem, modifier: Modifier = Modifier) {
    val type = item.illustration
    val shape = remember(type) { PathParser().parsePathString(type.shape).toPath() }
    val details = remember(type) { PathParser().parsePathString(type.details).toPath() }
    val line = if (item.tint.luminance() < 0.2f) Color.White.copy(alpha = 0.45f) else WadrovaColors.Charcoal.copy(alpha = 0.4f)
    val outline = WadrovaColors.Charcoal.copy(alpha = 0.35f)

    Canvas(modifier.semantics { contentDescription = "Illustration of ${item.name}" }) {
        val fit = min(size.width / VIEWPORT_WIDTH, size.height / VIEWPORT_HEIGHT)
        val left = (size.width - VIEWPORT_WIDTH * fit) / 2
        val top = (size.height - VIEWPORT_HEIGHT * fit) / 2
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
