package com.example.wadrova

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wadrova.data.LocalRepositories
import com.example.wadrova.ui.BrandSplash
import com.example.wadrova.ui.BrandSplashViewModel
import com.example.wadrova.ui.CreateModelScreen
import com.example.wadrova.ui.ExtractionScreen
import com.example.wadrova.ui.GarmentDetailsScreen
import com.example.wadrova.ui.GarmentFormScreen
import com.example.wadrova.ui.OutfitBuilderScreen
import com.example.wadrova.ui.LocalPhotoLoader
import com.example.wadrova.ui.MirrorDialogs
import com.example.wadrova.ui.MirrorHomeScreen
import com.example.wadrova.ui.MirrorRoute
import com.example.wadrova.ui.MirrorSettingsScreen
import com.example.wadrova.ui.MirrorViewModel
import com.example.wadrova.ui.PlaceholderScreen
import com.example.wadrova.ui.PlatformBackHandler
import com.example.wadrova.ui.PrimaryNavigationBar
import com.example.wadrova.ui.PrimaryTab
import com.example.wadrova.ui.START_TAB
import com.example.wadrova.ui.TryOnScreen
import com.example.wadrova.ui.Screen
import com.example.wadrova.ui.WadrovaColors
import com.example.wadrova.ui.WadrovaTheme
import com.example.wadrova.ui.WardrobeScreen
import com.example.wadrova.ui.WardrobeViewModel
import com.example.wadrova.ui.placeholderBackTab
import com.example.wadrova.ui.primaryBarInsets
import com.example.wadrova.ui.rememberReducedMotion
import com.example.wadrova.ui.showsPrimaryBar

/** Gives every ViewModel the same repositories over one database connection. */
private class RepositoriesHolder(val repositories: LocalRepositories) : ViewModel()

