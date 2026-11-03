package com.example.wadrova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.wadrova.data.PhotoSize
import com.example.wadrova.domain.GarmentCategory

/** A piece's category and expected height-to-width ratio, which is all the layout needs to know. */
internal data class MoodPiece(val category: GarmentCategory, val aspect: Float)

/** Position and size as fractions of the canvas width, so one layout fits every screen width. */
internal data class MoodSlot(val x: Float, val y: Float, val width: Float, val height: Float)

/** One slot per piece, in the order given, and the canvas height as a fraction of its width. */
internal data class MoodLayout(val slots: List<MoodSlot>, val height: Float)

private const val GAP = 0.05f
private const val WIDE = 0.55f
private const val NARROW = 1f - WIDE - GAP
private const val STAGGER = 0.1f
private const val SOLO = 0.6f
private const val SHOES = 0.3f
private const val ACCESSORY = 0.22f
private const val ACCESSORIES_PER_ROW = 4

/** Which piece leads: a dress is the whole outfit, then a coat layered over it, then the top. */
private val heroOrder = listOf(
    GarmentCategory.DRESS,
    GarmentCategory.OUTERWEAR,
    GarmentCategory.TOP,
    GarmentCategory.BOTTOM,
    GarmentCategory.FOOTWEAR,
    GarmentCategory.ACCESSORY,
)

private fun GarmentCategory.maxWidth(): Float = when (this) {
    GarmentCategory.FOOTWEAR -> SHOES
    GarmentCategory.ACCESSORY -> ACCESSORY
    else -> 1f
}

/**
 * Lays an outfit out as a flat-lay. The leading piece takes a wide column and the rest of the
 * outfit follows in body order, each into whichever column ends higher, so the columns stay even.
 * The narrow column starts lower for an offset, editorial rhythm. Shoes and accessories are drawn
 * smaller than the main garments, and two or more accessories share a row beneath the outfit.
 * Slots keep each piece's ratio and never overlap, so no garment is cropped, stretched or hidden.
 */
internal fun moodboardLayout(pieces: List<MoodPiece>): MoodLayout {
    val slots = arrayOfNulls<MoodSlot>(pieces.size)
    val accessories = pieces.indices.filter { pieces[it].category == GarmentCategory.ACCESSORY }
    val strip = if (accessories.size > 1) accessories else emptyList()
    val main = pieces.indices.filterNot { it in strip }
    val hero = main.minByOrNull { heroOrder.indexOf(pieces[it].category) }
    val ordered = listOfNotNull(hero) + main.filter { it != hero }.sortedBy { pieces[it].category.ordinal }
    var bottom = 0f

    if (ordered.size == 1) {
        val piece = pieces[ordered.single()]
        slots[ordered.single()] = MoodSlot((1f - SOLO) / 2, 0f, SOLO, SOLO * piece.aspect)
        bottom = SOLO * piece.aspect
    } else if (ordered.isNotEmpty()) {
        val ends = floatArrayOf(0f, STAGGER)
        val used = booleanArrayOf(false, false)
        ordered.forEachIndexed { n, i ->
            val column = if (n == 0 || ends[0] < ends[1]) 0 else 1
            val width = minOf(if (column == 0) WIDE else NARROW, pieces[i].category.maxWidth())
            val y = if (used[column]) ends[column] + GAP else ends[column]
            // Narrower pieces sit against the gutter, keeping the outfit together in the middle.
            val x = if (column == 0) WIDE - width else WIDE + GAP
            slots[i] = MoodSlot(x, y, width, width * pieces[i].aspect)
            ends[column] = y + width * pieces[i].aspect
            used[column] = true
        }
        bottom = maxOf(ends[0], if (used[1]) ends[1] else 0f)
    }

    if (strip.isNotEmpty()) {
        val maxWidth = if (ordered.isEmpty()) SOLO / 2 else ACCESSORY
        var top = if (ordered.isEmpty()) 0f else bottom + GAP
        strip.chunked(ACCESSORIES_PER_ROW).forEach { row ->
            val width = minOf(maxWidth, (1f - GAP * (row.size - 1)) / row.size)
            val rowHeight = row.maxOf { width * pieces[it].aspect }
            var x = (1f - width * row.size - GAP * (row.size - 1)) / 2
            row.forEach { i ->
                val height = width * pieces[i].aspect
                slots[i] = MoodSlot(x, top + rowHeight - height, width, height)
                x += width + GAP
            }
            top += rowHeight + GAP
        }
        bottom = top - GAP
    }
    return MoodLayout(slots.map { it!! }, bottom)
}

/** Photos and extracted images are mostly 2:3 portraits; illustrations are drawn on a 5:6 viewport. */
internal val WardrobeItem.moodAspect: Float
    get() = if (photoId != null || extractedImageId != null) 1.5f else 1.2f

/**
 * The outfit as one composition on a single canvas. The canvas is close to the light grey of most
 * product photos, so their own backgrounds sit quietly on it without being masked or cropped.
 */
@Composable
internal fun Moodboard(items: List<WardrobeItem>, modifier: Modifier = Modifier) {
    val layout = remember(items) { moodboardLayout(items.map { MoodPiece(it.garment.category, it.moodAspect) }) }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(WadrovaColors.Sand)
            .padding(20.dp),
    ) {
        val unit = maxWidth
        Box(Modifier.fillMaxWidth().height(unit * layout.height)) {
            items.zip(layout.slots).forEach { (item, slot) ->
                key(item.garment.id) {
                    GarmentVisual(
                        item,
                        Modifier.offset(unit * slot.x, unit * slot.y).size(unit * slot.width, unit * slot.height),
                        if (slot.width > SHOES) PhotoSize.DISPLAY else PhotoSize.THUMBNAIL,
                    )
                }
            }
        }
    }
}
