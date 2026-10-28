package dev.argus.automation.screen

import dev.argus.engine.model.Action
import dev.argus.engine.model.ScrollDirection
import dev.argus.engine.model.UiStep
import dev.argus.engine.runtime.ActionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** L'esecutore delle macro su un albero di nodi finto: trovato, non trovato, timeout, controllo, stop. */
class UiStepsRunnerTest {
    private val clock = FakeClock()

    private fun screen(
        tree: (Long) -> ScreenNode? = { null },
        active: () -> String? = { "com.example.app" },
        focus: () -> ScreenNode? = { null },
    ) = FakeScreen(time = clock.now, tree = tree, active = active, focus = focus)

    private fun runner(screen: FakeScreen, session: FakeSession = FakeSession()) =
        UiStepsRunner(screen, session, clock.now, clock.pause)

    private val noOpen: suspend (String) -> ActionResult = { ActionResult.Success }

    private fun macro(vararg steps: UiStep, pkg: String? = null) = Action.UiSteps(pkg, steps.toList())

    private fun failure(result: ActionResult) = (result as ActionResult.Failure).reason

    /** Una schermata con un pulsante cliccabile e una etichetta non cliccabile dentro un contenitore cliccabile. */
    private val send = FakeNode(text = "Invia", viewId = "com.example:id/send", isClickable = true)

    // --- clic ----------------------------------------------------------------------------------

    @Test
    fun `a click finds the element by text and taps it`() = runTest {
        val root = FakeNode(children = listOf(FakeNode(text = "Annulla", isClickable = true), send))
        val result = runner(screen({ root })).run(macro(UiStep.Click(text = "Invia")), noOpen)

        assertEquals(ActionResult.Success, result)
        assertEquals(listOf("Invia"), send.clicks)
    }

    @Test
    fun `a click finds the element by id, with or without the package prefix`() = runTest {
        val root = FakeNode(children = listOf(send))
        assertEquals(
            ActionResult.Success,
            runner(screen({ root })).run(macro(UiStep.Click(id = "send"), UiStep.Click(id = "com.example:id/send")), noOpen),
        )
        assertEquals(2, send.clicks.size)
    }

    @Test
    fun `a click finds the element by content description`() = runTest {
        val icon = FakeNode(contentDescription = "Condividi", isClickable = true)
        val root = FakeNode(children = listOf(icon))
        assertEquals(ActionResult.Success, runner(screen({ root })).run(macro(UiStep.Click(desc = "Condividi")), noOpen))
        assertEquals(1, icon.clicks.size)
    }

    @Test
    fun `an exact text beats a text that merely contains it`() = runTest {
        val all = FakeNode(text = "Invia a tutti", isClickable = true)
        val one = FakeNode(text = "Invia", isClickable = true)
        val root = FakeNode(children = listOf(all, one))
        runner(screen({ root })).run(macro(UiStep.Click(text = "invia")), noOpen)
        assertEquals(emptyList(), all.clicks)
        assertEquals(1, one.clicks.size)
    }

    @Test
    fun `a partial text still finds the element when nothing matches exactly`() = runTest {
        val root = FakeNode(children = listOf(send))
        assertEquals(ActionResult.Success, runner(screen({ root })).run(macro(UiStep.Click(text = "Inv")), noOpen))
        assertEquals(1, send.clicks.size)
    }

    @Test
    fun `the index picks the nth match in document order`() = runTest {
        val first = FakeNode(text = "Aggiungi", isClickable = true)
        val second = FakeNode(text = "Aggiungi", isClickable = true)
        val root = FakeNode(children = listOf(FakeNode(children = listOf(first)), second))
        runner(screen({ root })).run(macro(UiStep.Click(text = "Aggiungi", index = 1)), noOpen)
        assertEquals(emptyList(), first.clicks)
        assertEquals(1, second.clicks.size)
    }

