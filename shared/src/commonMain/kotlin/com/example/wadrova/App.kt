package com.example.wadrova

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wadrova.data.LocalRepositories
import com.example.wadrova.ui.GarmentFormScreen
import com.example.wadrova.ui.OutfitBuilderScreen
import com.example.wadrova.ui.LocalPhotoLoader
import com.example.wadrova.ui.PlatformBackHandler
import com.example.wadrova.ui.SavedLooksScreen
import com.example.wadrova.ui.Screen
import com.example.wadrova.ui.WadrovaTheme
import com.example.wadrova.ui.WardrobeScreen
import com.example.wadrova.ui.WardrobeViewModel

@Composable
fun App(createRepositories: () -> LocalRepositories) {
    val viewModel = viewModel {
        val repositories = createRepositories()
        WardrobeViewModel(repositories.savedLooks, repositories.garments, repositories.photos)
    }
    val state = viewModel.state

    WadrovaTheme {
        CompositionLocalProvider(LocalPhotoLoader provides viewModel.photoLoader) {
        when (state.screen) {
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
                )
            }
        }
        }

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
