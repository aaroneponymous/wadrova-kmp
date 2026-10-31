package com.example.wadrova.data

import app.cash.sqldelight.db.SqlDriver
import com.example.wadrova.domain.Garment
import com.example.wadrova.domain.GarmentCategory
import com.example.wadrova.domain.UserGarment
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
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

interface GarmentRepository {
    suspend fun getAll(): List<UserGarment>

    /** Throws [InvalidGarmentException] if the details aren't valid; nothing is written in that case. */
    suspend fun create(details: GarmentDetails): UserGarment

    /** Keeps the garment's id. A garment used by a saved look can't change category. Throws like [create]. */
    suspend fun update(id: String, details: GarmentDetails): UserGarment

    suspend fun setArchived(id: String, archived: Boolean): UserGarment
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
    private val newId: () -> String = { "user-${Uuid.random()}" },
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : GarmentRepository {
    private val queries = WadrovaDatabase(driver).garmentQueries

    override suspend fun getAll(): List<UserGarment> = withContext(dispatcher) {
        queries.selectGarments().executeAsList().mapNotNull { it.toDomain() }
    }

    override suspend fun create(details: GarmentDetails): UserGarment = withContext(dispatcher) {
        val clean = details.validated()
        val id = newId()
        val now = nowMillis()
        queries.transactionWithResult {
            queries.insertGarment(id, clean.name, clean.category.name, clean.color, clean.brand, clean.material, now, now)
            requireGarment(id)
        }
    }

    override suspend fun update(id: String, details: GarmentDetails): UserGarment = withContext(dispatcher) {
        val clean = details.validated()
        val now = nowMillis()
        queries.transactionWithResult {
            val existing = requireGarment(id)
            if (existing.garment.category != clean.category && queries.countLooksUsingGarment(id).executeAsOne() > 0) {
                throw InvalidGarmentException("This piece is part of a saved look, so its category can't change.")
            }
            queries.updateGarment(clean.name, clean.category.name, clean.color, clean.brand, clean.material, now, id)
            requireGarment(id)
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

    private fun requireGarment(id: String): UserGarment =
        queries.selectGarment(id).executeAsOneOrNull()?.toDomain()
            ?: throw InvalidGarmentException("This piece no longer exists.")

    private fun User_garment.toDomain(): UserGarment? {
        val knownCategory = GarmentCategory.entries.find { it.name == category } ?: return null
        return UserGarment(Garment(id, knownCategory), name, color, brand, material, archived_at != null)
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