    @Test
    fun `an index past the matches is not found`() = runTest {
        val root = FakeNode(children = listOf(send))
        val result = runner(screen({ root })).run(macro(UiStep.Click(text = "Invia", index = 3)), noOpen)
        assertEquals("ui_element_not_found", failure(result))
        assertEquals(emptyList(), send.clicks)
    }

    @Test
    fun `a label that is not clickable taps its clickable parent`() = runTest {
        val label = FakeNode(text = "Carrello")
        val button = FakeNode(isClickable = true, children = listOf(label))
        val root = FakeNode(children = listOf(button))
        assertEquals(ActionResult.Success, runner(screen({ root })).run(macro(UiStep.Click(text = "Carrello")), noOpen))
        assertEquals(1, button.clicks.size)
    }

    @Test
    fun `an element with nothing clickable around it fails instead of tapping anything`() = runTest {
        val label = FakeNode(text = "Titolo")
        val root = FakeNode(children = listOf(label))
        assertEquals(
            "ui_click_failed",
            failure(runner(screen({ root })).run(macro(UiStep.Click(text = "Titolo")), noOpen)),
        )
    }

    @Test
    fun `an element that is not on the screen stops the macro after the find timeout and taps nothing`() = runTest {
        val other = FakeNode(text = "Altro", isClickable = true)
        val root = FakeNode(children = listOf(other))
        val result = runner(screen({ root })).run(
            macro(UiStep.Click(text = "Invia"), UiStep.Click(text = "Altro")),
            noOpen,
        )
        assertEquals("ui_element_not_found", failure(result))
        assertEquals(emptyList(), other.clicks, "il passo dopo non deve partire")
        assertTrue(clock.time >= 3_000, "ha aspettato il tempo di ricerca: ${clock.time}")
    }

    @Test
    fun `an element that is off screen is not tapped`() = runTest {
        val hidden = FakeNode(text = "Invia", isClickable = true, isVisibleToUser = false)
        val root = FakeNode(children = listOf(hidden))
        assertEquals(
            "ui_element_not_found",
            failure(runner(screen({ root })).run(macro(UiStep.Click(text = "Invia")), noOpen)),
        )
        assertEquals(emptyList(), hidden.clicks)
    }

    @Test
    fun `an element that shows up while the macro looks for it is tapped`() = runTest {
        val late = FakeNode(text = "Invia", isClickable = true)
        val result = runner(screen({ now -> if (now >= 1_000) FakeNode(children = listOf(late)) else FakeNode() }))
            .run(macro(UiStep.Click(text = "Invia")), noOpen)
        assertEquals(ActionResult.Success, result)
        assertEquals(1, late.clicks.size)
    }

    @Test
    fun `a tap the system refuses and a node that throws both stop the macro with a closed code`() = runTest {
        val refuses = FakeNode(text = "Invia", isClickable = true, clickResult = false)
        assertEquals(
            "ui_click_failed",
            failure(runner(screen({ FakeNode(children = listOf(refuses)) })).run(macro(UiStep.Click(text = "Invia")), noOpen)),
        )
        val stale = FakeNode(text = "Invia", isClickable = true, clickThrows = true)
        assertEquals(
            "ui_action_failed",
            failure(runner(screen({ FakeNode(children = listOf(stale)) })).run(macro(UiStep.Click(text = "Invia")), noOpen)),
        )
    }

    // --- attesa e controllo --------------------------------------------------------------------

    @Test
    fun `wait_for returns as soon as the element appears`() = runTest {
        val tree: (Long) -> ScreenNode? = { now ->
            if (now >= 2_000) FakeNode(children = listOf(FakeNode(text = "Cerca"))) else FakeNode()
        }
        val result = runner(screen(tree)).run(macro(UiStep.WaitFor(text = "Cerca", timeoutMs = 5_000)), noOpen)
        assertEquals(ActionResult.Success, result)
        assertTrue(clock.time in 2_000..2_500, "tempo: ${clock.time}")
    }

