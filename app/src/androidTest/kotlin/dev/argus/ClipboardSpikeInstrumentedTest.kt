package dev.argus

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spike P2-0 (piano P2 §D3): verifica se `setPrimaryClip` funziona SENZA focus/foreground su
 * Android 16 reale. La lettura è notoriamente ristretta in background, quindi la verità della
 * scrittura si controlla dall'host con `dumpsys clipboard` DOPO il run. I due metodi girano in
 * invocazioni separate (`#backgroundWriteToClipboard`, poi verifica host, poi `#clearSpikeClip`
 * per non lasciare residui negli appunti dell'utente). Spike temporaneo: si rimuove a decisione
 * presa e registrata nel piano.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardSpikeInstrumentedTest {
    private val manager: ClipboardManager
        get() = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @Test
    fun backgroundWriteToClipboard() {
        val clip = ClipData.newPlainText("argus-spike", "SPIKE-123456").apply {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        val result = runCatching { manager.setPrimaryClip(clip) }
        assertTrue("setPrimaryClip fallita: ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test
    fun clearSpikeClip() {
        val result = runCatching { manager.clearPrimaryClip() }
        assertTrue("clearPrimaryClip fallita: ${result.exceptionOrNull()}", result.isSuccess)
    }
}
