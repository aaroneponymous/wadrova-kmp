package com.example.wadrova

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wadrova.data.LocalRepositories
import com.example.wadrova.ui.CreateModelScreen
import com.example.wadrova.ui.ExtractionScreen
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
import com.example.wadrova.ui.SavedLooksScreen
import com.example.wadrova.ui.Screen
import com.example.wadrova.ui.WadrovaColors
import com.example.wadrova.ui.WadrovaTheme
import com.example.wadrova.ui.WardrobeScreen
import com.example.wadrova.ui.WardrobeViewModel
import com.example.wadrova.ui.primaryBarInsets
import com.example.wadrova.ui.showsPrimaryBar

/** Gives every ViewModel the same repositories over one database connection. */
private class RepositoriesHolder(val repositories: LocalRepositories) : ViewModel()

@Composable
fun App(createRepositories: () -> LocalRepositories) {
    val holder = viewModel { RepositoriesHolder(createRepositories()) }
    val repositories = holder.repositories
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
    val tabStates = rememberSaveableStateHolder()

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
                                        if (state.showArchived && state.archivedItems.isNotEmpty()) {
                                            viewModel.update { it.showArchivedPieces(false) }
                                        } else {
                                            tab = START_TAB
                                        }
                                    }
                                    WardrobeScreen(state, viewModel::update)
                                }
                                Screen.BUILDER -> {
                                    PlatformBackHandler { viewModel.update { it.openWardrobe() } }
                                    OutfitBuilderScreen(state, viewModel::update, viewModel::save)
                                }
                                Screen.SAVED_LOOKS -> {
                                    PlatformBackHandler { viewModel.update { it.openWardrobe() } }
                                    SavedLooksScreen(state, viewModel::update, viewModel::loadRecords)
                                }
                                Screen.GARMENT_FORM -> {
                                    PlatformBackHandler { viewModel.cancelGarmentForm() }
                                    GarmentFormScreen(
                                        state,
                                        viewModel::update,
                                        viewModel::submitGarmentForm,
                                        viewModel::setEditedGarmentArchived,
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
                                PlatformBackHandler { tab = START_TAB }
                                PlaceholderScreen(tab)
                            }
                        }
                    }
                }
                if (showBar) PrimaryNavigationBar(tab) { tab = it }
            }
        }

        MirrorDialogs(mirrorState, mirror)

        if (state.pendingDiscard != null) {
            AlertDialog(
                onDismissRequest = { viewModel.update { it.keepDraft() } },
                title = { Text("Discard your changes?") },
                text = { Text("The look you're working on hasn't been saved.") },
                confirmButton = {
                    TextButton(onClick = { viewModel.update { it.confirmDiscard() } }) { Text("Discard") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.update { it.keepDraft() } }) { Text("Keep editing") }
                },
            )
        }
    }
}
