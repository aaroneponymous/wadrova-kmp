package com.example.wadrova

import androidx.compose.ui.window.ComposeUIViewController
import com.example.wadrova.data.createLocalRepositories

fun MainViewController() = ComposeUIViewController { App { createLocalRepositories() } }