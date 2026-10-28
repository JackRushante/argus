package dev.argus.automation.triggers

import dev.argus.device.ForegroundFromUsage
import dev.argus.device.UsageActivityEvent
import dev.argus.engine.model.AppEventKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Da eventi di attività di Android a «app aperta» / «app chiusa», senza eventi doppi (0.5.1, F15). */
class AppTransitionTrackerTest {
    private val youtube = "com.google.android.youtube"
    private val chrome = "com.android.chrome"
    private val launcher = "com.android.launcher"

    private fun resumed(pkg: String, at: Long, cls: String = "Main") =
        UsageActivityEvent(pkg, cls, ForegroundFromUsage.RESUMED, at)

    private fun paused(pkg: String, at: Long, cls: String = "Main") =
        UsageActivityEvent(pkg, cls, ForegroundFromUsage.PAUSED, at)

    private fun stopped(pkg: String, at: Long, cls: String = "Main") =
        UsageActivityEvent(pkg, cls, ForegroundFromUsage.STOPPED, at)

    private fun opened(pkg: String, at: Long) = AppTransition(pkg, AppEventKind.OPENED, at)
    private fun closed(pkg: String, at: Long) = AppTransition(pkg, AppEventKind.CLOSED, at)

    private val grace = AppTransitionTracker.DEFAULT_GRACE_MILLIS

    @Test
    fun `an app that comes to the foreground is opened once`() {
        val tracker = AppTransitionTracker()
        assertEquals(listOf(opened(youtube, 1_000)), tracker.apply(listOf(resumed(youtube, 1_000)), nowMillis = 1_100))
        // Lo stesso evento riletto: niente di nuovo.
        assertEquals(emptyList(), tracker.apply(listOf(resumed(youtube, 1_000)), nowMillis = 3_000))
    }

    @Test
    fun `going to the home screen closes the app and opens the launcher`() {
        val tracker = AppTransitionTracker()
        tracker.apply(listOf(resumed(youtube, 1_000)), 1_100)

        val out = tracker.apply(listOf(paused(youtube, 5_000), resumed(launcher, 5_040)), 5_100)

        // La chiusura porta l'istante della pausa e arriva prima dell'apertura che l'ha causata.
        assertEquals(listOf(closed(youtube, 5_000), opened(launcher, 5_040)), out)
    }

    @Test
    fun `switching to another app closes the first and opens the second`() {
        val tracker = AppTransitionTracker()
        tracker.apply(listOf(resumed(youtube, 1_000)), 1_100)

        val out = tracker.apply(listOf(paused(youtube, 4_000), resumed(chrome, 4_030)), 4_100)

        assertEquals(listOf(closed(youtube, 4_000), opened(chrome, 4_030)), out)
    }

    @Test
    fun `moving between two screens of the same app is neither a close nor an open`() {
        val tracker = AppTransitionTracker()
        tracker.apply(listOf(resumed(youtube, 1_000, "Home")), 1_100)

        val out = tracker.apply(
            listOf(paused(youtube, 3_000, "Home"), resumed(youtube, 3_080, "Watch"), stopped(youtube, 3_400, "Home")),
            nowMillis = 5_000,
        )

        assertEquals(emptyList(), out)
        // E l'app resta aperta: lasciarla ora la chiude una volta sola.
        assertEquals(
            listOf(closed(youtube, 6_000), opened(launcher, 6_020)),
            tracker.apply(listOf(paused(youtube, 6_000, "Watch"), resumed(launcher, 6_020)), 6_100),
        )
    }

    @Test
    fun `a pause without a following resume becomes a close after the grace time`() {
        val tracker = AppTransitionTracker()
        tracker.apply(listOf(resumed(youtube, 1_000)), 1_100)

        // Appena in pausa: potrebbe essere un cambio di schermata, si aspetta.
        assertEquals(emptyList(), tracker.apply(listOf(paused(youtube, 5_000)), 5_100))
        assertEquals(emptyList(), tracker.apply(emptyList(), 5_000 + grace - 1))
        // Matura: la chiusura porta l'istante della pausa, non quello della scoperta.
        assertEquals(listOf(closed(youtube, 5_000)), tracker.apply(emptyList(), 5_000 + grace))
        // Una sola volta.
        assertEquals(emptyList(), tracker.apply(emptyList(), 60_000))
    }

