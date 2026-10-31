package com.example.wadrova.data

import app.cash.sqldelight.db.SqlDriver
import com.example.wadrova.domain.Garment
import com.example.wadrova.domain.GarmentCategory
import com.example.wadrova.domain.UserGarment
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GarmentDetails(
    val name: String,
    val category: GarmentCategory,
    val color: String,
    val brand: String? = null,
    val material: String? = null,
)

/** What an update does to the garment's photo. */
sealed interface PhotoChange {
    data object Keep : PhotoChange
    data object Remove : PhotoChange
    data class Set(val photoId: String) : PhotoChange
}

interface GarmentRepository {
    suspend fun getAll(): List<UserGarment>

    /** Throws [InvalidGarmentException] if the details aren't valid; nothing is written in that case. */
    suspend fun create(details: GarmentDetails, photoId: String? = null): UserGarment

    /**
     * Keeps the garment's id. A garment used by a saved look can't change category. Throws like [create].
     * A replaced or removed photo's files are deleted only after the database change has committed.
     */
    suspend fun update(id: String, details: GarmentDetails, photo: PhotoChange = PhotoChange.Keep): UserGarment

    suspend fun setArchived(id: String, archived: Boolean): UserGarment

    /** Sets or clears the approved extracted image. The photo is untouched, and a previous extraction is deleted. */
    suspend fun setExtractedImage(id: String, imageId: String?): UserGarment

    /** Deletes photo files no garment refers to, such as those left by a crash mid-edit. */
    suspend fun removeUnreferencedPhotos()
}

class InvalidGarmentException(message: String) : Exception(message)

/** The user-created garments as domain garments. Rows with a category this version doesn't know are skipped. */
internal fun WadrovaDatabase.userGarmentReferences(): List<Garment> =
    garmentQueries.selectGarmentCategories().executeAsList().mapNotNull { row ->
        GarmentCategory.entries.find { it.name == row.category }?.let { Garment(row.id, it) }
    }

@OptIn(ExperimentalUuidApi::class)
class SqlDelightGarmentRepository(
    driver: SqlDriver,
    private val photos: GarmentPhotoStore,
    private val generatedImages: GeneratedImageStore,
    private val newId: () -> String = { "user-${Uuid.random()}" },
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : GarmentRepository {
    private val queries = WadrovaDatabase(driver).garmentQueries

    override suspend fun getAll(): List<UserGarment> = withContext(dispatcher) {
        queries.selectGarments().executeAsList().mapNotNull { it.toDomain() }
    }

    override suspend fun create(details: GarmentDetails, photoId: String?): UserGarment = withContext(dispatcher) {
        val clean = details.validated()
        val id = newId()
        val now = nowMillis()
        queries.transactionWithResult {
            queries.insertGarment(id, clean.name, clean.category.name, clean.color, clean.brand, clean.material, now, now, photoId)
            requireGarment(id)
        }
    }

    override suspend fun update(id: String, details: GarmentDetails, photo: PhotoChange): UserGarment = withContext(dispatcher) {
        val clean = details.validated()
        val now = nowMillis()
        var replacedPhotoId: String? = null
        var staleExtractionId: String? = null
        val updated = queries.transactionWithResult {
            val existing = requireGarment(id)
            if (existing.garment.category != clean.category && queries.countLooksUsingGarment(id).executeAsOne() > 0) {
                throw InvalidGarmentException("This piece is part of a saved look, so its category can't change.")
            }
            val newPhotoId = when (photo) {
                PhotoChange.Keep -> existing.photoId
                PhotoChange.Remove -> null
                is PhotoChange.Set -> photo.photoId
            }
            // An extraction was made from the old photo, so it can't outlive it.
            val photoChanged = newPhotoId != existing.photoId
            if (photoChanged) {
                replacedPhotoId = existing.photoId
                staleExtractionId = existing.extractedImageId
            }
            val extractedImageId = if (photoChanged) null else existing.extractedImageId
            queries.updateGarment(clean.name, clean.category.name, clean.color, clean.brand, clean.material, newPhotoId, extractedImageId, now, id)
            requireGarment(id)
        }
        replacedPhotoId?.let { deleteQuietly(it) }
        staleExtractionId?.let { deleteExtractionQuietly(it) }
        updated
    }

    override suspend fun setExtractedImage(id: String, imageId: String?): UserGarment = withContext(dispatcher) {
        val now = nowMillis()
        var previousId: String? = null
        val updated = queries.transactionWithResult {
            previousId = requireGarment(id).extractedImageId
            queries.setExtractedImage(imageId, now, id)
            requireGarment(id)
        }
        previousId?.takeIf { it != imageId }?.let { deleteExtractionQuietly(it) }
        updated
    }

    private suspend fun deleteExtractionQuietly(imageId: String) {
        try {
            generatedImages.delete(imageId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    override suspend fun setArchived(id: String, archived: Boolean): UserGarment = withContext(dispatcher) {
        val now = nowMillis()
        queries.transactionWithResult {
            requireGarment(id)
            queries.setGarmentArchivedAt(if (archived) now else null, now, id)
            requireGarment(id)
        }
    }

    override suspend fun removeUnreferencedPhotos() = withContext(dispatcher) {
        val referenced = queries.selectPhotoIds().executeAsList().toSet()
        (photos.storedIds() - referenced).forEach { deleteQuietly(it) }
    }

    /** A file that can't be deleted now is cleared by the next [removeUnreferencedPhotos]. */
    private suspend fun deleteQuietly(photoId: String) {
        try {
            photos.delete(photoId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private fun requireGarment(id: String): UserGarment =
        queries.selectGarment(id).executeAsOneOrNull()?.toDomain()
            ?: throw InvalidGarmentException("This piece no longer exists.")

    private fun User_garment.toDomain(): UserGarment? {
        val knownCategory = GarmentCategory.entries.find { it.name == category } ?: return null
        return UserGarment(Garment(id, knownCategory), name, color, brand, material, archived_at != null, photo_id, extracted_image_id)
    }

    private fun GarmentDetails.validated(): GarmentDetails {
        val cleanName = name.trim()
        val cleanColor = color.trim()
        if (cleanName.isEmpty()) throw InvalidGarmentException("A piece needs a name.")
        if (cleanColor.isEmpty()) throw InvalidGarmentException("A piece needs a color.")
        return copy(
            name = cleanName,
            color = cleanColor,
            brand = brand?.trim()?.ifEmpty { null },
            material = material?.trim()?.ifEmpty { null },
        )
    }
}
