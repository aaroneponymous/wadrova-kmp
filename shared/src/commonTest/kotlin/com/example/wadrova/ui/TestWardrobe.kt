package com.example.wadrova.ui

import com.example.wadrova.domain.Garment
import com.example.wadrova.domain.GarmentCategory
import com.example.wadrova.domain.UserGarment

private fun piece(id: String, name: String, category: GarmentCategory, color: String) =
    UserGarment(Garment(id, category), name, color, brand = null, material = null, archived = false)

/** A synthetic wardrobe for tests, covering every category. The app itself ships no garments. */
val testGarments = listOf(
    piece("poplin-shirt", "Poplin Shirt", GarmentCategory.TOP, "White"),
    piece("everyday-tee", "Everyday Tee", GarmentCategory.TOP, "Black"),
    piece("cashmere-knit", "Cashmere Knit", GarmentCategory.TOP, "Beige"),
    piece("wide-trousers", "Wide Trousers", GarmentCategory.BOTTOM, "Charcoal"),
    piece("straight-jeans", "Straight Jeans", GarmentCategory.BOTTOM, "Denim"),
    piece("slip-dress", "Slip Dress", GarmentCategory.DRESS, "Green"),
    piece("evening-dress", "Evening Dress", GarmentCategory.DRESS, "Burgundy"),
    piece("wool-coat", "Wool Coat", GarmentCategory.OUTERWEAR, "Camel"),
    piece("linen-blazer", "Linen Blazer", GarmentCategory.OUTERWEAR, "Navy"),
    piece("court-sneakers", "Court Sneakers", GarmentCategory.FOOTWEAR, "White"),
    piece("suede-boots", "Suede Boots", GarmentCategory.FOOTWEAR, "Brown"),
    piece("leather-tote", "Leather Tote", GarmentCategory.ACCESSORY, "Black"),
    piece("silk-scarf", "Silk Scarf", GarmentCategory.ACCESSORY, "Pink"),
    piece("felt-hat", "Felt Hat", GarmentCategory.ACCESSORY, "Grey"),
    piece("leather-belt", "Leather Belt", GarmentCategory.ACCESSORY, "Brown"),
)

val testWardrobe = testGarments.map { it.toWardrobeItem() }
