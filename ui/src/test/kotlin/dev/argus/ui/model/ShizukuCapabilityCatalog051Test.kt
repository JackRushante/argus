package dev.argus.ui.model

import dev.argus.engine.model.ActionTypeIds
import dev.argus.ui.presentation.RenderLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Le azioni privilegiate tipizzate 0.5.1 stanno nella lista «Cosa richiede Shizuku», nella riga giusta. */
class ShizukuCapabilityCatalog051Test {
    private val ids = listOf(
        ActionTypeIds.APP_CONTROL,
        ActionTypeIds.APP_PERMISSION,
        ActionTypeIds.SET_AIRPLANE,
        ActionTypeIds.SET_LOCATION,
        ActionTypeIds.SET_NFC,
        ActionTypeIds.SET_BATTERY_SAVER,
        ActionTypeIds.LOCK_SCREEN,
        ActionTypeIds.SCREENSHOT,
    )

    @Test
    fun `all eight need Shizuku and sit in one row`() {
        RenderLanguage.entries.forEach { language ->
            val rows = ShizukuCapabilityCatalog.rows(language)
            val row = rows.single { ActionTypeIds.APP_CONTROL in it.actionTypeIds }
            assertEquals(ShizukuRequirement.REQUIRED, row.requirement)
            assertEquals(ids.toSet(), row.actionTypeIds.toSet())
            assertTrue(row.note.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `the row is written in the language asked`() {
        val english = ShizukuCapabilityCatalog.rows(RenderLanguage.EN).single { ActionTypeIds.SET_NFC in it.actionTypeIds }
        val italian = ShizukuCapabilityCatalog.rows(RenderLanguage.IT).single { ActionTypeIds.SET_NFC in it.actionTypeIds }
        assertTrue(english.title.startsWith("Control apps"))
        assertTrue(italian.title.startsWith("Controllare le app"))
    }
}
