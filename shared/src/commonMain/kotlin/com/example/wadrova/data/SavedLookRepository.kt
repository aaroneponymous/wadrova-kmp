package com.example.wadrova.data

import app.cash.sqldelight.db.SqlDriver
import com.example.wadrova.domain.SavedLook
import com.example.wadrova.domain.canSaveLook
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface SavedLookRepository {
    suspend fun getAll(): List<SavedLook>

    /** Throws [InvalidLookException] if the look can't be saved; nothing is written in that case. */
    suspend fun create(name: String, garmentIds: List<String>): SavedLook

    /** Replaces an existing look's name and pieces, keeping its id. Throws like [create]. */
    suspend fun update(id: String, name: String, garmentIds: List<String>): SavedLook

    /** Removes the look and its piece references, never the garments themselves. A look that's already gone is not an error. */
    suspend fun delete(id: String)

    /** Closes the underlying database, which other repositories may share. */
    fun close()
}

class InvalidLookException(message: String) : Exception(message)

/** The repositories over one local database. */
data class LocalRepositories(
    val savedLooks: SavedLookRepository,
    val garments: GarmentRepository,
    val photos: GarmentPhotoStore,
    val generatedImages: GeneratedImageStore,
    val mirror: MirrorRepository,
    val mirrorBackend: MirrorBackend,
    val brandIntro: BrandIntroStore,
)

@OptIn(ExperimentalUuidApi::class)
class SqlDelightSavedLookRepository(
    private val driver: SqlDriver,
    private val newId: () -> String = { Uuid.random().toString() },
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SavedLookRepository {
    private val database = WadrovaDatabase(driver)
    private val queries = database.savedLookQueries

    override suspend fun getAll(): List<SavedLook> = withContext(dispatcher) {
        queries.transactionWithResult {
            val garmentIds = queries.selectLookGarments().executeAsList()
                .groupBy({ it.look_id }, { it.garment_id })
            queries.selectLooks().executeAsList().map { look ->
                SavedLook(look.id, look.name, garmentIds[look.id].orEmpty(), look.created_at, look.updated_at)
            }
        }
    }

    override suspend fun create(name: String, garmentIds: List<String>): SavedLook = withContext(dispatcher) {
        val now = nowMillis()
        val id = newId()
        queries.transactionWithResult {
            val look = SavedLook(id, validated(name, garmentIds), garmentIds, now, now)
            queries.insertLook(look.id, look.name, now, now)
            insertGarments(look.id, garmentIds)
            look
        }
    }

    override suspend fun update(id: String, name: String, garmentIds: List<String>): SavedLook = withContext(dispatcher) {
        val now = nowMillis()
        queries.transactionWithResult {
            val trimmed = validated(name, garmentIds)
            val existing = queries.selectLook(id).executeAsOneOrNull()
                ?: throw InvalidLookException("This look no longer exists.")
            queries.updateLook(trimmed, now, id)
            queries.deleteLookGarments(id)
            insertGarments(id, garmentIds)
            SavedLook(id, trimmed, garmentIds, existing.created_at, now)
        }
    }

    override suspend fun delete(id: String) = withContext(dispatcher) {
        queries.transaction {
            queries.deleteLookGarments(id)
            queries.deleteLook(id)
        }
    }

    override fun close() = driver.close()

    private fun validated(name: String, garmentIds: List<String>): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw InvalidLookException("A look needs a name.")
        if (!canSaveLook(garmentIds, database.userGarmentReferences())) {
            throw InvalidLookException("This look includes a piece that is no longer in your wardrobe.")
        }
        return trimmed
    }

    private fun insertGarments(lookId: String, garmentIds: List<String>) {
        garmentIds.forEachIndexed { index, garmentId ->
            queries.insertLookGarment(lookId, garmentId, index.toLong())
        }
    }
}
