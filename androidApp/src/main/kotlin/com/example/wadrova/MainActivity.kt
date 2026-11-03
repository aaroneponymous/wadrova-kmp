package com.example.wadrova

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.wadrova.data.createLocalRepositories

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is light-only for now, so keep dark system bar icons in system dark mode.
        val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)

        // `adb shell am start -n com.example.wadrova/.MainActivity --ez replayIntro true` replays the brand splash.
        val replayIntro = intent.getBooleanExtra("replayIntro", false)
        setContent {
            App(replayIntro) { createLocalRepositories(applicationContext) }
        }
    }
}
