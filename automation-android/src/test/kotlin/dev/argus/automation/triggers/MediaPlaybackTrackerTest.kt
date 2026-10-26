package dev.argus.automation.triggers

import dev.argus.engine.model.MediaPlayState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class MediaPlaybackTrackerTest {
    private val emitted = mutableListOf<MediaPlayState>()

    private fun TestScope.tracker(initiallyPlaying: Boolean = false) =
        MediaPlaybackTracker(this, initiallyPlaying) { emitted += it }

    @Test
    fun `playback that was already running at registration is the base and fires nothing`() = runTest {
        val tracker = tracker(initiallyPlaying = true)
        tracker.onPlayback(true)
        runCurrent()
        assertEquals(emptyList(), emitted)
    }

    @Test
    fun `start is reported at once and the end only after two quiet seconds`() = runTest {
        val tracker = tracker()
        tracker.onPlayback(true)
        runCurrent()
        assertEquals(listOf(MediaPlayState.PLAYING), emitted)

        tracker.onPlayback(false)
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(listOf(MediaPlayState.PLAYING), emitted)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(MediaPlayState.PLAYING, MediaPlayState.STOPPED), emitted)
    }

    @Test
    fun `the gap between two tracks fires nothing`() = runTest {
        val tracker = tracker()
        tracker.onPlayback(true)
        runCurrent()

        // Brano finito, il successivo parte dopo 600 ms.
        tracker.onPlayback(false)
        advanceTimeBy(600)
        tracker.onPlayback(true)
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(listOf(MediaPlayState.PLAYING), emitted)
    }

    @Test
    fun `a new playback after the quiet period is a new start`() = runTest {
        val tracker = tracker()
        tracker.onPlayback(true)
        tracker.onPlayback(false)
        advanceTimeBy(2_500)
        tracker.onPlayback(true)
        runCurrent()

        assertEquals(
            listOf(MediaPlayState.PLAYING, MediaPlayState.STOPPED, MediaPlayState.PLAYING),
            emitted,
        )
    }

    @Test
    fun `repeated inactive callbacks keep a single pending end`() = runTest {
        val tracker = tracker()
        tracker.onPlayback(true)
        tracker.onPlayback(false)
        advanceTimeBy(1_000)
        tracker.onPlayback(false)
        advanceTimeBy(1_100)
        runCurrent()

        assertEquals(listOf(MediaPlayState.PLAYING, MediaPlayState.STOPPED), emitted)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, emitted.size)
    }

    @Test
    fun `closing the tracker cancels a pending end`() = runTest {
        val tracker = tracker()
        tracker.onPlayback(true)
        tracker.onPlayback(false)
        tracker.close()
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(listOf(MediaPlayState.PLAYING), emitted)
    }
}
