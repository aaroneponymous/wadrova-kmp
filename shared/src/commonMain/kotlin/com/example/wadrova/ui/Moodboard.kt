package com.example.wadrova.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.wadrova.data.PhotoSize
import com.example.wadrova.domain.GarmentCategory

/**
 * A piece's category and expected height-to-width ratio, which is all the layout needs to know.
 * [share] is the part of a full-width slot the garment itself spans: below 1 for an illustration
 * trimmed to its outline, so a trimmed sneaker is drawn no larger than an untrimmed one.
 */
internal data class MoodPiece(val category: GarmentCategory, val aspect: Float, val share: Float = 1f)

/** Position and size as fractions of the canvas width, so one layout fits every screen width. */
internal data class MoodSlot(val x: Float, val y: Float, val width: Float, val height: Float)

/** One slot per piece, in the order given, and the canvas height as a fraction of its width. */
internal data class MoodLayout(val slots: List<MoodSlot>, val height: Float)

private const val GAP = 0.05f
private const val SOLO = 0.6f
private const val SHOES = 0.3f
private const val ACCESSORY = 0.22f
private const val ACCESSORY_HEIGHT = 0.33f
private const val ACCESSORIES_PER_ROW = 4

/** The wide column's share of the width, and how much lower the narrow column starts. */
private data class Columns(val wide: Float, val stagger: Float)

private val editorial = Columns(0.55f, 0.1f)

/** Small outfits may also lay out level with a slightly wider lead, which wastes less space. */
private val compact = Columns(0.6f, 0f)

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

/** The widest a piece can be in a cell, keeping shoes and accessories at their size and accessories within a set height. */
private fun MoodPiece.widthIn(cell: Float): Float = when (category) {
    GarmentCategory.ACCESSORY -> minOf(cell * share, ACCESSORY_HEIGHT / aspect)
    GarmentCategory.FOOTWEAR -> cell * share
    else -> cell
}

/**
 * Lays an outfit out as a flat-lay. The leading piece takes a wide column and the rest of the
 * outfit follows in body order, each into whichever column ends higher, so the columns stay even.
 * The narrow column starts lower for an offset, editorial rhythm. Shoes and accessories are drawn
 * smaller than the main garments. Accessories either fill the columns, pairing up in the wide
 * one, or share a row beneath the outfit, whichever keeps the canvas shorter.
 * Slots keep each piece's ratio and never overlap, so no garment is cropped, stretched or hidden.
 */
internal fun moodboardLayout(pieces: List<MoodPiece>): MoodLayout {
    if (pieces.isEmpty()) return MoodLayout(emptyList(), 0f)
    if (pieces.size == 1) return MoodLayout(listOf(MoodSlot((1f - SOLO) / 2, 0f, SOLO, SOLO * pieces[0].aspect)), SOLO * pieces[0].aspect)
    val accessories = pieces.indices.filter { pieces[it].category == GarmentCategory.ACCESSORY }
    if (accessories.size == pieces.size) {
        val slots = arrayOfNulls<MoodSlot>(pieces.size)
        val height = placeRows(pieces, accessories, 0f, SOLO / 2, slots)
        return MoodLayout(slots.map { it!! }, height)
    }
    // Two or three pieces leave the most space unused, so they may also lay out level.
    val shapes = if (pieces.size <= 3) listOf(editorial, compact) else listOf(editorial)
    val strips = if (accessories.size > 1) listOf(emptyList(), accessories) else listOf(emptyList())
    return shapes.flatMap { columns -> strips.map { strip -> arrange(pieces, columns, strip) } }.minBy { it.height }
}

/** Places every piece not in [strip] in the columns, then [strip] in rows beneath them. */
private fun arrange(pieces: List<MoodPiece>, columns: Columns, strip: List<Int>): MoodLayout {
    val slots = arrayOfNulls<MoodSlot>(pieces.size)
    val inColumns = pieces.indices.filter { it !in strip }
    val narrow = 1f - columns.wide - GAP
    val hero = inColumns.minBy { heroOrder.indexOf(pieces[it].category) }
    val ordered = listOf(hero) + inColumns.filter { it != hero }.sortedBy { pieces[it].category.ordinal }
    val ends = floatArrayOf(0f, columns.stagger)
    val counts = intArrayOf(0, 0)

    var n = 0
    while (n < ordered.size) {
        val column = if (n == 0 || ends[0] < ends[1]) 0 else 1
        val columnWidth = if (column == 0) columns.wide else narrow
        val first = pieces[ordered[n]]
        // Accessories come last, so the next piece after one is always another accessory.
        val pair = column == 0 && first.category == GarmentCategory.ACCESSORY && n + 1 < ordered.size
        val row = if (pair) ordered.subList(n, n + 2) else listOf(ordered[n])
        val cell = if (pair) (columnWidth - GAP) / 2 else minOf(columnWidth, first.category.maxWidth())
        val rowHeight = row.maxOf { pieces[it].widthIn(cell) * pieces[it].aspect }
        val y = if (counts[column] > 0) ends[column] + GAP else ends[column]
        row.forEachIndexed { k, i ->
            val width = pieces[i].widthIn(cell)
            val height = width * pieces[i].aspect
            // Narrower pieces sit against the gutter, keeping the outfit together in the middle.
            val x = when {
                pair -> k * (cell + GAP) + (cell - width) / 2
                column == 0 -> columns.wide - width
                else -> columns.wide + GAP
            }
            slots[i] = MoodSlot(x, y + rowHeight - height, width, height)
        }
        ends[column] = y + rowHeight
        counts[column]++
        n += row.size
    }

    var height = maxOf(ends[0], if (counts[1] > 0) ends[1] else 0f)
    // A lead standing alone beside a taller stack is centred on it rather than leaving a gap below.
    if (counts[0] == 1 && ends[0] < height) {
        slots[hero] = slots[hero]!!.let { it.copy(y = it.y + (height - ends[0]) / 2) }
    }
    if (strip.isNotEmpty()) height = placeRows(pieces, strip, height + GAP, ACCESSORY, slots)
    return MoodLayout(slots.map { it!! }, height)
}

