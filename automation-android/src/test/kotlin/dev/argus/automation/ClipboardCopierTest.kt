package dev.argus.automation

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.TriggerEvent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClipboardCopierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scheduled = mutableListOf<() -> Unit>()
    private val copier = AndroidClipboardCopier(
        context = context,
        expiryMillis = 60_000,
        expiryScheduler = ClipboardExpiryScheduler { _, block -> scheduled += block },
    )
    private val manager: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun sms(text: String?) = TriggerEvent.PhoneStateChanged(
        PhoneEvent.SMS_RECEIVED, "+39001", smsText = text,
    )

    @Test
    fun `extracts the first capture group and marks the clip sensitive`() {
        val result = copier.copy(
            event = sms("G-482913 e' il tuo codice di verifica Argus"),
            extractionRegex = "(\\d{4,8})",
        )

        assertEquals(ActionResult.Success, result)
        val clip = manager.primaryClip
        assertEquals("482913", clip?.getItemAt(0)?.text?.toString())
        assertTrue(
            clip?.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true,
            "il codice non deve comparire nelle anteprime di sistema",
        )
    }

    @Test
    fun `without regex the whole message is copied`() {
        assertEquals(ActionResult.Success, copier.copy(sms("testo integrale"), extractionRegex = null))
        assertEquals("testo integrale", manager.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `notification text is a valid source too`() {
        val event = TriggerEvent.NotificationPosted(
            pkg = "com.whatsapp",
            text = "codice 7741",
        )
        assertEquals(ActionResult.Success, copier.copy(event, "(\\d{4,8})"))
        assertEquals("7741", manager.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `no match and no payload are honest failures without touching the clipboard`() {
        copier.copy(sms("riferimento ordine"), extractionRegex = null)

        assertEquals(
            ActionResult.Failure("otp_not_found"),
            copier.copy(sms("nessun codice qui"), extractionRegex = "(\\d{6})"),
        )
        assertEquals(
            ActionResult.Failure("clipboard_source_missing"),
            copier.copy(sms(null), extractionRegex = null),
        )
        assertEquals(
            ActionResult.Failure("clipboard_source_missing"),
            copier.copy(
                TriggerEvent.PhoneStateChanged(PhoneEvent.INCOMING_CALL, "3"),
                extractionRegex = null,
            ),
        )
        // Il contenuto precedente resta intatto dopo i fallimenti.
        assertEquals("riferimento ordine", manager.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `match without capture group copies the whole match`() {
        assertEquals(ActionResult.Success, copier.copy(sms("pin 55443"), extractionRegex = "\\d{4,8}"))
        assertEquals("55443", manager.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `copyLiteral writes the exact string and marks the clip sensitive`() {
        val result = copier.copyLiteral("ordine #4821 confermato")

        assertEquals(ActionResult.Success, result)
        val clip = manager.primaryClip
        assertEquals("ordine #4821 confermato", clip?.getItemAt(0)?.text?.toString())
        assertTrue(
            clip?.description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true,
            "anche il literal è marcato sensibile come copy_to_clipboard",
        )
    }

    @Test
    fun `copyLiteral of a blank string is an honest failure without touching the clipboard`() {
        copier.copyLiteral("valore precedente")

        assertEquals(ActionResult.Failure("clipboard_source_missing"), copier.copyLiteral("   "))
        // Il contenuto precedente resta intatto dopo il fallimento.
        assertEquals("valore precedente", manager.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `expiry clears an unchanged Argus clip`() {
        assertEquals(ActionResult.Success, copier.copyLiteral("482913"))

        scheduled.single().invoke()

        assertTrue(manager.primaryClip == null || manager.primaryClip?.itemCount == 0)
    }

    @Test
    fun `expiry preserves a clip replaced by the user`() {
        assertEquals(ActionResult.Success, copier.copyLiteral("482913"))
        manager.setPrimaryClip(ClipData.newPlainText("user", "non cancellare"))

        scheduled.single().invoke()

        assertEquals("non cancellare", manager.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `an older expiry cannot clear a newer Argus clip`() {
        assertEquals(ActionResult.Success, copier.copyLiteral("primo"))
        assertEquals(ActionResult.Success, copier.copyLiteral("secondo"))

        scheduled[0].invoke()
        assertEquals("secondo", manager.primaryClip?.getItemAt(0)?.text?.toString())

        scheduled[1].invoke()
        assertTrue(manager.primaryClip == null || manager.primaryClip?.itemCount == 0)
    }
}
