package com.example.wadrova.domain

/** One stored image of a candidate. [sha256] is what the downloaded bytes must hash to. */
data class CandidateAsset(val id: String, val sha256: String, val mediaType: String, val width: Int?, val height: Int?)

data class CandidateBox(val x1: Int, val y1: Int, val x2: Int, val y2: Int) {
    val width: Int get() = x2 - x1
    val height: Int get() = y2 - y1

    /** Intersection area over the smaller box, so a part inside a larger region still counts as overlapping. */
    fun overlap(other: CandidateBox): Double {
        val w = minOf(x2, other.x2) - maxOf(x1, other.x1)
        val h = minOf(y2, other.y2) - maxOf(y1, other.y1)
        if (w <= 0 || h <= 0) return 0.0
        val smaller = minOf(width.toLong() * height, other.width.toLong() * other.height).coerceAtLeast(1)
        return (w.toLong() * h).toDouble() / smaller
    }
}

/**
 * A SAM 3 segmentation result. It's a region of the photo, not a recognised garment: [phrase] is the vocabulary
 * phrase that found it, and [suggestedCategory] is set only when the backend's (provisional) resolver settled on one.
 */
data class Candidate(
    val id: String,
    val rank: Int,
    val phrase: String,
    val suggestedCategory: GarmentCategory?,
    val origin: String,
    val detected: Boolean,
    val revision: Int,
    val parentId: String?,
    val supersededBy: String?,
    val box: CandidateBox,
    val cutout: CandidateAsset,
    val preview: CandidateAsset,
)

/**
 * A candidate and all its revisions: one garment as far as the user is concerned. The backend identifies a lineage
 * only through parent links, so its id here is the first revision's id (the backend's `lineage_id` is the same).
 */
data class Lineage(val id: String, val revisions: List<Candidate>) {
    /** The newest revision. Only it can be corrected. */
    val current: Candidate get() = revisions.last()

    /** Lineages whose current revision is a detection must each be decided when finalizing. */
    val mustBeDecided: Boolean get() = current.detected

    fun revision(id: String?): Candidate? = revisions.find { it.id == id }
}

/**
 * Groups every candidate of a session (listed with superseded and below-threshold ones) into lineages, in the order
 * of their first revision's rank. Revisions are chained by parent id; a revision whose parent isn't listed starts its
 * own lineage rather than being dropped.
 */
fun groupLineages(candidates: List<Candidate>): List<Lineage> {
    val byId = candidates.associateBy { it.id }
    fun root(candidate: Candidate): Candidate {
        var node = candidate
        val seen = mutableSetOf(node.id)
        while (true) {
            val parent = node.parentId?.let(byId::get) ?: return node
            if (!seen.add(parent.id)) return node
            node = parent
        }
    }
    return candidates.groupBy { root(it).id }
        .map { (rootId, revisions) -> Lineage(rootId, revisions.sortedWith(compareBy({ it.revision }, { it.id }))) }
        .sortedWith(compareBy({ it.revisions.first().rank }, { it.id }))
}

/** What the user decided about one lineage, as stored on the device. */
data class ReviewChoice(
    val lineageId: String,
    /** The local garment id this lineage becomes if saved. Created once, so retries never make a second garment. */
    val garmentId: String,
    val selected: Boolean = false,
    /** A revision the user picked on purpose. Null follows the newest revision. */
    val chosenCandidateId: String? = null,
    val name: String? = null,
    val category: GarmentCategory? = null,
    val color: String? = null,
    val correction: PendingCorrection? = null,
    val correctionMessage: String? = null,
) {
    /** The revision this lineage stands for: the pinned one while it still exists, otherwise the newest. */
    fun revisionIn(lineage: Lineage): Candidate = lineage.revision(chosenCandidateId) ?: lineage.current

    val hasRequiredDetails: Boolean get() = !name.isNullOrBlank() && category != null && !color.isNullOrBlank()
}

/** A correction sent (or about to be sent) for a lineage. [key] is its Idempotency-Key, kept so a resend replays. */
data class PendingCorrection(val key: String, val id: String?, val method: String)

/** The finalize body: each accepted candidate with its local garment id, and the rejected candidate ids. */
data class FinalizeManifest(val accepted: List<Pair<String, String>>, val rejected: List<String>)

class ManifestException(message: String) : Exception(message)

/**
 * Every lineage that must be decided appears exactly once: selected ones as accepted with the revision the user
 * saved, the rest as rejected with their newest revision. Below-threshold lineages are left out unless selected.
 * Throws if a selected lineage is no longer listed, which would make the manifest incomplete.
 */
fun buildManifest(lineages: List<Lineage>, choices: Map<String, ReviewChoice>): FinalizeManifest {
    val known = lineages.associateBy { it.id }
    choices.values.filter { it.selected && it.lineageId !in known }.forEach {
        throw ManifestException("A selected piece is no longer part of this import.")
    }
    val accepted = mutableListOf<Pair<String, String>>()
    val rejected = mutableListOf<String>()
    lineages.forEach { lineage ->
        val choice = choices[lineage.id]
        when {
            choice != null && choice.selected -> accepted += choice.revisionIn(lineage).id to choice.garmentId
            lineage.mustBeDecided -> rejected += lineage.current.id
        }
    }
    return FinalizeManifest(accepted, rejected)
}

/** Maps a tap on an image drawn with ContentScale.Fit inside a box to source-image pixels, or null outside the image. */
fun fitTapToImage(tapX: Float, tapY: Float, boxWidth: Float, boxHeight: Float, imageWidth: Int, imageHeight: Int): Pair<Int, Int>? {
    if (boxWidth <= 0f || boxHeight <= 0f || imageWidth <= 0 || imageHeight <= 0) return null
    val scale = minOf(boxWidth / imageWidth, boxHeight / imageHeight)
    val drawnWidth = imageWidth * scale
    val drawnHeight = imageHeight * scale
    val left = (boxWidth - drawnWidth) / 2
    val top = (boxHeight - drawnHeight) / 2
    val x = (tapX - left) / scale
    val y = (tapY - top) / scale
    if (x < 0 || y < 0 || x >= imageWidth || y >= imageHeight) return null
    return x.toInt() to y.toInt()
}
