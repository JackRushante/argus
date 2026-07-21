package dev.argus.ui.model

import dev.argus.engine.model.ActionTypeIds
import dev.argus.ui.presentation.RenderLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Task #54 — la lista "Cosa richiede Shizuku" mostrata in onboarding deve essere onesta e coerente
 * con la classificazione reale (`ActionPrivileges` BASE vs PRIVILEGED + degrado activity-launch da
 * background, fix #53). Ogni action type deve cadere nella categoria giusta e in UNA sola riga.
 */
class ShizukuCapabilityCatalogTest {

    private val rows = ShizukuCapabilityCatalog.rows()

    @Test
    fun `english catalog does not leak italian production copy`() {
        val english = ShizukuCapabilityCatalog.rows(RenderLanguage.EN)

        assertTrue(english.any { it.title == "Run shell commands" })
        assertTrue(english.none { it.title.contains("Eseguire") })
    }

    private fun requirementOf(actionTypeId: String): ShizukuRequirement? =
        rows.firstOrNull { actionTypeId in it.actionTypeIds }?.requirement

    @Test
    fun `le azioni privilegiate richiedono Shizuku`() {
        listOf(
            ActionTypeIds.SET_WIFI,
            ActionTypeIds.SET_BLUETOOTH,
            ActionTypeIds.SET_MOBILE_DATA,
            ActionTypeIds.RUN_SHELL,
            ActionTypeIds.WRITE_SETTING,
        ).forEach { id ->
            assertEquals(ShizukuRequirement.REQUIRED, requirementOf(id), "atteso REQUIRED per $id")
        }
    }

    @Test
    fun `le activity-launch da background sono meglio con Shizuku`() {
        listOf(
            ActionTypeIds.SET_ALARM,
            ActionTypeIds.SET_TIMER,
            ActionTypeIds.LAUNCH_APP,
            ActionTypeIds.OPEN_URL,
            ActionTypeIds.OPEN_SETTINGS_SCREEN,
        ).forEach { id ->
            assertEquals(ShizukuRequirement.RECOMMENDED, requirementOf(id), "atteso RECOMMENDED per $id")
        }
    }

    @Test
    fun `le azioni manager e clipboard non richiedono Shizuku`() {
        listOf(
            ActionTypeIds.SET_VOLUME,
            ActionTypeIds.SET_RINGER,
            ActionTypeIds.SET_DND,
            ActionTypeIds.SET_FLASHLIGHT,
            ActionTypeIds.VIBRATE,
            ActionTypeIds.SHOW_NOTIFICATION,
            ActionTypeIds.COPY_TO_CLIPBOARD,
            ActionTypeIds.COPY_TEXT,
        ).forEach { id ->
            assertEquals(ShizukuRequirement.NOT_REQUIRED, requirementOf(id), "atteso NOT_REQUIRED per $id")
        }
    }

    @Test
    fun `tutte e tre le categorie sono rappresentate`() {
        val present = rows.map { it.requirement }.toSet()
        assertEquals(ShizukuRequirement.entries.toSet(), present)
    }

    @Test
    fun `ogni action type compare in una sola riga`() {
        val allIds = rows.flatMap { it.actionTypeIds }
        val duplicates = allIds.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(duplicates.isEmpty(), "action type in piu' righe: $duplicates")
    }

    @Test
    fun `esiste almeno una riga descrittiva senza action type per categoria chiave`() {
        // La sveglia/timer "a mano dalla chat" (NOT_REQUIRED) e i lettori privilegiati (REQUIRED)
        // sono righe senza action type dedicato: la lista resta onesta anche sul foreground.
        assertNotNull(
            rows.firstOrNull {
                it.requirement == ShizukuRequirement.NOT_REQUIRED && it.actionTypeIds.isEmpty()
            },
            "manca la riga sveglia/timer manuali",
        )
        assertNotNull(
            rows.firstOrNull {
                it.requirement == ShizukuRequirement.REQUIRED && it.actionTypeIds.isEmpty()
            },
            "manca la riga lettori privilegiati",
        )
    }
}