    @Test
    fun `wait_for that never sees the element stops with a timeout at its own limit`() = runTest {
        val result = runner(screen({ FakeNode() })).run(
            macro(UiStep.WaitFor(text = "Cerca", timeoutMs = 4_000), UiStep.Back),
            noOpen,
        )
        assertEquals("ui_timeout", failure(result))
        assertTrue(clock.time in 4_000..4_500, "tempo: ${clock.time}")
    }

    // --- attesa dell'app (wait_for senza testo né id) ------------------------------------------

    private val appWait = UiStep.WaitFor(timeoutMs = 5_000)

    @Test
    fun `a wait with no target succeeds at once when the app is already in front with a readable window`() = runTest {
        val seven = FakeNode(text = "7", isClickable = true)
        val root = FakeNode(children = listOf(seven))
        val result = runner(screen({ root }, active = { "com.example.app" }))
            .run(macro(appWait, UiStep.Click(text = "7"), pkg = "com.example.app"), noOpen)

        assertEquals(ActionResult.Success, result)
        assertEquals(1, seven.clicks.size)
    }

    @Test
    fun `a wait with no target waits for the window to become readable and then goes on`() = runTest {
        val seven = FakeNode(text = "7", isClickable = true)
        val tree: (Long) -> ScreenNode? = { now -> if (now >= 1_000) FakeNode(children = listOf(seven)) else null }
        val result = runner(screen(tree, active = { "com.example.app" }))
            .run(macro(appWait, UiStep.Click(text = "7"), pkg = "com.example.app"), noOpen)

        assertEquals(ActionResult.Success, result)
        assertEquals(1, seven.clicks.size)
        assertTrue(clock.time >= 1_000, "ha aspettato la finestra: ${clock.time}")
    }

    @Test
    fun `a wait with no target that never sees the app in front stops with a timeout and taps nothing`() = runTest {
        val seven = FakeNode(text = "7", isClickable = true)
        val root = FakeNode(children = listOf(seven))
        // L'app compare davanti per l'apertura e poi un'altra app (non vietata) le passa sopra.
        val active = { if (clock.time < 100) "com.example.app" else "com.example.other" }
        val result = runner(screen({ root }, active = active))
            .run(macro(UiStep.WaitFor(timeoutMs = 1_000), UiStep.Click(text = "7"), pkg = "com.example.app"), noOpen)

        assertEquals("ui_timeout", failure(result))
        assertEquals(emptyList(), seven.clicks, "il passo dopo non deve partire")
        assertTrue(clock.time in 1_400..1_900, "tempo: ${clock.time}")
    }

    @Test
    fun `a wait with no target on a window that stays unreadable times out at its own limit`() = runTest {
        val result = runner(screen({ null }, active = { "com.example.app" }))
            .run(macro(UiStep.WaitFor(timeoutMs = 2_000), pkg = "com.example.app"), noOpen)

        assertEquals("ui_timeout", failure(result))
        assertTrue(clock.time in 2_400..2_900, "tempo: ${clock.time}")
    }

    @Test
    fun `a wait with no target never lets a forbidden screen pass`() = runTest {
        val result = runner(screen({ FakeNode() }, active = { if (clock.time < 100) "com.example.app" else "com.android.settings" }))
            .run(macro(appWait, pkg = "com.example.app"), noOpen)

        assertEquals("ui_forbidden_screen", failure(result))
    }

    @Test
    fun `a wait with no target and no app to open is refused before anything runs`() = runTest {
        val screen = screen({ FakeNode() })
        val result = runner(screen).run(macro(appWait, UiStep.Home), noOpen)

        assertEquals("resolved_action_invalid", failure(result))
        assertEquals(emptyList(), screen.globals)
    }

    @Test
    fun `wait_for also sees a label that the app exposes only as a content description`() = runTest {
        val root = FakeNode(children = listOf(FakeNode(contentDescription = "Cerca")))
        assertEquals(
            ActionResult.Success,
            runner(screen({ root })).run(macro(UiStep.WaitFor(text = "cerca", timeoutMs = 1_000)), noOpen),
        )
    }

