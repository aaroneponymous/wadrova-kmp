package com.example.wadrova.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wadrova.data.BrandIntroStore
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import wadrova.shared.generated.resources.Res

enum class SplashPhase { CHECKING, PLAYING, DONE }

/**
 * The full intro plays once per install, or again when a developer asks for it, and never with
 * reduced motion on. A reduced-motion launch isn't recorded, so the intro stays eligible.
 */
fun introEligible(seen: Boolean, replayRequested: Boolean, reducedMotion: Boolean): Boolean =
    !reducedMotion && (replayRequested || !seen)

/**
 * One run of the brand splash per process. It lives in a ViewModel so a configuration change resumes
 * the animation from where it was instead of restarting it.
 */
class BrandSplashViewModel(
    private val store: BrandIntroStore,
    replayRequested: Boolean,
    reducedMotion: Boolean,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ViewModel() {
    var phase by mutableStateOf(SplashPhase.CHECKING)
        private set

    /** Whether the intro started, so its last frame can stay on screen while the splash fades out. */
    var played = false
        private set
    private var startedAt: TimeMark? = null

    init {
        viewModelScope.launch {
            // If the flag can't be read, don't risk holding the user on the intro.
            val seen = runCatching { store.hasSeenIntro() }.getOrDefault(true)
            if (phase == SplashPhase.CHECKING) {
                played = introEligible(seen, replayRequested, reducedMotion)
                phase = if (played) SplashPhase.PLAYING else SplashPhase.DONE
            }
        }
    }

    /** Time since the animation's first frame; the first call starts the clock. */
    fun elapsed(): Duration = (startedAt ?: timeSource.markNow().also { startedAt = it }).elapsedNow()

    /** Ends the intro once: completed, skipped, Back, or the app leaving the foreground. Only a played intro is recorded. */
    fun finish() {
        val wasPlaying = phase == SplashPhase.PLAYING
        phase = SplashPhase.DONE
        if (wasPlaying) viewModelScope.launch { runCatching { store.markIntroSeen() } }
    }

    /** The animation couldn't load: leave without recording it, so it can play on a later launch. */
    fun abandon() {
        phase = SplashPhase.DONE
    }
}

/** The splash files' own background layers, so the area around the 9:16 canvas matches it. */
private val SplashIvory = Color(0xFFF5F4F0)
private val SplashGraphite = Color(0xFF232529)

/** If the animation hasn't loaded by then, go straight to the app rather than show an empty screen. */
private val LOAD_TIMEOUT = 2.seconds

private fun splashFile(dark: Boolean) = if (dark) "files/wadrova-splash-dark.json" else "files/wadrova-splash-light.json"

/**
 * Covers the app until [BrandSplashViewModel] decides, then plays the official splash Lottie or
 * fades away. It's an overlay, not a destination: the app underneath starts as it always does, and
 * Back never returns here.
 */
@Composable
fun BrandSplash(splash: BrandSplashViewModel, dark: Boolean = false) {
    val phase = splash.phase
    AnimatedVisibility(visible = phase != SplashPhase.DONE, enter = EnterTransition.None, exit = fadeOut(tween(220))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(if (dark) SplashGraphite else SplashIvory)
                // Keeps touches off the app underneath; dropped as soon as the fade-out starts.
                .then(if (phase != SplashPhase.DONE) Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } } else Modifier),
        ) {
            if (splash.played) SplashAnimation(splash, dark)
            if (phase == SplashPhase.PLAYING) {
                PlatformBackHandler(splash::finish)
                LeaveForegroundEffect(splash::finish)
                TextButton(
                    onClick = splash::finish,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(8.dp),
                ) {
                    Text(
                        "Skip",
                        style = MaterialTheme.typography.labelLarge,
                        color = (if (dark) SplashIvory else SplashGraphite).copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/**
 * Plays the composition once at its own keyframe timing, measured on the ViewModel's clock, and
 * finishes on the last frame. After a skip the current frame holds while the splash fades out.
 */
@Composable
private fun SplashAnimation(splash: BrandSplashViewModel, dark: Boolean) {
    val result = rememberLottieComposition(dark) {
        LottieCompositionSpec.JsonString(Res.readBytes(splashFile(dark)).decodeToString())
    }
    val composition = result.value
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(result.isFailure) {
        if (result.isFailure) splash.abandon()
    }
    LaunchedEffect(Unit) {
        delay(LOAD_TIMEOUT)
        if (result.value == null) splash.abandon()
    }
    LaunchedEffect(composition) {
        if (composition == null) return@LaunchedEffect
        while (splash.phase == SplashPhase.PLAYING) {
            withFrameNanos { }
            progress = (splash.elapsed() / composition.duration).toFloat().coerceAtMost(1f)
            if (progress >= 1f) splash.finish()
        }
    }

    if (composition != null) {
        Image(
            rememberLottiePainter(composition, progress = { progress }, enableExpressions = false),
            contentDescription = "Wadrova",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}
