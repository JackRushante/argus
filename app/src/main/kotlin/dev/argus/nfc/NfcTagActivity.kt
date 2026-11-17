package dev.argus.nfc

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.argus.automation.foreground.ReceiverWorkLauncher
import dev.argus.automation.nfc.AndroidNfcTagReader
import dev.argus.automation.nfc.NfcScanIngress
import dev.argus.automation.nfc.NfcScanVerification
import dev.argus.automation.nfc.NfcTagIntents

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface NfcEntryPoint {
    fun nfcScans(): NfcScanIngress
    fun nfcTagReader(): AndroidNfcTagReader
    fun workLauncher(): ReceiverWorkLauncher
}

internal fun Context.nfcEntryPoint(): NfcEntryPoint =
    EntryPointAccessors.fromApplication(applicationContext, NfcEntryPoint::class.java)

/**
 * Trampolino invisibile dei tag NFC (0.8), nello stile di `ManualRunActivity`: non mostra nulla, passa la
 * scansione al motore e si chiude.
 *
 * È ESPORTATO per forza: il sistema consegna i tag solo ad activity con i filtri NFC. Quindi qualunque app
 * può avviarlo con un id inventato. Per questo la scansione porta `verified`: vero solo se il chiamante
 * iniziale è il servizio NFC del sistema (Android 15+); una scansione non verificata può far scattare le
 * regole ma mai shell né macro sullo schermo (`StaticShellSafety`, FirePolicy). Col telefono bloccato non
 * fa niente: le regole NFC scattano solo a telefono sbloccato. L'id del tag non finisce in nessun log.
 */
class NfcTagActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        try {
            handOver(intent)
        } finally {
            finish()
        }
    }

    private fun handOver(request: Intent?) {
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked
        val tagId = NfcTagIntents.deliverable(request, locked) ?: return
        val verified = NfcScanVerification.verified(Build.VERSION.SDK_INT, initialCallerUid())
        val entry = nfcEntryPoint()
        // Il motore lavora nello scope dell'app con la lease del servizio in primo piano: qui non si aspetta.
        entry.workLauncher().launch("nfc_tag") { entry.nfcScans().onScan(tagId, verified) }
    }

    /** Android 15+: chi ha avviato davvero l'activity. Prima non si sa (`null` = non verificata). */
    private fun initialCallerUid(): Int? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            runCatching { initialCaller.uid }.getOrNull()
        } else {
            null
        }
}
