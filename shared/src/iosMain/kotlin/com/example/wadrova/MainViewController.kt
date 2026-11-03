package com.example.wadrova

import androidx.compose.ui.window.ComposeUIViewController
import com.example.wadrova.data.createLocalRepositories
import platform.Foundation.NSUserDefaults

/** The launch argument `-WadrovaReplayIntro YES` (Xcode scheme, Run, Arguments) replays the brand splash. */
fun MainViewController() = ComposeUIViewController {
    App(NSUserDefaults.standardUserDefaults.boolForKey("WadrovaReplayIntro")) { createLocalRepositories() }
}
