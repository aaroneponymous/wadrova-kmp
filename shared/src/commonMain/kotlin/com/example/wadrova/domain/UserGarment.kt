package com.example.wadrova.domain

/** A garment the user added. Archived garments stay resolvable by saved looks. */
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
    /** An imported garment's transparent cutout (the real segmented pixels); [photoId] stays its flattened copy. */
    val cutoutImageId: String? = null,
)
