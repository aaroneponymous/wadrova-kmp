package com.example.wadrova

import androidx.compose.ui.window.ComposeUIViewController
import com.example.wadrova.data.createSavedLookRepository

fun MainViewController() = ComposeUIViewController { App { createSavedLookRepository() } }