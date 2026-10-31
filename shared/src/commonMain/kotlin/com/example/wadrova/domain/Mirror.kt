package com.example.wadrova.domain

/** One approved version of the user's personal model. A new approval adds a version under the same [modelId]. */
data class ApprovedModel(
    val modelId: String,
    val version: Long,
    val imageId: String,
    val generationModel: String,
    val generationQuality: String,
)

/** Which of a garment's images a try-on used. */
enum class GarmentImageKind { PHOTO, EXTRACTED }

/** An accepted preview, with enough detail to trace how it was made. */
data class TryOnResult(
    val id: String,
    val modelId: String,
    val modelVersion: Long,
    val garmentId: String,
    val sourceImageId: String,
    val sourceImageKind: GarmentImageKind,
    val generationModel: String,
    val generationQuality: String,
    val resultImageId: String,
)
