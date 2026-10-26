package dev.argus.nav

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B33: dopo due rifiuti Android non mostra più il dialogo e «Concedi» non farebbe niente. Ma
 * `shouldShowRequestPermissionRationale` è false anche prima della prima richiesta e dopo un dialogo
 * chiuso toccando fuori: il «negato per sempre» si deduce da uno stato per permesso
 * (NONE / DENIED_ONCE / PERMANENT) più il tempo che la richiesta ha impiegato a tornare.
 */
class PermissionRequestCoordinatorTest {
    // --- decisioni pure -----------------------------------------------------------------------------

    @Test
    fun `a denial with a rationale left is the first explicit denial`() {
        assertEquals(
            DenialOutcome(PermissionState.DENIED_ONCE),
            denialOutcome(PermissionState.NONE, shouldShowRationale = true, elapsedMillis = 5_000),
        )
    }

    @Test
    fun `a denial with a rationale left stays denied once even when it returns at once`() {
        assertEquals(
            DenialOutcome(PermissionState.DENIED_ONCE),
            denialOutcome(PermissionState.NONE, shouldShowRationale = true, elapsedMillis = 10),
        )
    }

    @Test
    fun `a denial that returns at once without a rationale is permanent and opens the settings`() {
        assertEquals(
            DenialOutcome(PermissionState.PERMANENT, message = true, openSettings = true),
            denialOutcome(PermissionState.NONE, shouldShowRationale = false, elapsedMillis = INSTANT_DENIAL_MILLIS),
        )
    }

    @Test
    fun `a slow denial without a rationale after an explicit denial is permanent with the message only`() {
        assertEquals(
            DenialOutcome(PermissionState.PERMANENT, message = true),
            denialOutcome(
                PermissionState.DENIED_ONCE,
                shouldShowRationale = false,
                elapsedMillis = INSTANT_DENIAL_MILLIS + 1,
            ),
        )
    }

    @Test
    fun `a slow denial without a rationale and no prior denial changes nothing`() {
        // Prima richiesta chiusa toccando fuori dal dialogo: Android non la conta come rifiuto.
        assertEquals(
            DenialOutcome(PermissionState.NONE),
            denialOutcome(PermissionState.NONE, shouldShowRationale = false, elapsedMillis = 4_000),
        )
    }

    @Test
    fun `a denial with an unknown elapsed time is never treated as instant`() {
        assertEquals(
            DenialOutcome(PermissionState.NONE),
            denialOutcome(PermissionState.NONE, shouldShowRationale = false, elapsedMillis = null),
        )
    }

    @Test
    fun `the tap opens the settings only for a permanent permission without a rationale`() {
        assertTrue(tapOpensSettings(PermissionState.PERMANENT, shouldShowRationale = false))
        assertFalse(tapOpensSettings(PermissionState.PERMANENT, shouldShowRationale = true))
        assertFalse(tapOpensSettings(PermissionState.DENIED_ONCE, shouldShowRationale = false))
        assertFalse(tapOpensSettings(PermissionState.NONE, shouldShowRationale = false))
    }

    // --- flusso completo con un sistema e un orologio finti ------------------------------------------

    private class FakeSystem {
        var now = 1_000_000L
        val granted = mutableSetOf<String>()
        val rationale = mutableSetOf<String>()
        val states = mutableMapOf<String, PermissionState>()
        val launched = mutableListOf<List<String>>()
        var settingsOpened = 0
        var messages = 0

        val coordinator = PermissionRequestCoordinator(
            store = object : PermissionStateStore {
                override fun state(permission: String) = states[permission] ?: PermissionState.NONE
                override fun setState(permission: String, state: PermissionState) {
                    states[permission] = state
                }
            },
            isGranted = { it in granted },
            shouldShowRationale = { it in rationale },
            launch = { launched += it.toList() },
            openAppSettings = { settingsOpened += 1 },
            notifySettingsNeeded = { messages += 1 },
            nowMillis = { now },
        )

        fun state(permission: String) = states[permission] ?: PermissionState.NONE

        /** La richiesta torna dopo [afterMillis] con l'esito dato. */
        fun deliver(afterMillis: Long, result: Map<String, Boolean>) {
            now += afterMillis
            coordinator.onResult(result)
        }
    }

    private val sms = "android.permission.RECEIVE_SMS"
    private val phoneState = "android.permission.READ_PHONE_STATE"
    private val callLog = "android.permission.READ_CALL_LOG"

    @Test
    fun `the first tap launches the request and never opens the settings`() {
        val system = FakeSystem()

        assertTrue(system.coordinator.request(arrayOf(sms)))

        assertEquals(listOf(listOf(sms)), system.launched)
        assertEquals(0, system.settingsOpened)
        assertEquals(0, system.messages)
    }

    @Test
    fun `a first request closed by tapping outside changes nothing and the next tap asks again`() {
        val system = FakeSystem()
        system.coordinator.request(arrayOf(sms))

        // Dialogo chiuso a mano: esito negato, ma la rationale resta falsa e passano secondi.
        system.deliver(afterMillis = 3_000, mapOf(sms to false))

        assertEquals(PermissionState.NONE, system.state(sms))
        assertEquals(0, system.settingsOpened)
        assertEquals(0, system.messages)
        system.coordinator.request(arrayOf(sms))
        assertEquals(2, system.launched.size, "al tocco dopo si rilancia la richiesta")
        assertEquals(0, system.settingsOpened)
    }