    @Test
    fun `a return after the grace time is a close followed by a new open`() {
        val tracker = AppTransitionTracker()
        tracker.apply(listOf(resumed(youtube, 1_000)), 1_100)
        tracker.apply(listOf(paused(youtube, 5_000)), 5_100)

        // Schermo spento e riacceso: le due letture sono separate da un lungo silenzio.
        val out = tracker.apply(listOf(resumed(youtube, 600_000)), 600_100)

        assertEquals(listOf(closed(youtube, 5_000), opened(youtube, 600_000)), out)
    }

    @Test
    fun `a quick return inside the grace time cancels the close`() {
        val tracker = AppTransitionTracker()
        tracker.apply(listOf(resumed(youtube, 1_000)), 1_100)
        tracker.apply(listOf(paused(youtube, 5_000)), 5_100)

        assertEquals(emptyList(), tracker.apply(listOf(resumed(youtube, 5_000 + grace)), 5_000 + grace + 10))
        assertEquals(emptyList(), tracker.apply(emptyList(), 60_000))
    }

    @Test
    fun `two apps in split screen each have their own transitions`() {
        val tracker = AppTransitionTracker()
        val out = tracker.apply(listOf(resumed(youtube, 1_000), resumed(chrome, 1_020)), 1_100)
        assertEquals(listOf(opened(youtube, 1_000), opened(chrome, 1_020)), out)

        // Chiudere solo una delle due non tocca l'altra.
        assertEquals(emptyList(), tracker.apply(listOf(paused(chrome, 4_000)), 4_100))
        assertEquals(listOf(closed(chrome, 4_000)), tracker.apply(emptyList(), 4_000 + grace))
        assertTrue(tracker.hasForeground())
    }

    @Test
    fun `duplicate and orphan events change nothing`() {
        val tracker = AppTransitionTracker()
        // Pausa e stop di un'attività che non risulta ripresa: resti di una finestra di lettura.
        assertEquals(emptyList(), tracker.apply(listOf(paused(youtube, 1_000), stopped(youtube, 1_050)), 2_000))
        assertFalse(tracker.hasForeground())

        assertEquals(listOf(opened(youtube, 3_000)), tracker.apply(listOf(resumed(youtube, 3_000)), 3_100))
        // La stessa ripresa due volte, e pausa+stop dello stesso passaggio: una sola chiusura.
        assertEquals(emptyList(), tracker.apply(listOf(resumed(youtube, 3_000)), 3_200))
        tracker.apply(listOf(paused(youtube, 8_000), stopped(youtube, 8_300)), 8_400)
        assertEquals(listOf(closed(youtube, 8_000)), tracker.apply(emptyList(), 8_000 + grace))
    }

    @Test
    fun `a stop alone closes the app like a pause`() {
        val tracker = AppTransitionTracker()
        tracker.apply(listOf(resumed(youtube, 1_000)), 1_100)
        tracker.apply(listOf(stopped(youtube, 4_000)), 4_100)
        assertEquals(listOf(closed(youtube, 4_000)), tracker.apply(emptyList(), 4_000 + grace))
    }

    @Test
    fun `events are applied in time order whatever the order they arrive in`() {
        val tracker = AppTransitionTracker()
        val out = tracker.apply(
            listOf(resumed(chrome, 4_030), paused(youtube, 4_000), resumed(youtube, 1_000)),
            nowMillis = 4_100,
        )
        assertEquals(listOf(opened(youtube, 1_000), closed(youtube, 4_000), opened(chrome, 4_030)), out)
    }

    @Test
    fun `reset forgets what was open`() {
        val tracker = AppTransitionTracker()
        tracker.apply(listOf(resumed(youtube, 1_000)), 1_100)
        assertTrue(tracker.hasForeground())
        tracker.reset()
        assertFalse(tracker.hasForeground())
        // Dopo il reset la pausa di un'app non più nota è un orfano: nessuna chiusura inventata.
        assertEquals(emptyList(), tracker.apply(listOf(paused(youtube, 5_000)), 9_000))
    }
}