/** [replayIntro] plays the brand splash even if it has been seen, for testing it without clearing app data. */
@Composable
fun App(replayIntro: Boolean = false, createRepositories: () -> LocalRepositories) {
    val holder = viewModel { RepositoriesHolder(createRepositories()) }
    val repositories = holder.repositories
    val reducedMotion = rememberReducedMotion()
    val splash = viewModel { BrandSplashViewModel(repositories.brandIntro, replayIntro, reducedMotion) }
    val viewModel = viewModel { WardrobeViewModel(repositories.savedLooks, repositories.garments, repositories.photos) }
    val mirror = viewModel {
        MirrorViewModel(
            repositories.mirror,
            repositories.mirrorBackend,
            repositories.photos,
            repositories.generatedImages,
            repositories.garments,
            onGarmentChanged = { garment -> viewModel.update { it.garmentChangedInPlace(garment) } },
        )
    }
    val state = viewModel.state
    val mirrorState = mirror.state
    var tab by rememberSaveable { mutableStateOf(START_TAB) }
    var stylistFromWardrobe by rememberSaveable { mutableStateOf(false) }
    val tabStates = rememberSaveableStateHolder()
    // Up here so each list keeps its position across the builder and the garment form.
    val itemsGrid = rememberLazyGridState()
    val outfitsGrid = rememberLazyGridState()
    val detailsScroll = remember(state.detailGarmentId) { ScrollState(0) }

    WadrovaTheme {
        CompositionLocalProvider(LocalPhotoLoader provides mirror.imageLoader) {
            val showBar = showsPrimaryBar(tab, state.screen, mirrorState.route, mirrorState.extraction != null)
            Column(Modifier.fillMaxSize().background(WadrovaColors.Ivory)) {
                val barInsets = primaryBarInsets.only(WindowInsetsSides.Bottom)
                Box(Modifier.weight(1f).fillMaxWidth().then(if (showBar) Modifier.consumeWindowInsets(barInsets) else Modifier)) {
                    // Keeps each tab's scroll position while another tab is showing.
                    tabStates.SaveableStateProvider(tab.name) {
                        when {
                            mirrorState.extraction != null -> {
                                PlatformBackHandler { mirror.keepOriginal() }
                                ExtractionScreen(mirrorState, mirror)
                            }
                            tab == PrimaryTab.WARDROBE -> when (state.screen) {
                                Screen.WARDROBE -> {
                                    PlatformBackHandler {
                                        if (state.isShowingArchived) {
                                            viewModel.update { it.showArchivedPieces(false) }
                                        } else {
                                            tab = START_TAB
                                        }
                                    }
                                    WardrobeScreen(state, viewModel::update, viewModel::loadRecords, itemsGrid, outfitsGrid) {
                                        stylistFromWardrobe = true
                                        tab = PrimaryTab.STYLIST
                                    }
                                }
                                Screen.GARMENT_DETAILS -> {
                                    PlatformBackHandler { viewModel.update { it.openWardrobe() } }
                                    GarmentDetailsScreen(state, viewModel::update, detailsScroll)
                                }
                                Screen.BUILDER -> {
                                    PlatformBackHandler { viewModel.update { it.requestLeaveBuilder() } }
                                    OutfitBuilderScreen(state, viewModel::update, viewModel::save)
                                }
                                Screen.GARMENT_FORM -> {
                                    PlatformBackHandler { viewModel.requestCancelGarmentForm() }
                                    GarmentFormScreen(
                                        state,
                                        viewModel::update,
                                        viewModel::submitGarmentForm,
                                        viewModel::setEditedGarmentArchived,
                                        viewModel::requestCancelGarmentForm,
                                        viewModel::cancelGarmentForm,
                                        viewModel::onPhotoPicked,
                                        viewModel::removeFormPhoto,
                                        mirror::startExtraction,
                                        mirror::clearExtractedImage,
                                    )
                                }
                            }
                            tab == PrimaryTab.MIRROR -> when (mirrorState.route) {
                                // Back leaves the app from here, the start tab.
                                MirrorRoute.HOME -> MirrorHomeScreen(mirrorState, mirror, state.items)
                                MirrorRoute.CREATE_MODEL -> {
                                    PlatformBackHandler { mirror.cancelCreateModel() }
                                    CreateModelScreen(mirrorState, mirror)
                                }
                                MirrorRoute.TRY_ON -> {
                                    PlatformBackHandler { mirror.discardTryOn() }
                                    TryOnScreen(mirrorState, mirror, state.items)
                                }
                                MirrorRoute.SETTINGS -> {
                                    PlatformBackHandler { mirror.openRoute(MirrorRoute.HOME) }
                                    MirrorSettingsScreen(mirrorState, mirror)
                                }
                            }
                            else -> {
                                PlatformBackHandler {
                                    tab = placeholderBackTab(tab, stylistFromWardrobe)
                                    stylistFromWardrobe = false
                                }
                                PlaceholderScreen(tab)
                            }
                        }
                    }
                }
                if (showBar) {
                    PrimaryNavigationBar(tab) {
                        if (it != tab) stylistFromWardrobe = false
                        tab = it
                    }
                }
            }
        }

        MirrorDialogs(mirrorState, mirror)

        state.pendingReplacement?.let { pending ->
            val replaced = pending.replaced.joinToString(" and ") { it.name }
            val several = pending.replaced.size > 1
            AlertDialog(
                onDismissRequest = { viewModel.update { it.cancelReplacement() } },
                title = { Text("Replace $replaced with ${pending.item.name}?") },
                text = {
                    Text(
                        if (several) "They'll come out of this look but stay in your wardrobe." else "It'll come out of this look but stay in your wardrobe.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.update { it.confirmReplacement() } }) {
                        Text(if (several) "Replace pieces" else "Replace piece")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.update { it.cancelReplacement() } }) { Text("Cancel") }
                },
            )
        }

        state.pendingLookDelete?.let { pending ->
            AlertDialog(
                onDismissRequest = { viewModel.update { it.cancelDeleteLook() } },
                title = { Text("Delete \"${pending.look.name}\"?") },
                text = {
                    Column {
                        Text("This saved outfit will be permanently removed. The individual clothing pieces in your wardrobe will not be deleted.")
                        pending.error?.let { error ->
                            Text(error, Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !pending.deleting, onClick = viewModel::deleteLook) {
                        Text(if (pending.deleting) "Deleting…" else "Delete look", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(enabled = !pending.deleting, onClick = { viewModel.update { it.cancelDeleteLook() } }) { Text("Cancel") }
                },
            )
        }

        if (state.pendingDiscard != null) {
            AlertDialog(
                onDismissRequest = { viewModel.update { it.keepDraft() } },
                title = { Text("Discard your changes?") },
                text = { Text(state.discardMessage) },
                confirmButton = {
                    TextButton(onClick = { viewModel.update { it.confirmDiscard() } }) { Text("Discard") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.update { it.keepDraft() } }) { Text("Keep editing") }
                },
            )
        }

        // Drawn last, over the app, which has already started underneath. The app is light-only for
        // now, so the light splash plays; the dark file is bundled for when a dark theme exists.
        BrandSplash(splash)
    }
}
