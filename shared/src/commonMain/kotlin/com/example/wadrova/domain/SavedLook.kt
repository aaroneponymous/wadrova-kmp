package com.example.wadrova.domain

/** A user-created outfit. It stores garment references, in the order they were selected. */
data class SavedLook(
    val id: String,
    val name: String,
    val garmentIds: List<String>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)
