package dev.argus.automation.phone

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Il canale telefonia entra dal manifest del modulo: permessi e receiver vanno blindati. */
class PhoneManifestHardeningTest {
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun `telephony permissions are declared`() {
        assertTrue("android.permission.RECEIVE_SMS" in manifest)
        assertTrue("android.permission.READ_PHONE_STATE" in manifest)
        // Senza READ_CALL_LOG il broadcast PHONE_STATE arriva senza numero: le regole
        // "chiamata da X" la richiedono; quelle "da chiunque" funzionano comunque.
        assertTrue("android.permission.READ_CALL_LOG" in manifest)
    }

    @Test
    fun `sms receiver only accepts the system broadcast`() {
        assertTrue("dev.argus.automation.phone.SmsBroadcastReceiver" in manifest)
        assertTrue(
            "android.permission.BROADCAST_SMS" in manifest,
            "il receiver SMS deve richiedere la permission di sistema del broadcaster",
        )
        assertTrue("android.provider.Telephony.SMS_RECEIVED" in manifest)
    }

    @Test
    fun `phone state receiver is declared`() {
        assertTrue("dev.argus.automation.phone.PhoneStateBroadcastReceiver" in manifest)
        assertTrue("android.intent.action.PHONE_STATE" in manifest)
    }
}
