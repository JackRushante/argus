package dev.argus.automation.span

import android.app.UiModeManager
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.NightMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 0.8: un valore letto prima del cambio, rimesso con la sua azione tipata, torna esattamente al valore di prima;
 * quello che non si legge o non si rappresenta resta null (mai un default).
 */
class SpanReadingsTest {
    /** La stessa formula dell'esecutore di `set_volume`. */
    private fun volumeIndex(percent: Int, max: Int) = if (percent == 0) 0 else maxOf(1, Math.round(percent / 100.0 * max).toInt())

    @Test
    fun `every stream index comes back exactly through set_volume`() {
        listOf(7, 15, 25, 30, 40).forEach { max ->
            (0..max).forEach { index ->
                val percent = SpanReadings.volumePercent(index, max)
                requireNotNull(percent) { "indice $index su $max senza percentuale" }
                assertEquals(index, volumeIndex(percent, max), "indice $index su $max")
            }
        }
        assertNull(SpanReadings.volumePercent(16, 15))
        assertNull(SpanReadings.volumePercent(3, 0))
    }

    @Test
    fun `brightness takes the nearest level of set_brightness and refuses values out of scale`() {
        assertEquals(0, SpanReadings.brightnessPercent(0))
        assertEquals(100, SpanReadings.brightnessPercent(255))
        assertEquals(50, SpanReadings.brightnessPercent(128))
        assertNull(SpanReadings.brightnessPercent(256))
        assertNull(SpanReadings.brightnessPercent(-1))
    }

    @Test
    fun `builtin readings map only known values`() {
        assertEquals(true, SpanReadings.flag("on"))
        assertEquals(false, SpanReadings.flag("off"))
        assertNull(SpanReadings.flag(null))
        assertNull(SpanReadings.flag("unknown"))
        assertEquals(DndMode.PRIORITY, SpanReadings.dnd("priority"))
        assertNull(SpanReadings.dnd("alarms"))
        assertEquals(NightMode.AUTO, SpanReadings.night(UiModeManager.MODE_NIGHT_AUTO))
        assertEquals(NightMode.ON, SpanReadings.night(UiModeManager.MODE_NIGHT_YES))
        assertNull(SpanReadings.night(UiModeManager.MODE_NIGHT_CUSTOM))
    }

    @Test
    fun `an absent setting is not an empty value`() {
        assertEquals("600000", SpanReadings.settingText("600000"))
        assertNull(SpanReadings.settingText("null"))
        assertNull(SpanReadings.settingText(""))
        assertNull(SpanReadings.settingText(null))
    }
}
