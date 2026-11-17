package dev.argus.automation.nfc

import android.content.Intent
import android.nfc.NfcAdapter
import dev.argus.automation.triggers.DeviceEventDispatcher
import dev.argus.engine.device.DeviceEventParser
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerMatcher
import dev.argus.engine.safety.StaticShellSafety
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.8: da dove viene una scansione NFC e cosa ne arriva al motore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NfcScansTest {
    @Test
    fun `only the nfc service on android 15 or later makes a verified scan`() {
        assertTrue(NfcScanVerification.verified(35, 1027))
        assertTrue(NfcScanVerification.verified(36, 1027))
        // Lo stesso servizio in un altro utente del telefono (uid = utente * 100000 + app id).
        assertTrue(NfcScanVerification.verified(36, 10 * 100_000 + 1027))
        // Un'app qualunque, un chiamante che non condivide l'identità, un Android più vecchio.
        assertFalse(NfcScanVerification.verified(36, 10_123))
        assertFalse(NfcScanVerification.verified(36, -1))
        assertFalse(NfcScanVerification.verified(36, null))
        assertFalse(NfcScanVerification.verified(34, 1027))
    }

    @Test
    fun `the tag id is read from the discovery intent in canonical form`() {
        val intent = Intent(NfcAdapter.ACTION_TAG_DISCOVERED)
            .putExtra(NfcAdapter.EXTRA_ID, byteArrayOf(0x04, 0xA2.toByte(), 0x19, 0x0B))
        assertEquals("04:A2:19:0B", NfcTagIntents.tagId(intent))
        assertNull(NfcTagIntents.tagId(Intent(NfcAdapter.ACTION_TAG_DISCOVERED)))
        assertNull(NfcTagIntents.tagId(Intent(NfcAdapter.ACTION_TAG_DISCOVERED).putExtra(NfcAdapter.EXTRA_ID, ByteArray(0))))
        assertNull(NfcTagIntents.tagId(null))
        assertTrue(NfcAdapter.ACTION_TECH_DISCOVERED in NfcTagIntents.DISCOVERY_ACTIONS)
    }

    @Test
    fun `a scan is handed over only with the phone unlocked and a real discovery`() {
        val tag = Intent(NfcAdapter.ACTION_TECH_DISCOVERED).putExtra(NfcAdapter.EXTRA_ID, byteArrayOf(0x04, 0xA2.toByte()))
        assertEquals("04:A2", NfcTagIntents.deliverable(tag, keyguardLocked = false))
        // Bloccato, o stato del blocco illeggibile: niente.
        assertNull(NfcTagIntents.deliverable(tag, keyguardLocked = true))
        assertNull(NfcTagIntents.deliverable(tag, keyguardLocked = null))
        // Un avvio qualunque dell'activity esportata (nessuna azione NFC) non porta nulla al motore.
        assertNull(NfcTagIntents.deliverable(Intent(Intent.ACTION_MAIN).putExtra(NfcAdapter.EXTRA_ID, byteArrayOf(0x04)), false))
        assertNull(NfcTagIntents.deliverable(Intent().putExtra(NfcAdapter.EXTRA_ID, byteArrayOf(0x04)), false))
        assertNull(NfcTagIntents.deliverable(null, false))
    }

    @Test
    fun `a scan reaches the engine with its origin and only a verified one may start shell`() = runTest {
        val delivered = mutableListOf<TriggerEnvelope>()
        val ingress = NfcScanIngress(DeviceEventParser(), DeviceEventDispatcher { delivered += it }) { 1_000L }

        assertTrue(ingress.onScan("04a2190b", verified = false))
        assertTrue(ingress.onScan("04:A2:19:0B", verified = true))
        assertFalse(ingress.onScan("not a tag", verified = true))

        val (forged, real) = delivered.map { it.event as TriggerEvent.NfcTagScanned }
        assertEquals(TriggerEvent.NfcTagScanned("04:A2:19:0B", verified = false), forged)
        val rule = Trigger.NfcTag("04:A2:19:0B")
        assertTrue(TriggerMatcher().matches(rule, forged))
        assertFalse(StaticShellSafety.allows(rule, forged, emptySet()))
        assertTrue(StaticShellSafety.allows(rule, real, emptySet()))
        // L'id del tag non compare in chiaro nell'id dell'evento.
        assertFalse(delivered.any { "04:A2" in it.id.value })
    }
}
