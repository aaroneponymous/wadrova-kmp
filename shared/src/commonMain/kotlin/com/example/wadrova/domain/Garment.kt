package com.example.wadrova.domain

/** Declared in the order pieces are displayed within an outfit. */
enum class GarmentCategory {
    OUTERWEAR,
    TOP,
    DRESS,
    BOTTOM,
    FOOTWEAR,
    ACCESSORY,
}

data class Garment(
    val id: String,
    val category: GarmentCategory,
)
