package com.example.wadrova.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** Rotation doesn't stop the view controller on iOS, so every stop means the app left the foreground. */
@Composable
actual fun LeaveForegroundEffect(onLeave: () -> Unit) = LifecycleEventEffect(Lifecycle.Event.ON_STOP) { onLeave() }
