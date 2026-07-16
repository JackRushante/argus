package dev.argus

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.argus.automation.base.AndroidBaseActionExecutor
import dev.argus.automation.base.AndroidBaseActionSurface
import dev.argus.engine.model.DndMode
import dev.argus.engine.runtime.ActionResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gate del tier base per DND (P3-3 B4): prova sul device reale che, essendo Argus un notification
 * listener, l'accesso «Non disturbare» è implicito (`isNotificationPolicyAccessGranted == true`) e
 * che [AndroidBaseActionSurface] cambia davvero il DND via NotificationManager, SENZA Shizuku.
 * Non tocca alcuna regola dell'utente; ripristina il filtro originale.
 */
@RunWith(AndroidJUnit4::class)
class ArgusBaseTierDndInstrumentedTest {

    @Test
    fun dndPolicyIsImplicitAndSetDndWorksWithoutShizuku() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val nm = context.getSystemService(NotificationManager::class.java)

        assertTrue(
            "DND policy access non implicito nonostante il notification listener attivo",
            nm.isNotificationPolicyAccessGranted,
        )

        val original = nm.currentInterruptionFilter
        val executor = AndroidBaseActionExecutor(AndroidBaseActionSurface(context))
        try {
            val result = runBlocking { executor.setDnd(DndMode.PRIORITY) }
            assertEquals(ActionResult.Success, result)
            assertEquals(
                "NotificationManager non riflette il filtro impostato dal tier base",
                NotificationManager.INTERRUPTION_FILTER_PRIORITY,
                nm.currentInterruptionFilter,
            )
        } finally {
            nm.setInterruptionFilter(original)
        }
    }
}
