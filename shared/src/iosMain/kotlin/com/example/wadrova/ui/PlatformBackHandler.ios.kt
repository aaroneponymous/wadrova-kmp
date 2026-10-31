package com.example.wadrova.ui

import androidx.compose.runtime.Composable

/** iOS has no system back action; the builder's back button handles navigation. */
@Composable
actual fun PlatformBackHandler(onBack: () -> Unit) = Unit
