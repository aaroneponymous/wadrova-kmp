package com.example.wadrova.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrimaryNavigationTest {
    private fun visible(
        tab: PrimaryTab,
        screen: Screen = Screen.WARDROBE,
        route: MirrorRoute = MirrorRoute.HOME,
        extracting: Boolean = false,
    ) = showsPrimaryBar(tab, screen, route, extracting)

    @Test
    fun tabsAreInTheAgreedOrder() {
        assertEquals(listOf("Home", "Wardrobe", "Mirror", "Stylist", "Discover"), PrimaryTab.entries.map { it.label })
    }

    @Test
    fun theAppStartsOnMirrorForThisMilestone() {
        assertEquals(PrimaryTab.MIRROR, START_TAB)
    }

    @Test
    fun backFromStylistReturnsToWardrobeOnlyWhenOpenedFromIt() {
        assertEquals(PrimaryTab.WARDROBE, placeholderBackTab(PrimaryTab.STYLIST, openedFromWardrobe = true))
        assertEquals(START_TAB, placeholderBackTab(PrimaryTab.STYLIST, openedFromWardrobe = false))
        assertEquals(START_TAB, placeholderBackTab(PrimaryTab.HOME, openedFromWardrobe = false))
        assertEquals(START_TAB, placeholderBackTab(PrimaryTab.DISCOVER, openedFromWardrobe = true))
    }

    @Test
    fun everyRootScreenShowsTheBar() {
        PrimaryTab.entries.forEach { assertTrue(visible(it), "$it") }
    }

    @Test
    fun wardrobeDetailScreensHideTheBar() {
        listOf(Screen.BUILDER, Screen.GARMENT_DETAILS, Screen.GARMENT_FORM).forEach {
            assertFalse(visible(PrimaryTab.WARDROBE, screen = it), "$it")
        }
    }

    @Test
    fun mirrorWorkflowsHideTheBar() {
        listOf(MirrorRoute.CREATE_MODEL, MirrorRoute.TRY_ON, MirrorRoute.SETTINGS).forEach {
            assertFalse(visible(PrimaryTab.MIRROR, route = it), "$it")
        }
    }

    @Test
    fun anotherTabsDetailScreenDoesNotHideTheBar() {
        assertTrue(visible(PrimaryTab.MIRROR, screen = Screen.BUILDER))
        assertTrue(visible(PrimaryTab.WARDROBE, route = MirrorRoute.SETTINGS))
    }

    @Test
    fun extractionHidesTheBar() {
        assertFalse(visible(PrimaryTab.WARDROBE, screen = Screen.GARMENT_FORM, extracting = true))
        assertFalse(visible(PrimaryTab.WARDROBE, extracting = true))
    }
}