    @Test
    fun `a check that holds lets the macro go on and one that fails stops it`() = runTest {
        val root = FakeNode(children = listOf(FakeNode(text = "Ordine confermato")))
        assertEquals(
            ActionResult.Success,
            runner(screen({ root })).run(
                macro(UiStep.Check("Ordine confermato", present = true), UiStep.Check("Errore", present = false)),
                noOpen,
            ),
        )
        assertEquals(
            "ui_check_failed",
            failure(runner(screen({ root })).run(macro(UiStep.Check("Errore", present = true), UiStep.Back), noOpen)),
        )
        assertEquals(
            "ui_check_failed",
            failure(runner(screen({ root })).run(macro(UiStep.Check("Ordine confermato", present = false)), noOpen)),
        )
    }

    @Test
    fun `a failed check never lets the next step run`() = runTest {
        val screen = screen({ FakeNode() })
        val result = runner(screen).run(macro(UiStep.Check("Fatto", present = true), UiStep.Home), noOpen)
        assertEquals("ui_check_failed", failure(result))
        assertEquals(emptyList(), screen.globals)
    }

    // --- scrivere, scorrere, indietro, home ----------------------------------------------------

    @Test
    fun `type writes into the focused editable field`() = runTest {
        val field = FakeNode(isEditable = true)
        assertEquals(
            ActionResult.Success,
            runner(screen(focus = { field })).run(macro(UiStep.Type("scarpe")), noOpen),
        )
        assertEquals(listOf("scarpe"), field.typed)
    }

    @Test
    fun `type without a focused field or on a field that refuses stops the macro`() = runTest {
        assertEquals(
            "ui_input_failed",
            failure(runner(screen(focus = { null })).run(macro(UiStep.Type("x")), noOpen)),
        )
        assertEquals(
            "ui_input_failed",
            failure(runner(screen(focus = { FakeNode(isEditable = false) })).run(macro(UiStep.Type("x")), noOpen)),
        )
        val refuses = FakeNode(isEditable = true, typeResult = false)
        assertEquals(
            "ui_input_failed",
            failure(runner(screen(focus = { refuses })).run(macro(UiStep.Type("x")), noOpen)),
        )
    }

    @Test
    fun `scroll moves the first scrollable container in the asked direction`() = runTest {
        val list = FakeNode(isScrollable = true)
        val root = FakeNode(children = listOf(FakeNode(text = "x"), list))
        runner(screen({ root })).run(
            macro(UiStep.Scroll(ScrollDirection.DOWN), UiStep.Scroll(ScrollDirection.UP)),
            noOpen,
        )
        assertEquals(listOf(true, false), list.scrolls)
    }

    @Test
    fun `scroll with nothing to scroll stops the macro`() = runTest {
        assertEquals(
            "ui_scroll_failed",
            failure(runner(screen({ FakeNode() })).run(macro(UiStep.Scroll(ScrollDirection.DOWN)), noOpen)),
        )
        val stuck = FakeNode(isScrollable = true, scrollResult = false)
        assertEquals(
            "ui_scroll_failed",
            failure(runner(screen({ FakeNode(children = listOf(stuck)) })).run(macro(UiStep.Scroll(ScrollDirection.UP)), noOpen)),
        )
    }

    @Test
    fun `back and home press the global keys and report a refusal`() = runTest {
        val ok = screen({ FakeNode() })
        assertEquals(ActionResult.Success, runner(ok).run(macro(UiStep.Back, UiStep.Home), noOpen))
        assertEquals(listOf("back", "home"), ok.globals)

        val refusing = FakeScreen(time = clock.now, tree = { FakeNode() }, backResult = false)
        assertEquals("ui_action_failed", failure(runner(refusing).run(macro(UiStep.Back), noOpen)))
    }

    // --- aprire l'app --------------------------------------------------------------------------

