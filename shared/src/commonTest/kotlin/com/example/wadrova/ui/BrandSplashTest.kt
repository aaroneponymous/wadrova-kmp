package com.example.wadrova.ui

import com.example.wadrova.data.BrandIntroStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

private class FakeIntroStore(var seen: Boolean = false, val failRead: Boolean = false) : BrandIntroStore {
    var writes = 0

    override suspend fun hasSeenIntro(): Boolean {
        if (failRead) error("database unavailable")
        return seen
    }

    override suspend fun markIntroSeen() {
        writes++
        seen = true
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BrandSplashTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun splash(store: BrandIntroStore, replay: Boolean = false, reducedMotion: Boolean = false, time: TestTimeSource = TestTimeSource()) =
        BrandSplashViewModel(store, replay, reducedMotion, time)

    @Test
    fun eligibilityPlaysOnceUnlessReplayedAndNeverWithReducedMotion() {
        assertTrue(introEligible(seen = false, replayRequested = false, reducedMotion = false))
        assertFalse(introEligible(seen = true, replayRequested = false, reducedMotion = false))
        assertTrue(introEligible(seen = true, replayRequested = true, reducedMotion = false))
        assertFalse(introEligible(seen = false, replayRequested = false, reducedMotion = true))
        assertFalse(introEligible(seen = true, replayRequested = true, reducedMotion = true))
    }

    @Test
    fun firstLaunchPlaysTheIntro() {
        val splash = splash(FakeIntroStore(seen = false))
        assertEquals(SplashPhase.PLAYING, splash.phase)
        assertTrue(splash.played)
    }

    @Test
    fun laterLaunchesGoStraightToTheApp() {
        val store = FakeIntroStore(seen = true)
        val splash = splash(store)
        assertEquals(SplashPhase.DONE, splash.phase)
        assertFalse(splash.played)
        assertEquals(0, store.writes)
    }

    @Test
    fun coversTheAppUntilTheFlagIsRead() {
        val store = FakeIntroStore(seen = true)
        val pending = CompletableDeferred<Unit>()
        val slowStore = object : BrandIntroStore by store {
            override suspend fun hasSeenIntro(): Boolean {
                pending.await()
                return store.hasSeenIntro()
            }
        }
        val splash = splash(slowStore)
        assertEquals(SplashPhase.CHECKING, splash.phase)
        pending.complete(Unit)
        assertEquals(SplashPhase.DONE, splash.phase)
    }

    @Test
    fun completionRecordsTheIntroAndLeavesTheSplash() {
        val store = FakeIntroStore()
        val splash = splash(store)
        splash.finish()
        assertEquals(SplashPhase.DONE, splash.phase)
        assertEquals(1, store.writes)
        assertTrue(splash.played, "the last frame stays while the splash fades out")
    }

    @Test
    fun skipEndsTheIntroImmediatelyAndRecordsIt() {
        val time = TestTimeSource()
        val store = FakeIntroStore()
        val splash = splash(store, time = time)
        splash.elapsed()
        time += 800.milliseconds
        splash.finish()
        assertEquals(SplashPhase.DONE, splash.phase)
        assertEquals(1, store.writes)
    }

    @Test
    fun goingToTheBackgroundEndsTheIntroSoReturningDoesNotReplayIt() {
        val store = FakeIntroStore()
        val splash = splash(store)
        splash.finish() // ON_STOP while playing
        assertEquals(SplashPhase.DONE, splash.phase)
        assertTrue(store.seen)
        // A new process on the next cold launch reads the recorded flag.
        assertEquals(SplashPhase.DONE, splash(store).phase)
    }

    @Test
    fun repeatedEndsRecordOnce() {
        val store = FakeIntroStore()
        val splash = splash(store)
        splash.finish() // last frame
        splash.finish() // a Skip tap in the same frame
        splash.finish() // ON_STOP during the fade
        splash.abandon()
        assertEquals(SplashPhase.DONE, splash.phase)
        assertEquals(1, store.writes)
    }

    @Test
    fun reducedMotionSkipsTheIntroWithoutRecordingIt() {
        val store = FakeIntroStore(seen = false)
        val splash = splash(store, reducedMotion = true)
        assertEquals(SplashPhase.DONE, splash.phase)
        assertFalse(splash.played)
        assertEquals(0, store.writes)
    }

    @Test
    fun replayFlagPlaysASeenIntroAgain() {
        val splash = splash(FakeIntroStore(seen = true), replay = true)
        assertEquals(SplashPhase.PLAYING, splash.phase)
    }

    @Test
    fun aFailedLoadLeavesWithoutRecording() {
        val store = FakeIntroStore()
        val splash = splash(store)
        splash.abandon()
        assertEquals(SplashPhase.DONE, splash.phase)
        assertEquals(0, store.writes)
    }

    @Test
    fun anUnreadableFlagDoesNotHoldTheUserOnTheIntro() {
        val splash = splash(FakeIntroStore(failRead = true))
        assertEquals(SplashPhase.DONE, splash.phase)
    }

    @Test
    fun theClockStartsOnTheFirstFrameAndSurvivesRecomposition() {
        val time = TestTimeSource()
        val splash = splash(FakeIntroStore(), time = time)
        time += 5.seconds // loading time before the first frame doesn't count
        assertEquals(0.seconds, splash.elapsed())
        time += 1.seconds
        // A configuration change recreates the composable but keeps this ViewModel, so time carries on.
        assertEquals(1.seconds, splash.elapsed())
    }

    @Test
    fun theSplashDoesNotChangeTheStartTab() {
        splash(FakeIntroStore()).finish()
        assertEquals(PrimaryTab.MIRROR, START_TAB)
    }
}
