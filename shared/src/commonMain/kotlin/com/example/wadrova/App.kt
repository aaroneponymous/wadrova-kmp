package com.example.wadrova

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wadrova.ui.OutfitBuilderScreen
import com.example.wadrova.ui.PlatformBackHandler
import com.example.wadrova.ui.Screen
import com.example.wadrova.ui.WadrovaTheme
import com.example.wadrova.ui.WardrobeScreen
import com.example.wadrova.ui.WardrobeViewModel

@Composable
fun App() {
    val viewModel = viewModel { WardrobeViewModel() }
    val state = viewModel.state

    WadrovaTheme {
        when (state.screen) {
            Screen.WARDROBE -> WardrobeScreen(state, viewModel::update)
            Screen.BUILDER -> {
                PlatformBackHandler { viewModel.update { it.openWardrobe() } }
                OutfitBuilderScreen(state, viewModel::update)
            }
        }
    }
}