    @Test
    fun `a first explicit denial is denied once`() {
        val system = FakeSystem()
        system.coordinator.request(arrayOf(sms))

        system.rationale += sms
        system.deliver(afterMillis = 2_500, mapOf(sms to false))

        assertEquals(PermissionState.DENIED_ONCE, system.state(sms))
        assertEquals(0, system.settingsOpened)
        assertEquals(0, system.messages)
        system.coordinator.request(arrayOf(sms))
        assertEquals(2, system.launched.size, "Android mostrerà ancora il dialogo")
    }

    @Test
    fun `a second explicit denial only shows the message and the next tap opens the settings without a launch`() {
        val system = FakeSystem()
        system.coordinator.request(arrayOf(sms))
        system.rationale += sms
        system.deliver(afterMillis = 2_000, mapOf(sms to false))
        system.coordinator.request(arrayOf(sms))
        assertEquals(2, system.launched.size)

        // Secondo «Non consentire»: Android non mostrerà più il dialogo (rationale falsa).
        system.rationale -= sms
        system.deliver(afterMillis = 2_000, mapOf(sms to false))

        assertEquals(PermissionState.PERMANENT, system.state(sms))
        assertEquals(1, system.messages, "il messaggio compare subito")
        assertEquals(0, system.settingsOpened, "ma le impostazioni non si aprono sotto il dito dell'utente")

        system.coordinator.request(arrayOf(sms))

        assertEquals(2, system.launched.size, "nessun lancio: Android non mostrerebbe nulla")
        assertEquals(1, system.settingsOpened)
        assertEquals(2, system.messages)
    }

    @Test
    fun `a denial that returns at once with no prior state opens the settings immediately`() {
        val system = FakeSystem() // negato per sempre da prima di questa versione: nessuno stato

        system.coordinator.request(arrayOf(sms))
        system.deliver(afterMillis = 120, mapOf(sms to false))

        assertEquals(PermissionState.PERMANENT, system.state(sms))
        assertEquals(1, system.settingsOpened)
        assertEquals(1, system.messages)
    }

    @Test
    fun `a granted result resets the state`() {
        val system = FakeSystem()
        system.states[sms] = PermissionState.DENIED_ONCE
        system.coordinator.request(arrayOf(sms))

        system.granted += sms
        system.deliver(afterMillis = 1_500, mapOf(sms to true))

        assertEquals(PermissionState.NONE, system.state(sms))
        assertEquals(0, system.settingsOpened)
        assertEquals(0, system.messages)
    }

    @Test
    fun `a permission revoked after being granted is asked again and not sent to the settings`() {
        val system = FakeSystem()
        system.coordinator.request(arrayOf(sms))
        system.granted += sms
        system.deliver(afterMillis = 1_500, mapOf(sms to true))

        system.granted -= sms
        system.coordinator.request(arrayOf(sms))

        assertEquals(2, system.launched.size)
        assertEquals(0, system.settingsOpened)
    }

    @Test
    fun `a permanent state with a rationale to show launches the request`() {
        val system = FakeSystem()
        system.states[sms] = PermissionState.PERMANENT
        system.rationale += sms // l'utente ha ripristinato qualcosa: Android offre di nuovo il dialogo

        system.coordinator.request(arrayOf(sms))

        assertEquals(1, system.launched.size)
        assertEquals(0, system.settingsOpened)
    }

    @Test
    fun `an already granted permission launches nothing`() {
        val system = FakeSystem()
        system.granted += sms
        system.states[sms] = PermissionState.PERMANENT

        assertFalse(system.coordinator.request(arrayOf(sms)))

        assertTrue(system.launched.isEmpty())
        assertEquals(0, system.settingsOpened)
        assertEquals(0, system.messages)
    }

    @Test
    fun `with several permissions only the missing ones are asked`() {
        val system = FakeSystem()
        system.granted += phoneState

        system.coordinator.request(arrayOf(phoneState, callLog))

        assertContentEquals(listOf(callLog), system.launched.single())
    }

    @Test
    fun `with several permissions each one follows its own state`() {
        val system = FakeSystem()
        system.coordinator.request(arrayOf(phoneState, callLog))

        // Il primo ha un rifiuto esplicito (rationale vera), il secondo è stato chiuso toccando fuori.
        system.rationale += phoneState
        system.deliver(afterMillis = 2_000, mapOf(phoneState to false, callLog to false))

        assertEquals(PermissionState.DENIED_ONCE, system.state(phoneState))
        assertEquals(PermissionState.NONE, system.state(callLog))
        assertEquals(0, system.settingsOpened)
        assertEquals(0, system.messages)
    }

    @Test
    fun `with several permissions one denied for good sends the user to the settings at the next tap`() {
        val system = FakeSystem()
        system.states[callLog] = PermissionState.PERMANENT
        system.granted += phoneState

        system.coordinator.request(arrayOf(phoneState, callLog))

        assertTrue(system.launched.isEmpty())
        assertEquals(1, system.settingsOpened)
        assertEquals(1, system.messages)
    }

    @Test
    fun `with several permissions an instant denial opens the settings once`() {
        val system = FakeSystem()
        system.coordinator.request(arrayOf(phoneState, callLog))

        system.deliver(afterMillis = 90, mapOf(phoneState to false, callLog to false))

        assertEquals(PermissionState.PERMANENT, system.state(phoneState))
        assertEquals(PermissionState.PERMANENT, system.state(callLog))
        assertEquals(1, system.settingsOpened, "una sola apertura per richiesta")
        assertEquals(1, system.messages)
    }
}
