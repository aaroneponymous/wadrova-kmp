package com.example.wadrova.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

/** A configuration change also stops the activity, so it's told apart with [android.app.Activity.isChangingConfigurations]. */
@Composable
actual fun LeaveForegroundEffect(onLeave: () -> Unit) {
    val activity = LocalActivity.current
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (activity?.isChangingConfigurations != true) onLeave()
    }
}
