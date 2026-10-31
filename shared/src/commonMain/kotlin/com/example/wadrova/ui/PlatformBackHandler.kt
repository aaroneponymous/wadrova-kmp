package com.example.wadrova.ui

import androidx.compose.runtime.Composable

/** Handles the platform's system back action, where it has one. */
@Composable
expect fun PlatformBackHandler(onBack: () -> Unit)
