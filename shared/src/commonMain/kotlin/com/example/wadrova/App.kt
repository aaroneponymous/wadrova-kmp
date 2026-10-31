package com.example.wadrova

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.example.wadrova.ui.PlatformBackHandler
import com.example.wadrova.ui.TryOnScreen
import com.example.wadrova.ui.SavedLooksScreen
import com.example.wadrova.ui.Screen
import com.example.wadrova.ui.WadrovaTheme
import com.example.wadrova.ui.WardrobeScreen
import com.example.wadrova.ui.WardrobeViewModel

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

    WadrovaTheme {
        CompositionLocalProvider(LocalPhotoLoader provides mirror.imageLoader) {
        if (mirrorState.extraction != null) {
            PlatformBackHandler { mirror.keepOriginal() }
            ExtractionScreen(mirrorState, mirror)
        } else when (state.screen) {
            Screen.WARDROBE -> WardrobeScreen(state, viewModel::update)
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
            Screen.MIRROR -> when (mirrorState.route) {
                MirrorRoute.HOME -> {
                    PlatformBackHandler { viewModel.update { it.openWardrobe() } }
                    MirrorHomeScreen(mirrorState, mirror, state.items) { viewModel.update { it.openWardrobe() } }
                }
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
