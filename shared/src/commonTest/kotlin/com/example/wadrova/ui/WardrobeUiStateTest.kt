package com.example.wadrova.ui

import com.example.wadrova.domain.GarmentCategory
import com.example.wadrova.domain.SavedLook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WardrobeUiStateTest {

    private fun item(id: String) = sampleWardrobe.first { it.garment.id == id }

    private val initial = WardrobeUiState(sampleWardrobe)

    @Test
    fun togglingAddsAndRemovesAPiece() {
        val added = initial.toggle(item("poplin-shirt"))
        assertTrue(added.isInDraft(item("poplin-shirt")))
        assertFalse(added.toggle(item("poplin-shirt")).isInDraft(item("poplin-shirt")))
    }

    @Test
    fun replacedByPreviewsSameCategoryReplacementWithoutChangingTheDraft() {
        val state = initial.toggle(item("poplin-shirt"))
        assertEquals(listOf(item("poplin-shirt")), state.replacedBy(item("everyday-tee")))
        assertEquals(listOf("poplin-shirt"), state.draftIds)
    }

    @Test
    fun replacedByPreviewsDressReplacingTopAndBottom() {
        val state = initial.toggle(item("poplin-shirt")).toggle(item("straight-jeans"))
        assertEquals(listOf(item("poplin-shirt"), item("straight-jeans")), state.replacedBy(item("slip-dress")))
    }

    @Test
    fun replacedByIsEmptyForPiecesInTheDraftAndForAccessories() {
        val state = initial.toggle(item("leather-tote"))
        assertEquals(emptyList(), state.replacedBy(item("leather-tote")))
        assertEquals(emptyList(), state.replacedBy(item("silk-scarf")))
    }

    @Test
    fun draftItemsUseDisplayOrder() {
        val state = initial
            .toggle(item("leather-tote"))
            .toggle(item("court-sneakers"))
            .toggle(item("straight-jeans"))
            .toggle(item("poplin-shirt"))
            .toggle(item("wool-coat"))
        assertEquals(
            listOf("wool-coat", "poplin-shirt", "straight-jeans", "court-sneakers", "leather-tote"),
            state.draftItems.map { it.garment.id },
        )
    }

    @Test
    fun navigationPreservesDraftAndWardrobeFilter() {
        val state = initial
            .filterWardrobe(GarmentCategory.TOP)
            .toggle(item("poplin-shirt"))
            .openBuilder()
            .openPicker(GarmentCategory.DRESS)
            .toggle(item("slip-dress"))
            .requestLeaveBuilder()
        assertEquals(Screen.WARDROBE, state.screen)
        assertFalse(state.pickerOpen)
        assertEquals(GarmentCategory.TOP, state.wardrobeFilter)
        assertEquals(listOf("slip-dress"), state.draftIds)
    }

    @Test
    fun filtersSelectOnlyTheirCategoryAndNullSelectsEverything() {
        assertEquals(sampleWardrobe, initial.itemsIn(null))
        assertTrue(initial.itemsIn(GarmentCategory.DRESS).all { it.garment.category == GarmentCategory.DRESS })
        assertEquals(2, initial.itemsIn(GarmentCategory.DRESS).size)
    }

    private val savedLook = SavedLook("look", "Quiet Monday", listOf("poplin-shirt"), 1, 1)

    @Test
    fun wardrobeOpensOnItems() {
        assertEquals(WardrobeView.ITEMS, initial.wardrobeView)
    }

    @Test
    fun switchingViewsKeepsTheFilterAndSelection() {
        val state = initial.filterWardrobe(GarmentCategory.TOP).toggle(item("poplin-shirt"))
        val back = state.showWardrobeView(WardrobeView.OUTFITS).showWardrobeView(WardrobeView.ITEMS)
        assertEquals(GarmentCategory.TOP, back.wardrobeFilter)
        assertEquals(listOf("poplin-shirt"), back.draftIds)
    }

    @Test
    fun theArchivedListOnlyShowsInItems() {
        val archived = item("poplin-shirt").copy(archived = true)
        val state = WardrobeUiState(listOf(archived)).showArchivedPieces(true)
        assertTrue(state.isShowingArchived)
        assertFalse(state.showWardrobeView(WardrobeView.OUTFITS).isShowingArchived)
    }

    @Test
    fun aNewOutfitOpensTheBuilderOnAnEmptyDraft() {
        val state = initial.showWardrobeView(WardrobeView.OUTFITS).requestNewOutfit()
        assertEquals(Screen.BUILDER, state.screen)
        assertEquals(emptyList(), state.draftIds)
        assertEquals(null, state.openedLook)
    }

    @Test
    fun aNewOutfitAsksBeforeDiscardingASelection() {
        val selected = initial.toggle(item("poplin-shirt")).showWardrobeView(WardrobeView.OUTFITS)
        val asking = selected.requestNewOutfit()
        assertEquals(Screen.WARDROBE, asking.screen)
        assertEquals(listOf("poplin-shirt"), asking.draftIds)

        val kept = asking.keepDraft()
        assertEquals(Screen.WARDROBE, kept.screen)
        assertEquals(listOf("poplin-shirt"), kept.draftIds)

        val confirmed = asking.confirmDiscard()
        assertEquals(Screen.BUILDER, confirmed.screen)
        assertEquals(emptyList(), confirmed.draftIds)
    }

    @Test
    fun aNewOutfitDoesNotEditAnOpenedLook() {
        val opened = initial.copy(savedLooks = listOf(savedLook)).openLook(savedLook).requestLeaveBuilder()
        val state = opened.requestNewOutfit()
        assertEquals(null, state.openedLook)
        assertEquals(emptyList(), state.draftIds)
        assertEquals(listOf(savedLook), state.savedLooks)
    }

    @Test
    fun startingANewLookInsideTheBuilderStillStaysThere() {
        val state = initial.toggle(item("poplin-shirt")).openBuilder().requestNewOutfit().confirmDiscard()
        assertEquals(Screen.BUILDER, state.screen)
        assertEquals(emptyList(), state.draftIds)
    }

    @Test
    fun detailsOpenAndReturnWithoutTouchingTheFilterOrSelection() {
        val state = initial.filterWardrobe(GarmentCategory.TOP).toggle(item("poplin-shirt"))
        val details = state.openGarmentDetails(item("everyday-tee"))
        assertEquals(Screen.GARMENT_DETAILS, details.screen)
        assertEquals(item("everyday-tee"), details.detailItem)
        assertEquals(listOf("poplin-shirt"), details.draftIds)

        val back = details.openWardrobe()
        assertEquals(Screen.WARDROBE, back.screen)
        assertEquals(GarmentCategory.TOP, back.wardrobeFilter)
        assertEquals(listOf("poplin-shirt"), back.draftIds)
        assertEquals(null, back.detailItem)
    }

    @Test
    fun theFormReturnsToWhereItWasOpenedFrom() {
        val fromDetails = initial.openGarmentDetails(item("poplin-shirt")).openEditGarment(item("poplin-shirt"))
        assertEquals(Screen.GARMENT_DETAILS, fromDetails.closeGarmentForm().screen)
        assertEquals(Screen.WARDROBE, initial.openEditGarment(item("poplin-shirt")).closeGarmentForm().screen)
    }

    @Test
    fun stylingAPieceOpensTheBuilderWithIt() {
        val state = initial.toggle(item("wide-trousers")).requestStylePiece(item("poplin-shirt"))
        assertEquals(Screen.BUILDER, state.screen)
        assertEquals(listOf("wide-trousers", "poplin-shirt"), state.draftIds)
    }

    @Test
    fun stylingAPieceAlreadyInTheDraftKeepsIt() {
        val state = initial.toggle(item("poplin-shirt")).requestStylePiece(item("poplin-shirt"))
        assertEquals(Screen.BUILDER, state.screen)
        assertEquals(listOf("poplin-shirt"), state.draftIds)
    }

    @Test
    fun aConflictAsksBeforeReplacingPieces() {
        val draft = initial.toggle(item("poplin-shirt")).toggle(item("straight-jeans"))
        val asking = draft.requestStylePiece(item("slip-dress"))
        assertEquals(Screen.WARDROBE, asking.screen)
        assertEquals(draft.draftIds, asking.draftIds)
        assertEquals(listOf(item("poplin-shirt"), item("straight-jeans")), asking.pendingStyle?.replaced)

        assertEquals(draft.draftIds, asking.keepCurrentLook().draftIds)
        assertEquals(null, asking.keepCurrentLook().pendingStyle)

        val confirmed = asking.confirmStylePiece()
        assertEquals(Screen.BUILDER, confirmed.screen)
        assertEquals(listOf("slip-dress"), confirmed.draftIds)
    }

    @Test
    fun stylingWithAnUneditedSavedLookOpenStartsANewLook() {
        val opened = initial.copy(savedLooks = listOf(savedLook)).openLook(savedLook).requestLeaveBuilder()
        val state = opened.requestStylePiece(item("everyday-tee"))
        assertEquals(null, state.pendingStyle)
        assertEquals(null, state.openedLook)
        assertEquals(listOf("everyday-tee"), state.draftIds)
    }

    @Test
    fun aSavedLookNeverEntersTheSelection() {
        val selected = initial.toggle(item("wide-trousers"))
        val opened = selected.openLook(savedLook)
        assertEquals(listOf("poplin-shirt"), opened.builderIds)
        assertEquals(listOf("wide-trousers"), opened.draftIds)

        val edited = opened.toggle(item("leather-tote"))
        assertEquals(listOf("wide-trousers"), edited.draftIds)
        assertEquals(listOf("wide-trousers"), edited.requestLeaveBuilder().confirmDiscard().draftIds)
    }

    @Test
    fun buildALookNeverReopensAVisitedLook() {
        val state = initial.openLook(savedLook).requestLeaveBuilder().toggle(item("everyday-tee")).openBuilder()
        assertEquals(null, state.openedLook)
        assertEquals(listOf("everyday-tee"), state.builderIds)
    }

    @Test
    fun theBuilderReturnsToDetailsWhenStyledFromThere() {
        val state = initial.filterWardrobe(GarmentCategory.TOP)
            .openGarmentDetails(item("poplin-shirt"))
            .requestStylePiece(item("poplin-shirt"))
        val details = state.requestLeaveBuilder()
        assertEquals(Screen.GARMENT_DETAILS, details.screen)
        assertEquals(item("poplin-shirt"), details.detailItem)
        assertEquals(listOf("poplin-shirt"), details.draftIds)

        val wardrobe = details.openWardrobe()
        assertEquals(Screen.WARDROBE, wardrobe.screen)
        assertEquals(GarmentCategory.TOP, wardrobe.wardrobeFilter)
    }

    @Test
    fun aConfirmedStyleReplacementAlsoReturnsToDetails() {
        val state = initial.toggle(item("everyday-tee"))
            .openGarmentDetails(item("poplin-shirt"))
            .requestStylePiece(item("poplin-shirt"))
            .confirmStylePiece()
        assertEquals(Screen.BUILDER, state.screen)
        assertEquals(Screen.GARMENT_DETAILS, state.requestLeaveBuilder().screen)
    }

    @Test
    fun theBuilderReturnsToTheWardrobeViewItCameFrom() {
        val fromItems = initial.toggle(item("poplin-shirt")).openBuilder().requestLeaveBuilder()
        assertEquals(Screen.WARDROBE, fromItems.screen)
        assertEquals(WardrobeView.ITEMS, fromItems.wardrobeView)

        val fromOutfits = initial.showWardrobeView(WardrobeView.OUTFITS).openLook(savedLook).requestLeaveBuilder()
        assertEquals(Screen.WARDROBE, fromOutfits.screen)
        assertEquals(WardrobeView.OUTFITS, fromOutfits.wardrobeView)
    }

    @Test
    fun leavingAnUnchangedLookDoesNotAsk() {
        val state = initial.openLook(savedLook).requestLeaveBuilder()
        assertEquals(null, state.pendingDiscard)
        assertEquals(Screen.WARDROBE, state.screen)
        assertEquals(null, state.openedLook)
    }

    @Test
    fun leavingAModifiedLookAsksAndCanKeepEditing() {
        val asking = initial.openLook(savedLook).toggle(item("leather-tote")).requestLeaveBuilder()
        assertEquals(PendingDiscard.LEAVE_LOOK, asking.pendingDiscard)
        assertEquals(Screen.BUILDER, asking.screen)

        val kept = asking.keepDraft()
        assertEquals(Screen.BUILDER, kept.screen)
        assertEquals(listOf("poplin-shirt", "leather-tote"), kept.builderIds)

        val left = asking.confirmDiscard()
        assertEquals(Screen.WARDROBE, left.screen)
        assertEquals(null, left.openedLook)
        assertEquals(emptyList(), left.draftIds)
    }

    @Test
    fun leavingANewDraftKeepsItWithoutAsking() {
        val state = initial.toggle(item("poplin-shirt")).openBuilder().requestLeaveBuilder()
        assertEquals(null, state.pendingDiscard)
        assertEquals(listOf("poplin-shirt"), state.draftIds)
        assertEquals(listOf("poplin-shirt"), state.openBuilder().builderIds)
    }

    @Test
    fun anEmptyDraftNeedsNoConfirmation() {
        val opened = initial.requestNewOutfit()
        assertEquals(null, opened.pendingDiscard)
        assertEquals(null, opened.requestLeaveBuilder().pendingDiscard)
    }

    @Test
    fun thePieceCountFollowsTheLookBeingEditedNotTheSelection() {
        val opened = initial.toggle(item("wide-trousers")).toggle(item("leather-tote")).openLook(savedLook).openPicker()
        assertEquals(1, opened.builderPieceCount)
        assertEquals(2, opened.toggle(item("silk-scarf")).builderPieceCount)
        assertEquals(0, initial.openLook(savedLook).toggle(item("poplin-shirt")).builderPieceCount)
        assertEquals(1, initial.toggle(item("poplin-shirt")).openBuilder().builderPieceCount)
    }

    @Test
    fun anEmptiedLookOffersAddingPiecesOrLeavingAfterConfirming() {
        val emptied = initial.copy(savedLooks = listOf(savedLook)).openLook(savedLook).toggle(item("poplin-shirt"))
        assertTrue(emptied.isEditingEmptyLook)
        assertFalse(emptied.canSaveDraft)
        assertEquals(emptied, emptied.openSaveSheet())

        val refilled = emptied.openPicker().toggle(item("everyday-tee"))
        assertFalse(refilled.isEditingEmptyLook)
        assertTrue(refilled.canSaveDraft)
        assertEquals(savedLook, refilled.openedLook)

        val asking = emptied.requestLeaveBuilder()
        assertEquals(PendingDiscard.LEAVE_LOOK, asking.pendingDiscard)
        assertEquals(Screen.BUILDER, asking.screen)
        assertTrue(asking.keepDraft().isEditingEmptyLook)
        assertEquals(PendingDiscard.START_NEW, emptied.requestNewOutfit().pendingDiscard)

        val left = asking.confirmDiscard()
        assertEquals(null, left.openedLook)
        assertEquals(Screen.WARDROBE, left.screen)
        assertEquals(listOf(savedLook), left.savedLooks)
        assertEquals(listOf("poplin-shirt"), left.savedLooks.single().garmentIds)
    }

    @Test
    fun startingANewLookFromAnEditedLookAsksFirst() {
        val edited = initial.openLook(savedLook).toggle(item("leather-tote"))
        val asking = edited.requestNewOutfit()
        assertEquals(PendingDiscard.START_NEW, asking.pendingDiscard)
        val started = asking.confirmDiscard()
        assertEquals(null, started.openedLook)
        assertEquals(emptyList(), started.builderIds)
        assertEquals(Screen.BUILDER, started.screen)
    }

    @Test
    fun archivedPiecesCantBeStyled() {
        val archived = item("poplin-shirt").copy(archived = true)
        val state = WardrobeUiState(listOf(archived))
        assertEquals(state, state.requestStylePiece(archived))
    }
}