    @Test
    fun `the app is opened first and the steps start when it is in front`() = runTest {
        var front = "com.example.launcher"
        val opened = mutableListOf<String>()
        val button = FakeNode(text = "Invia", isClickable = true)
        val screen = screen({ FakeNode(children = listOf(button)) }, active = { front })
        val result = runner(screen).run(macro(UiStep.Click(text = "Invia"), pkg = "com.example.shop")) { pkg ->
            opened += pkg
            front = pkg
            ActionResult.Success
        }
        assertEquals(ActionResult.Success, result)
        assertEquals(listOf("com.example.shop"), opened)
        assertEquals(1, button.clicks.size)
    }

    @Test
    fun `starting from argus or settings does not stop a macro that opens another app`() = runTest {
        var front = "dev.argus"
        val button = FakeNode(text = "Invia", isClickable = true)
        val result = runner(screen({ FakeNode(children = listOf(button)) }, active = { front }))
            .run(macro(UiStep.Click(text = "Invia"), pkg = "com.example.shop")) { pkg ->
                front = pkg
                ActionResult.Success
            }
        assertEquals(ActionResult.Success, result)
    }

    @Test
    fun `an app that never comes to the front stops the macro`() = runTest {
        val button = FakeNode(text = "Invia", isClickable = true)
        val result = runner(screen({ FakeNode(children = listOf(button)) }, active = { "com.example.launcher" }))
            .run(macro(UiStep.Click(text = "Invia"), pkg = "com.example.shop"), noOpen)
        assertEquals("ui_open_failed", failure(result))
        assertEquals(emptyList(), button.clicks)
    }

    @Test
    fun `an opening that fails or is deferred to a notification stops the macro with that code`() = runTest {
        val result = runner(screen({ FakeNode() })).run(macro(UiStep.Back, pkg = "com.example.shop")) {
            ActionResult.Failure("deferred_to_notification")
        }
        assertEquals("deferred_to_notification", failure(result))
    }

    // --- confini -------------------------------------------------------------------------------

    @Test
    fun `a forbidden screen in front stops the macro before it touches anything`() = runTest {
        val button = FakeNode(text = "Consenti", isClickable = true)
        listOf(
            "dev.argus",
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.permissioncontroller",
        ).forEach { forbidden ->
            val result = runner(screen({ FakeNode(children = listOf(button)) }, active = { forbidden }))
                .run(macro(UiStep.Click(text = "Consenti")), noOpen)
            assertEquals("ui_forbidden_screen", failure(result), forbidden)
        }
        assertEquals(emptyList(), button.clicks)
    }

    @Test
    fun `a permission dialog that pops up mid macro stops the next step`() = runTest {
        val allow = FakeNode(text = "Consenti", isClickable = true)
        val shoot = FakeNode(text = "Scatta foto", isClickable = true)
        val tree = FakeNode(children = listOf(shoot, allow))
        // Dopo il primo tocco davanti c'è il dialogo dei permessi di sistema.
        val screen = screen(
            { tree },
            active = { if (shoot.clicks.isNotEmpty()) "com.google.android.permissioncontroller" else "com.example.app" },
        )
        val result = runner(screen).run(
            macro(UiStep.Click(text = "Scatta foto"), UiStep.Click(text = "Consenti")),
            noOpen,
        )
        assertEquals("ui_forbidden_screen", failure(result))
        assertEquals(1, shoot.clicks.size)
        assertEquals(emptyList(), allow.clicks)
    }

    @Test
    fun `a macro that runs longer than sixty seconds stops`() = runTest {
        val tree: (Long) -> ScreenNode? = { now ->
            FakeNode(children = (1..5).filter { now >= 14_000L * it }.map { FakeNode(text = "S$it") })
        }
        val result = runner(screen(tree)).run(
            macro(
                UiStep.WaitFor(text = "S1", timeoutMs = 15_000),
                UiStep.WaitFor(text = "S2", timeoutMs = 15_000),
                UiStep.WaitFor(text = "S3", timeoutMs = 15_000),
                UiStep.WaitFor(text = "S4", timeoutMs = 15_000),
                UiStep.WaitFor(text = "S5", timeoutMs = 15_000),
            ),
            noOpen,
        )
        assertEquals("ui_macro_timeout", failure(result))
        assertTrue(clock.time in 60_000..61_000, "tempo: ${clock.time}")
    }

