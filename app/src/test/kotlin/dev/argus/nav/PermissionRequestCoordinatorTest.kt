package dev.argus.nav

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * B33: dopo due rifiuti Android non mostra più la richiesta e «Concedi» non farebbe niente. Il primo
 * rifiuto non è permanente (prima di una richiesta `shouldShowRequestPermissionRationale` è false
 * anche se non è mai stato chiesto): serve il registro «già chiesto» per distinguerli.
 */
class PermissionRequestCoordinatorTest {
    // --- decisione pura: (già chiesto, rationale, esito) -> passo -----------------------------------

    @Test
    fun `never asked and no rationale means ask - the first denial is not permanent`() {
        assertEquals(
            PermissionStep.REQUEST,
            nextPermissionStep(alreadyAsked = false, shouldShowRationale = false, PermissionOutcome.NOT_REQUESTED_YET),
        )
    }

    @Test
    fun `asked before with a rationale to show means ask again`() {
        assertEquals(
            PermissionStep.REQUEST,
            nextPermissionStep(alreadyAsked = true, shouldShowRationale = true, PermissionOutcome.NOT_REQUESTED_YET),
        )
    }

    @Test
    fun `asked before and no rationale means open the app settings`() {
        assertEquals(
            PermissionStep.OPEN_APP_SETTINGS,
            nextPermissionStep(alreadyAsked = true, shouldShowRationale = false, PermissionOutcome.NOT_REQUESTED_YET),
        )
    }

    @Test
    fun `a denial with a rationale left is not permanent and does nothing`() {
        assertEquals(
            PermissionStep.NONE,
            nextPermissionStep(alreadyAsked = true, shouldShowRationale = true, PermissionOutcome.DENIED),
        )
    }

    @Test
    fun `a denial without a rationale after asking is permanent`() {
        assertEquals(
            PermissionStep.OPEN_APP_SETTINGS,
            nextPermissionStep(alreadyAsked = true, shouldShowRationale = false, PermissionOutcome.DENIED),
        )
    }

    @Test
    fun `a denial that was never asked is never treated as permanent`() {
        assertEquals(
            PermissionStep.NONE,
            nextPermissionStep(alreadyAsked = false, shouldShowRationale = false, PermissionOutcome.DENIED),
        )
    }

    @Test
    fun `a granted permission needs no step`() {
        assertEquals(
            PermissionStep.NONE,
            nextPermissionStep(alreadyAsked = true, shouldShowRationale = false, PermissionOutcome.GRANTED),
        )
    }

    // --- flusso completo con un sistema finto ------------------------------------------------------

    private class FakeSystem {
        val granted = mutableSetOf<String>()
        val rationale = mutableSetOf<String>()
        val asked = mutableSetOf<String>()
        val launched = mutableListOf<List<String>>()
        var settingsOpened = 0
        var settingsMessages = 0

        val coordinator = PermissionRequestCoordinator(
            store = object : PermissionAskStore {
                override fun wasAsked(permission: String) = permission in asked
                override fun markAsked(permission: String) {
                    asked += permission
                }

                override fun clearAsked(permission: String) {
                    asked -= permission
                }
            },
            isGranted = { it in granted },
            shouldShowRationale = { it in rationale },
            launch = { launched += it.toList() },
            openAppSettings = { settingsOpened += 1 },
            notifySettingsNeeded = { settingsMessages += 1 },
        )
    }

    private val sms = "android.permission.RECEIVE_SMS"
    private val phoneState = "android.permission.READ_PHONE_STATE"
    private val callLog = "android.permission.READ_CALL_LOG"

    @Test
    fun `the first tap asks and does not open the settings`() {
        val system = FakeSystem()

        assertTrue(system.coordinator.request(arrayOf(sms)))

        assertEquals(listOf(listOf(sms)), system.launched)
        assertEquals(0, system.settingsOpened)
        assertTrue(sms in system.asked, "la richiesta è registrata come già fatta")
    }

    @Test
    fun `a first denial keeps asking and a second denial sends the user to the settings`() {
        val system = FakeSystem()
        system.coordinator.request(arrayOf(sms))

        // Primo rifiuto: Android mostrerà ancora la richiesta (rationale vera).
        system.rationale += sms
        system.coordinator.onResult(mapOf(sms to false))
        assertEquals(0, system.settingsOpened, "il primo rifiuto non è permanente")

        system.coordinator.request(arrayOf(sms))
        assertEquals(2, system.launched.size, "il secondo tocco chiede di nuovo")

        // Secondo rifiuto: Android non mostrerà più la richiesta (rationale falsa).
        system.rationale -= sms
        system.coordinator.onResult(mapOf(sms to false))
        assertEquals(1, system.settingsOpened)
        assertEquals(1, system.settingsMessages, "il messaggio «abilita dalle impostazioni» accompagna l'apertura")
    }

    @Test
    fun `once denied for good the tap opens the settings without launching a request`() {
        val system = FakeSystem()
        system.asked += sms // già chiesto in passato; rationale falsa = negato per sempre

        system.coordinator.request(arrayOf(sms))

        assertTrue(system.launched.isEmpty(), "Android non mostrerebbe nulla: non si lancia")
        assertEquals(1, system.settingsOpened)
        assertEquals(1, system.settingsMessages)
    }

    @Test
    fun `a denial from before the registry existed is recognised at the first result`() {
        val system = FakeSystem() // registro vuoto: il permesso era già negato per sempre

        system.coordinator.request(arrayOf(sms))
        // La richiesta torna subito negata e Android non offre più la rationale.
        system.coordinator.onResult(mapOf(sms to false))

        assertEquals(1, system.settingsOpened)
    }

    @Test
    fun `a granted result clears the registry so a later revoke asks again`() {
        val system = FakeSystem()
        system.coordinator.request(arrayOf(sms))
        system.granted += sms
        system.coordinator.onResult(mapOf(sms to true))
        assertFalse(sms in system.asked)
        assertEquals(0, system.settingsOpened)

        // L'utente lo toglie dalle impostazioni: non è un rifiuto permanente.
        system.granted -= sms
        system.coordinator.request(arrayOf(sms))

        assertEquals(2, system.launched.size)
        assertEquals(0, system.settingsOpened)
    }

    @Test
    fun `an already granted permission launches nothing`() {
        val system = FakeSystem()
        system.granted += sms

        assertFalse(system.coordinator.request(arrayOf(sms)))

        assertTrue(system.launched.isEmpty())
        assertEquals(0, system.settingsOpened)
    }

    @Test
    fun `with several permissions only the missing ones are asked`() {
        val system = FakeSystem()
        system.granted += phoneState

        system.coordinator.request(arrayOf(phoneState, callLog))

        assertContentEquals(listOf(callLog), system.launched.single())
    }

    @Test
    fun `with several permissions one denied for good sends the user to the settings`() {
        val system = FakeSystem()
        system.coordinator.request(arrayOf(phoneState, callLog))
        system.rationale += phoneState // il primo ha ancora la rationale, il secondo no
        system.coordinator.onResult(mapOf(phoneState to false, callLog to false))

        assertEquals(1, system.settingsOpened)
        assertEquals(1, system.settingsMessages)
    }
}
