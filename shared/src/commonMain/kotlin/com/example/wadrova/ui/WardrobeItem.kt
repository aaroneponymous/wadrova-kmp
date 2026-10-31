package com.example.wadrova.ui

import androidx.compose.ui.graphics.Color
import com.example.wadrova.domain.Garment
import com.example.wadrova.domain.GarmentCategory

/** Presentation details for a garment; the domain model only knows its id and category. */
data class WardrobeItem(
    val garment: Garment,
    val name: String,
    val colorName: String,
    val illustration: GarmentIllustrationType,
    val tint: Color,
)

/** Order of the category filters, which differs from the display order within an outfit. */
val filterCategories = listOf(
    GarmentCategory.TOP,
    GarmentCategory.BOTTOM,
    GarmentCategory.DRESS,
    GarmentCategory.OUTERWEAR,
    GarmentCategory.FOOTWEAR,
    GarmentCategory.ACCESSORY,
)

val GarmentCategory.pluralLabel: String
    get() = when (this) {
        GarmentCategory.TOP -> "Tops"
        GarmentCategory.BOTTOM -> "Bottoms"
        GarmentCategory.DRESS -> "Dresses"
        GarmentCategory.OUTERWEAR -> "Outerwear"
        GarmentCategory.FOOTWEAR -> "Shoes"
        GarmentCategory.ACCESSORY -> "Accessories"
    }

val GarmentCategory.singularLabel: String
    get() = when (this) {
        GarmentCategory.TOP -> "Top"
        GarmentCategory.BOTTOM -> "Bottoms"
        GarmentCategory.DRESS -> "Dress"
        GarmentCategory.OUTERWEAR -> "Outerwear"
        GarmentCategory.FOOTWEAR -> "Shoes"
        GarmentCategory.ACCESSORY -> "Accessory"
    }

private fun sample(
    id: String,
    name: String,
    category: GarmentCategory,
    colorName: String,
    illustration: GarmentIllustrationType,
    tint: Long,
) = WardrobeItem(Garment(id, category), name, colorName, illustration, Color(tint))

/** Illustrated sample data, labelled as such in the UI. */
val sampleWardrobe = listOf(
    sample("poplin-shirt", "Poplin Shirt", GarmentCategory.TOP, "White", GarmentIllustrationType.SHIRT, 0xFFFFFFFF),
    sample("everyday-tee", "Everyday Tee", GarmentCategory.TOP, "Black", GarmentIllustrationType.TEE, 0xFF232529),
    sample("cashmere-knit", "Cashmere Knit", GarmentCategory.TOP, "Oatmeal", GarmentIllustrationType.KNIT, 0xFFDCCDB4),
    sample("wide-trousers", "Wide Trousers", GarmentCategory.BOTTOM, "Charcoal", GarmentIllustrationType.TROUSERS, 0xFF3E4146),
    sample("straight-jeans", "Straight Jeans", GarmentCategory.BOTTOM, "Denim", GarmentIllustrationType.JEANS, 0xFF5B7096),
    sample("slip-dress", "Slip Dress", GarmentCategory.DRESS, "Sage", GarmentIllustrationType.DRESS, 0xFFA6B7A7),
    sample("evening-dress", "Evening Dress", GarmentCategory.DRESS, "Burgundy", GarmentIllustrationType.DRESS, 0xFF6E2A35),
    sample("wool-coat", "Wool Coat", GarmentCategory.OUTERWEAR, "Camel", GarmentIllustrationType.COAT, 0xFFC19A6B),
    sample("linen-blazer", "Linen Blazer", GarmentCategory.OUTERWEAR, "Navy", GarmentIllustrationType.JACKET, 0xFF26324F),
    sample("court-sneakers", "Court Sneakers", GarmentCategory.FOOTWEAR, "White", GarmentIllustrationType.SNEAKER, 0xFFFFFFFF),
    sample("suede-boots", "Suede Boots", GarmentCategory.FOOTWEAR, "Brown", GarmentIllustrationType.BOOT, 0xFF7A5A43),
    sample("leather-tote", "Leather Tote", GarmentCategory.ACCESSORY, "Black", GarmentIllustrationType.BAG, 0xFF232529),
    sample("silk-scarf", "Silk Scarf", GarmentCategory.ACCESSORY, "Coral", GarmentIllustrationType.SCARF, 0xFFF2B6A5),
    sample("felt-hat", "Felt Hat", GarmentCategory.ACCESSORY, "Stone", GarmentIllustrationType.HAT, 0xFFC9C3B8),
    sample("leather-belt", "Leather Belt", GarmentCategory.ACCESSORY, "Tan", GarmentIllustrationType.BELT, 0xFFC8A57E),
)