    @Test
    fun `the stop button of the notification ends the macro and the next step never runs`() = runTest {
        val first = FakeNode(text = "Uno", isClickable = true)
        val second = FakeNode(text = "Due", isClickable = true)
        val root = FakeNode(children = listOf(first, second))
        val session = FakeSession(stopWhen = { first.clicks.isNotEmpty() })
        val result = runner(screen({ root }), session)
            .run(macro(UiStep.Click(text = "Uno"), UiStep.Click(text = "Due")), noOpen)
        assertEquals("ui_stopped", failure(result))
        assertEquals(1, first.clicks.size)
        assertEquals(emptyList(), second.clicks)
        assertEquals(1, session.ends)
    }

    @Test
    fun `a stop asked before the macro starts is honored at the first step`() = runTest {
        val button = FakeNode(text = "Uno", isClickable = true)
        val result = runner(screen({ FakeNode(children = listOf(button)) }), FakeSession(stopWhen = { true }))
            .run(macro(UiStep.Click(text = "Uno")), noOpen)
        assertEquals("ui_stopped", failure(result))
        assertEquals(emptyList(), button.clicks)
    }

    // --- servizio, notifica, una sola macro ----------------------------------------------------

    @Test
    fun `without the accessibility service nothing runs`() = runTest {
        val screen = FakeScreen(time = clock.now, available = false, tree = { FakeNode() })
        val session = FakeSession()
        assertEquals("ui_service_unavailable", failure(runner(screen, session).run(macro(UiStep.Back), noOpen)))
        assertEquals(0, session.begins)
    }

    @Test
    fun `without the notification the macro does not start`() = runTest {
        val screen = screen({ FakeNode() })
        val session = FakeSession(canShow = false)
        val result = runner(screen, session).run(macro(UiStep.Back), noOpen)
        assertEquals("ui_notification_unavailable", failure(result))
        assertEquals(emptyList(), screen.globals)
        assertEquals(0, session.ends)
    }

    @Test
    fun `the notification is shown once and hidden once whatever the outcome`() = runTest {
        val session = FakeSession()
        runner(screen({ FakeNode() }), session).run(macro(UiStep.Back), noOpen)
        runner(screen({ FakeNode() }), session).run(macro(UiStep.Click(text = "assente")), noOpen)
        assertEquals(2, session.begins)
        assertEquals(2, session.ends)
    }

    @Test
    fun `a second macro while one is running is refused as busy`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val screen = screen({ FakeNode() })
        val shared = UiStepsRunner(screen, FakeSession(), clock.now) { gate.await() }
        val first = launch { shared.run(macro(UiStep.Back), noOpen) }
        testScheduler.runCurrent()

        assertEquals("ui_busy", failure(shared.run(macro(UiStep.Home), noOpen)))
        gate.complete(Unit)
        first.join()
        assertEquals(ActionResult.Success, shared.run(macro(UiStep.Home), noOpen))
    }

    @Test
    fun `a macro the policy forbids never touches the screen`() = runTest {
        val screen = screen({ FakeNode() })
        val session = FakeSession()
        val forbidden = runner(screen, session).run(macro(UiStep.Back, pkg = "com.android.settings"), noOpen)
        assertEquals("resolved_action_invalid", failure(forbidden))
        val empty = runner(screen, session).run(Action.UiSteps(null, emptyList()), noOpen)
        assertEquals("resolved_action_invalid", failure(empty))
        assertEquals(0, session.begins)
        assertEquals(emptyList(), screen.globals)
    }
}
