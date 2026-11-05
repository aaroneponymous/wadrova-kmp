package com.example.wadrova.ui

import com.example.wadrova.data.ApiFailure
import com.example.wadrova.data.CorrectionRequest
import com.example.wadrova.data.ServerCorrection
import com.example.wadrova.data.WadrovaApi
import com.example.wadrova.data.WadrovaApiException
import com.example.wadrova.data.WadrovaConnection
import com.example.wadrova.data.sha256Hex
import com.example.wadrova.domain.Candidate
import com.example.wadrova.domain.CandidateAsset
import com.example.wadrova.domain.CandidateBox
import com.example.wadrova.domain.FinalizeManifest
import com.example.wadrova.domain.GarmentCategory
import com.example.wadrova.domain.ServerImport
import com.example.wadrova.domain.groupLineages
import kotlinx.coroutines.CompletableDeferred

/** A fake wadrova-backend: idempotent imports, candidates, verified assets, finalize rules and corrections. */
class FakeWadrovaServer : WadrovaApi {
    val calls = mutableListOf<String>()
    val uploads = mutableListOf<Triple<String, String, List<Byte>>>()
    val tokens = mutableListOf<String>()
    val imports = linkedMapOf<String, ServerImport>()
    private val idByKey = mutableMapOf<String, String>()
    val createFailures = ArrayDeque<WadrovaApiException>()
    var loseNextCreateResponse = false
    var alwaysUnreachable = false
    var getFailure: WadrovaApiException? = null
    val cancelFailures = ArrayDeque<WadrovaApiException>()
    var createGate: CompletableDeferred<Unit>? = null

    private fun record(name: String, connection: WadrovaConnection) {
        calls += name
        tokens += connection.token
        if (alwaysUnreachable) throw unreachable()
    }

    override suspend fun me(connection: WadrovaConnection): String {
        record("me", connection)
        return "Drill"
    }

    override suspend fun createImport(connection: WadrovaConnection, key: String, sha256: String, jpeg: ByteArray): ServerImport {
        record("create", connection)
        uploads += Triple(key, sha256, jpeg.toList())
        createGate?.await()
        createFailures.removeFirstOrNull()?.let { throw it }
        // Like the server: the same key returns the same import.
        val id = idByKey.getOrPut(key) {
            "imp_${idByKey.size + 1}".also { imports[it] = ServerImport(it, key, "queued", "ses_$it", null, null, 3) }
        }
        if (loseNextCreateResponse) {
            loseNextCreateResponse = false
            throw unreachable()
        }
        return imports.getValue(id)
    }

    override suspend fun getImport(connection: WadrovaConnection, id: String): ServerImport {
        record("get", connection)
        getFailure?.let { throw it }
        return imports[id] ?: throw WadrovaApiException(ApiFailure.NOT_FOUND, "gone")
    }

    override suspend fun activeImports(connection: WadrovaConnection, cursor: String?): Pair<List<ServerImport>, String?> {
        record("list", connection)
        return imports.values.filter { it.status != "cancelled" && it.status != "finalized" } to null
    }

    override suspend fun cancelImport(connection: WadrovaConnection, id: String): ServerImport {
        record("cancel", connection)
        cancelFailures.removeFirstOrNull()?.let { throw it }
        return imports.getValue(id).copy(status = "cancelled").also { imports[id] = it }
    }

    fun set(id: String, status: String, count: Int? = null, failure: String? = null) {
        imports[id] = imports.getValue(id).copy(status = status, candidateCount = count, failureMessage = failure)
    }

    fun count(name: String) = calls.count { it == name }

    private fun unreachable() = WadrovaApiException(ApiFailure.UNREACHABLE, "Couldn't reach Wadrova.")

    // Candidates, assets, finalize and corrections, with the backend's rules for each.

    /** Candidates by session id. Corrections add revisions here. */
    val sessions = mutableMapOf<String, MutableList<Candidate>>()
    /** Asset bytes by asset id. A missing entry is a 404; [corruptAssets] fail their checksum. */
    val assets = mutableMapOf<String, ByteArray>()
    val corruptAssets = mutableSetOf<String>()
    val finalizeBodies = mutableListOf<FinalizeManifest>()
    val finalizeFailures = ArrayDeque<WadrovaApiException>()
    var loseNextFinalizeResponse = false
    private val finalized = mutableMapOf<String, FinalizeManifest>()
    val corrections = mutableMapOf<String, ServerCorrection>()
    private val correctionByKey = mutableMapOf<String, String>()
    /** Asynchronous corrections stay queued until [completeCorrection]. */
    val correctionRequests = mutableListOf<Pair<String, CorrectionRequest>>()

    fun addCandidates(sessionId: String, vararg candidates: Candidate) {
        sessions.getOrPut(sessionId) { mutableListOf() } += candidates
        candidates.forEach { c -> assets.getOrPut(c.cutout.id) { "png-${c.id}".encodeToByteArray() } }
    }

