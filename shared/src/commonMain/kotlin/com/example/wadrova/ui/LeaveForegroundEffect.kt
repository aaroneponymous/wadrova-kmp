package com.example.wadrova.ui

import androidx.compose.runtime.Composable

/** Calls [onLeave] when the app goes to the background, but not when Android recreates the screen for a rotation. */
@Composable
expect fun LeaveForegroundEffect(onLeave: () -> Unit)