/** Centred, bottom-aligned rows of up to four pieces starting at [top]. Returns where they end. */
private fun placeRows(pieces: List<MoodPiece>, indices: List<Int>, top: Float, maxWidth: Float, slots: Array<MoodSlot?>): Float {
    var y = top
    indices.chunked(ACCESSORIES_PER_ROW).forEach { row ->
        val cell = minOf(maxWidth, (1f - GAP * (row.size - 1)) / row.size)
        val rowHeight = row.maxOf { pieces[it].widthIn(cell) * pieces[it].aspect }
        var x = (1f - cell * row.size - GAP * (row.size - 1)) / 2
        row.forEach { i ->
            val width = pieces[i].widthIn(cell)
            val height = width * pieces[i].aspect
            slots[i] = MoodSlot(x + (cell - width) / 2, y + rowHeight - height, width, height)
            x += cell + GAP
        }
        y += rowHeight + GAP
    }
    return y - GAP
}

/**
 * The pieces a small preview shows, as indices in the order given: the lead first (a dress or a
 * coat), then the top and bottoms, then shoes, then an accessory, until [max] are chosen.
 */
internal fun previewSelection(categories: List<GarmentCategory>, max: Int): List<Int> =
    categories.indices.sortedBy { heroOrder.indexOf(categories[it]) }.take(max).sorted()

/**
 * [pieces] laid out as on the full moodboard, then scaled and centred to fill a canvas
 * [aspect] times as tall as it is wide. Positions stay fractions of the canvas width.
 */
internal fun previewLayout(pieces: List<MoodPiece>, aspect: Float): List<MoodSlot> {
    val slots = moodboardLayout(pieces).slots
    if (slots.isEmpty()) return slots
    val left = slots.minOf { it.x }
    val top = slots.minOf { it.y }
    val width = slots.maxOf { it.x + it.width } - left
    val height = slots.maxOf { it.y + it.height } - top
    val scale = minOf(1f / width, aspect / height)
    val dx = (1f - width * scale) / 2 - left * scale
    val dy = (aspect - height * scale) / 2 - top * scale
    return slots.map { MoodSlot(dx + it.x * scale, dy + it.y * scale, it.width * scale, it.height * scale) }
}

/** Cards narrower than this inside their padding show three pieces instead of four. */
private val ROOMY_PREVIEW = 130.dp

/**
 * A saved look's card image: the moodboard's composition for its most telling pieces, filling a
 * 4:5 canvas. When pieces are left out, a "+N" marker says so; the look itself shows them all.
 */
@Composable
internal fun MoodboardPreview(items: List<WardrobeItem>, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(4f / 5f)
            .clip(RoundedCornerShape(16.dp))
            .background(WadrovaColors.Sand)
            .padding(12.dp),
    ) {
        val max = if (maxWidth >= ROOMY_PREVIEW) 4 else 3
        val shown = remember(items, max) { previewSelection(items.map { it.garment.category }, max).map { items[it] } }
        val aspect = maxHeight / maxWidth
        val slots = remember(shown, aspect) { previewLayout(shown.map { it.moodPiece }, aspect) }
        val unit = maxWidth
        shown.zip(slots).forEach { (item, slot) ->
            key(item.garment.id) {
                GarmentVisual(
                    item,
                    Modifier.offset(unit * slot.x, unit * slot.y).size(unit * slot.width, unit * slot.height),
                    trimIllustration = true,
                )
            }
        }
        val hidden = items.size - shown.size
        if (hidden > 0) {
            Text(
                "+$hidden",
                Modifier
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(WadrovaColors.Warm.copy(alpha = 0.92f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .semantics { contentDescription = "$hidden more ${if (hidden == 1) "piece" else "pieces"} in this look" },
                style = MaterialTheme.typography.labelLarge,
                color = WadrovaColors.Charcoal,
            )
        }
    }
}

/**
 * Photos and extracted images are mostly 2:3 portraits. Illustrations are drawn trimmed to the
 * garment's own outline, so a sneaker's slot is as wide and short as the sneaker.
 */
internal val WardrobeItem.moodPiece: MoodPiece
    get() = if (photoId != null || extractedImageId != null) {
        MoodPiece(garment.category, 1.5f)
    } else {
        MoodPiece(garment.category, illustration.trimmedAspect, illustration.trimmedShare)
    }

/**
 * The outfit as one composition on a single canvas. The canvas is close to the light grey of most
 * product photos, so their own backgrounds sit quietly on it without being masked or cropped.
 */
@Composable
internal fun Moodboard(items: List<WardrobeItem>, modifier: Modifier = Modifier) {
    val layout = remember(items) { moodboardLayout(items.map { it.moodPiece }) }
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
                        trimIllustration = true,
                    )
                }
            }
        }
    }
}
