package com.example.wadrova.ui

import androidx.compose.ui.graphics.Color
import com.example.wadrova.domain.Garment
import com.example.wadrova.domain.GarmentCategory
import com.example.wadrova.domain.UserGarment

/** Presentation details for a garment; the domain model only knows its id and category. */
data class WardrobeItem(
    val garment: Garment,
    val name: String,
    val colorName: String,
    val illustration: GarmentIllustrationType,
    val tint: Color,
    val archived: Boolean = false,
    val brand: String? = null,
    val material: String? = null,
    val photoId: String? = null,
    val extractedImageId: String? = null,
)

/** The colors offered when adding a piece, with the tints used to draw it. */
val garmentColors = listOf(
    "Black" to Color(0xFF232529),
    "White" to Color(0xFFFFFFFF),
    "Ivory" to Color(0xFFF6F2E8),
    "Beige" to Color(0xFFDCCDB4),
    "Camel" to Color(0xFFC19A6B),
    "Brown" to Color(0xFF7A5A43),
    "Grey" to Color(0xFFA3A6AA),
    "Charcoal" to Color(0xFF3E4146),
    "Navy" to Color(0xFF26324F),
    "Denim" to Color(0xFF5B7096),
    "Green" to Color(0xFF7D9A7E),
    "Olive" to Color(0xFF7A7A4F),
    "Red" to Color(0xFFB6453C),
    "Burgundy" to Color(0xFF6E2A35),
    "Pink" to Color(0xFFEDB8C0),
    "Yellow" to Color(0xFFE6C35C),
)

private val NEUTRAL_TINT = Color(0xFFCFCFCB)

/** User pieces have no photo yet, so each is drawn with a generic illustration for its category. */
private val GarmentCategory.placeholderIllustration: GarmentIllustrationType
    get() = when (this) {
        GarmentCategory.TOP -> GarmentIllustrationType.TEE
        GarmentCategory.BOTTOM -> GarmentIllustrationType.TROUSERS
        GarmentCategory.DRESS -> GarmentIllustrationType.DRESS
        GarmentCategory.OUTERWEAR -> GarmentIllustrationType.JACKET
        GarmentCategory.FOOTWEAR -> GarmentIllustrationType.SNEAKER
        GarmentCategory.ACCESSORY -> GarmentIllustrationType.BAG
    }

fun UserGarment.toWardrobeItem() = WardrobeItem(
    garment = garment,
    name = name,
    colorName = color,
    illustration = garment.category.placeholderIllustration,
    tint = garmentColors.find { it.first == color }?.second ?: NEUTRAL_TINT,
    archived = archived,
    brand = brand,
    material = material,
    photoId = photoId,
    extractedImageId = extractedImageId,
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