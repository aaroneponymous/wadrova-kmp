package com.example.wadrova.ui

import androidx.compose.runtime.Composable

/** Whether the user asked the system to reduce motion, read once when the app starts. */
@Composable
expect fun rememberReducedMotion(): Boolean
