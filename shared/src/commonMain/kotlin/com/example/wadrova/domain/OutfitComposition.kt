package com.example.wadrova.domain

private val VARIATION_SUFFIX = Regex(" — Variation( \\d+)?$")

/**
 * Removes [garment] if it is already in the outfit, otherwise appends it and drops the pieces it
 * conflicts with. Ids with no matching garment are kept.
 */
fun toggleInOutfit(ids: List<String>, garment: Garment, garments: List<Garment>): List<String> {
    if (garment.id in ids) return ids.filter { it != garment.id }
    val kept = ids.filter { id ->
        val other = garments.find { it.id == id }
        other == null || !conflicts(garment.category, other.category)
    }
    return kept + garment.id
}

/** Accessories stack, a dress fills the top and bottom slots, and every other category holds one piece. */
private fun conflicts(added: GarmentCategory, existing: GarmentCategory): Boolean = when (added) {
    GarmentCategory.ACCESSORY -> false
    GarmentCategory.DRESS -> existing == added || existing == GarmentCategory.TOP || existing == GarmentCategory.BOTTOM
    GarmentCategory.TOP, GarmentCategory.BOTTOM -> existing == added || existing == GarmentCategory.DRESS
    GarmentCategory.OUTERWEAR, GarmentCategory.FOOTWEAR -> existing == added
}

/** Resolves [ids] in display order, keeping selection order within a category and skipping unknown ids. */
fun pickGarments(garments: List<Garment>, ids: List<String>): List<Garment> =
    ids.mapNotNull { id -> garments.find { it.id == id } }
        .sortedBy { it.category.ordinal }

/** Compares regardless of order, so returning to the original composition is clean again. */
fun sameGarmentSet(a: List<String>, b: List<String>): Boolean = a.sorted() == b.sorted()

fun isDraftDirty(baselineIds: List<String>, currentIds: List<String>): Boolean =
    !sameGarmentSet(baselineIds, currentIds)

/** A look can be saved only if it has at least one piece, each once, and every piece exists in the wardrobe. */
fun canSaveLook(garmentIds: List<String>, garments: List<Garment>): Boolean {
    val known = garments.map { it.id }.toSet()
    return garmentIds.isNotEmpty() && garmentIds.distinct().size == garmentIds.size && garmentIds.all { it in known }
}

/** "Cobalt Easy" becomes "Cobalt Easy — Variation", without stacking the suffix. */
fun variationName(name: String): String =
    "${name.ifEmpty { "Untitled look" }.replace(VARIATION_SUFFIX, "")} — Variation"

/** The first free default name for a variation of [name]: "X — Variation", then "X — Variation 2", and so on. */
fun nextVariationName(name: String, existingNames: Collection<String>): String {
    val first = variationName(name)
    if (first !in existingNames) return first
    return generateSequence(2) { it + 1 }.map { "$first $it" }.first { it !in existingNames }
}
