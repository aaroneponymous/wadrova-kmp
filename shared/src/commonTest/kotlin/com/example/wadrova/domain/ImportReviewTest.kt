package com.example.wadrova.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

fun testCandidate(
    id: String,
    rank: Int = 0,
    parent: String? = null,
    revision: Int = 1,
    detected: Boolean = true,
    supersededBy: String? = null,
    box: CandidateBox = CandidateBox(0, 0, 100, 100),
    suggested: GarmentCategory? = null,
    phrase: String = "jacket",
) = Candidate(
    id = id,
    rank = rank,
    phrase = phrase,
    suggestedCategory = suggested,
    origin = if (detected) "detection" else "below_threshold",
    detected = detected,
    revision = revision,
    parentId = parent,
    supersededBy = supersededBy,
    box = box,
    cutout = CandidateAsset("ast_c_$id", "sha-$id", "image/png", box.width, box.height),
    preview = CandidateAsset("ast_p_$id", "shap-$id", "image/jpeg", null, null),
)

class ImportReviewTest {
    private val jacket1 = testCandidate("cnd_j1", rank = 0, supersededBy = "cnd_j2")
    private val jacket2 = testCandidate("cnd_j2", rank = 5, parent = "cnd_j1", revision = 2)
    private val pants = testCandidate("cnd_p1", rank = 1, phrase = "pants")
    private val faint = testCandidate("cnd_b1", rank = 2, detected = false)

    @Test
    fun revisionsGroupIntoOneLineagePerGarmentInRankOrder() {
        val lineages = groupLineages(listOf(pants, jacket2, faint, jacket1))
        assertEquals(listOf("cnd_j1", "cnd_p1", "cnd_b1"), lineages.map { it.id })
        val jacket = lineages.first()
        assertEquals(listOf("cnd_j1", "cnd_j2"), jacket.revisions.map { it.id })
        assertEquals("cnd_j2", jacket.current.id)
        assertTrue(jacket.mustBeDecided)
        assertFalse(lineages.last().mustBeDecided)
    }

    @Test
    fun aRevisionWhoseParentIsntListedStartsItsOwnLineage() {
        assertEquals(listOf("cnd_j2"), groupLineages(listOf(jacket2)).map { it.id })
    }

    @Test
    fun aPinnedRevisionStaysEvenWhenANewerOneArrives() {
        val lineage = groupLineages(listOf(jacket1, jacket2)).single()
        assertEquals("cnd_j2", ReviewChoice("cnd_j1", "g").revisionIn(lineage).id)
        assertEquals("cnd_j1", ReviewChoice("cnd_j1", "g", chosenCandidateId = "cnd_j1").revisionIn(lineage).id)
        // A pin to a revision that no longer exists falls back to the newest instead of failing.
        assertEquals("cnd_j2", ReviewChoice("cnd_j1", "g", chosenCandidateId = "gone").revisionIn(lineage).id)
    }

    @Test
    fun theManifestDecidesEveryRequiredLineageOnceAndKeepsTheSavedRevision() {
        val lineages = groupLineages(listOf(jacket1, jacket2, pants, faint))
        val manifest = buildManifest(
            lineages,
            mapOf(
                "cnd_j1" to ReviewChoice("cnd_j1", "user-a", selected = true, chosenCandidateId = "cnd_j1"),
                "cnd_p1" to ReviewChoice("cnd_p1", "user-b", selected = false),
            ),
        )
        assertEquals(listOf("cnd_j1" to "user-a"), manifest.accepted)
        // Pants rejected with its current revision; the below-threshold lineage is optional and left out.
        assertEquals(listOf("cnd_p1"), manifest.rejected)
    }

    @Test
    fun nothingSelectedRejectsEverythingAndNoCandidatesIsAnEmptyManifest() {
        val lineages = groupLineages(listOf(jacket1, jacket2, pants, faint))
        assertEquals(FinalizeManifest(emptyList(), listOf("cnd_j2", "cnd_p1")), buildManifest(lineages, emptyMap()))
        assertEquals(FinalizeManifest(emptyList(), emptyList()), buildManifest(emptyList(), emptyMap()))
    }

    @Test
    fun aSelectedPieceTheServerNoLongerListsStopsTheManifest() {
        assertFailsWith<ManifestException> {
            buildManifest(groupLineages(listOf(pants)), mapOf("cnd_gone" to ReviewChoice("cnd_gone", "user-x", selected = true)))
        }
    }

    @Test
    fun detailsAreCompleteOnlyWithNameCategoryAndColor() {
        assertFalse(ReviewChoice("l", "g", name = "Coat", category = GarmentCategory.OUTERWEAR).hasRequiredDetails)
        assertTrue(ReviewChoice("l", "g", name = "Coat", category = GarmentCategory.OUTERWEAR, color = "Grey").hasRequiredDetails)
    }

    @Test
    fun overlapIsMeasuredAgainstTheSmallerBox() {
        val big = CandidateBox(0, 0, 100, 100)
        assertEquals(1.0, big.overlap(CandidateBox(10, 10, 20, 20)))
        assertEquals(0.0, big.overlap(CandidateBox(100, 100, 120, 120)))
        assertEquals(0.5, big.overlap(CandidateBox(50, 0, 150, 100)))
    }

    @Test
    fun tapsMapToSourcePixelsThroughFitLetterboxing() {
        // A 1000x2000 photo in a 400x400 box is drawn 200x400, centred with 100 px bars left and right.
        assertEquals(0 to 0, fitTapToImage(100f, 0f, 400f, 400f, 1000, 2000))
        assertEquals(500 to 1000, fitTapToImage(200f, 200f, 400f, 400f, 1000, 2000))
        assertNull(fitTapToImage(50f, 200f, 400f, 400f, 1000, 2000))
        assertNull(fitTapToImage(300f, 200f, 400f, 400f, 1000, 2000))
        // A wide photo letterboxes top and bottom instead: 1600x1200 is drawn 400x300 with 50 px bars.
        assertEquals(1596 to 0, fitTapToImage(399f, 50f, 400f, 400f, 1600, 1200))
        assertNull(fitTapToImage(400f, 200f, 400f, 400f, 1600, 1200))
        assertNull(fitTapToImage(200f, 10f, 400f, 400f, 1600, 1200))
        assertNull(fitTapToImage(10f, 10f, 0f, 400f, 1600, 1200))
    }
}