    /** The cutout asset for [id], with its real hash, as the backend publishes it. */
    fun candidate(id: String, rank: Int = 0, parent: String? = null, revision: Int = 1, detected: Boolean = true, phrase: String = "jacket", suggested: GarmentCategory? = null): Candidate {
        val bytes = "png-$id".encodeToByteArray()
        return Candidate(
            id, rank, phrase, suggested, if (detected) "detection" else "below_threshold", detected, revision, parent, null,
            CandidateBox(10, 10, 110, 210), CandidateAsset("ast_c_$id", sha256Hex(bytes), "image/png", 100, 200),
            CandidateAsset("ast_p_$id", "00", "image/jpeg", null, null),
        )
    }

    override suspend fun candidates(connection: WadrovaConnection, sessionId: String): List<Candidate> {
        record("candidates", connection)
        val list = sessions[sessionId] ?: throw WadrovaApiException(ApiFailure.NOT_FOUND, "gone")
        val supersededBy = list.filter { it.parentId != null }.associate { it.parentId!! to it.id }
        return list.map { it.copy(supersededBy = supersededBy[it.id]) }
    }

    override suspend fun assetContent(connection: WadrovaConnection, asset: CandidateAsset): ByteArray {
        record("asset", connection)
        if (asset.id in corruptAssets) throw WadrovaApiException(ApiFailure.UNAVAILABLE, "A downloaded image didn't match its checksum.", "checksum_mismatch")
        return assets[asset.id] ?: throw WadrovaApiException(ApiFailure.NOT_FOUND, "gone")
    }

    /** Like the backend: every lineage whose current revision is a detection must be decided, once. */
    override suspend fun finalizeImport(connection: WadrovaConnection, id: String, manifest: FinalizeManifest): ServerImport {
        record("finalize", connection)
        finalizeBodies += manifest
        finalizeFailures.removeFirstOrNull()?.let { throw it }
        val import = imports[id] ?: throw WadrovaApiException(ApiFailure.NOT_FOUND, "gone")
        val normalized = FinalizeManifest(manifest.accepted.sortedBy { it.first }, manifest.rejected.sorted())
        finalized[id]?.let { previous ->
            if (previous != normalized) throw WadrovaApiException(ApiFailure.REJECTED, "refused", "invalid_state", "finalized_differently")
            return import
        }
        val lineages = groupLineages(sessions[import.sessionId].orEmpty())
        val listed = (manifest.accepted.map { it.first } + manifest.rejected).toSet()
        val missing = lineages.filter { it.mustBeDecided && it.revisions.none { r -> r.id in listed } }.map { it.current.id }
        if (missing.isNotEmpty()) throw WadrovaApiException(ApiFailure.REJECTED, "refused", "invalid_state", "missing_decisions", missing)
        finalized[id] = normalized
        val done = import.copy(status = "finalized", candidateCount = null, pollAfterSeconds = null)
        imports[id] = done
        if (loseNextFinalizeResponse) {
            loseNextFinalizeResponse = false
            throw unreachable()
        }
        return done
    }

    fun finalizedManifest(id: String): FinalizeManifest? = finalized[id]

    override suspend fun createCorrection(connection: WadrovaConnection, candidateId: String, key: String, correction: CorrectionRequest): ServerCorrection {
        record("correct", connection)
        correctionByKey[key]?.let { return corrections.getValue(it) }
        val session = sessions.entries.first { (_, list) -> list.any { it.id == candidateId } }
        if (session.value.any { it.parentId == candidateId }) throw WadrovaApiException(ApiFailure.REJECTED, "refused", "invalid_state")
        if (corrections.values.any { it.targetCandidateId == candidateId && it.status == "queued" }) throw WadrovaApiException(ApiFailure.REJECTED, "refused", "invalid_state")
        val id = "cor_${corrections.size + 1}"
        correctionByKey[key] = id
        correctionRequests += candidateId to correction
        val created = if (correction is CorrectionRequest.SelectAlternative) {
            val result = revise(session.key, candidateId)
            ServerCorrection(id, "succeeded", candidateId, result.id)
        } else {
            ServerCorrection(id, "queued", candidateId, null)
        }
        corrections[id] = created
        return created
    }

    override suspend fun getCorrection(connection: WadrovaConnection, id: String): ServerCorrection {
        record("getCorrection", connection)
        return corrections[id] ?: throw WadrovaApiException(ApiFailure.NOT_FOUND, "gone")
    }

    /** Finishes a queued correction the way the worker would: a new revision of the target. */
    fun completeCorrection(id: String, succeed: Boolean = true) {
        val pending = corrections.getValue(id)
        corrections[id] = if (succeed) {
            val session = sessions.entries.first { (_, list) -> list.any { it.id == pending.targetCandidateId } }.key
            pending.copy(status = "succeeded", resultCandidateId = revise(session, pending.targetCandidateId).id)
        } else {
            pending.copy(status = "failed")
        }
    }

    private fun revise(sessionId: String, targetId: String): Candidate {
        val list = sessions.getValue(sessionId)
        val target = list.first { it.id == targetId }
        val next = candidate("${targetId}r", rank = target.rank, parent = target.id, revision = target.revision + 1, phrase = target.phrase)
        list += next
        assets[next.cutout.id] = "png-${next.id}".encodeToByteArray()
        return next
    }
}
