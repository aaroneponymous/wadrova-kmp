package com.example.wadrova.domain

/** A garment the user added, as opposed to a built-in sample. Archived garments stay resolvable by saved looks. */
data class UserGarment(
    val garment: Garment,
    val name: String,
    val color: String,
    val brand: String?,
    val material: String?,
    val archived: Boolean,
    val photoId: String? = null,
    /** An approved background-removed version, kept alongside the photo rather than replacing it. */
    val extractedImageId: String? = null,
)
