package dev.argus.device

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.argus.engine.model.StateKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Lettura reale delle chiavi di stato con le API pubbliche, senza Shizuku (0.4.0, F19). */
@RunWith(AndroidJUnit4::class)
class PublicStateInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun publicReaderGivesTheRegistryValuesWithoutShizuku() {
        val source = AndroidPublicStateSource(context)
        val values = PublicStateReader(source).read(StateKeys.ALL.keys)

        assertTrue(values.keys.all(StateKeys.ALL::containsKey))
        // Il Bluetooth manca senza il permesso (o prima di Android 12); il DND manca in «solo sveglie»,
        // che non ha un valore nel registry. Tutte le altre chiavi si leggono sempre.
        val bluetoothReadable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && source.canReadBluetooth()
        val mandatory = StateKeys.ALL.keys - StateKeys.BLUETOOTH - StateKeys.DND
        assertTrue("chiavi mancanti: ${mandatory - values.keys}", values.keys.containsAll(mandatory))
        assertEquals(bluetoothReadable, StateKeys.BLUETOOTH in values)

        assertTrue(values.getValue(StateKeys.RINGER) in setOf("normal", "vibrate", "silent"))
        assertTrue(values.getValue(StateKeys.WIFI) in setOf("on", "off"))
        assertTrue(values.getValue(StateKeys.AIRPLANE) in setOf("on", "off"))
        assertTrue(values.getValue(StateKeys.SCREEN) in setOf("on", "off"))
        assertTrue(values.getValue(StateKeys.CHARGING) in setOf("true", "false"))
        assertTrue(values.getValue(StateKeys.BATTERY).toInt() in 0..100)
        values[StateKeys.DND]?.let { assertTrue(it in setOf("off", "priority", "total")) }
        assertTrue(values.values.none(String::isEmpty))
    }
}
