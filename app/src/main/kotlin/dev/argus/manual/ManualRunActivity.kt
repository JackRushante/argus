package dev.argus.manual

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dev.argus.R
import dev.argus.automation.manual.ManualIntents
import dev.argus.automation.manual.ManualRunResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Trampolino invisibile delle scorciatoie sulla schermata Home e dei pulsanti del widget (F8).
 *
 * NON è esportato: lo avvia solo il sistema per conto di Argus (scorciatoia fissata, `PendingIntent`
 * del widget). Ogni avvio porta id della regola e token; senza il token giusto non parte niente. Resta
 * in primo piano, senza disegnare nulla e senza intercettare i tocchi, finché il motore ha finito o per
 * poco: le azioni che aprono app partono dal contesto del tocco, che è un gesto dell'utente.
 */
class ManualRunActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // La finestra non deve rubare i tocchi alla Home mentre il motore lavora.
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val request = intent
        if (request == null || request.action != ManualIntents.ACTION_RUN) {
            finish()
            return
        }
        val ruleId = request.getStringExtra(ManualIntents.EXTRA_RULE_ID)
        val token = request.getStringExtra(ManualIntents.EXTRA_TOKEN)
        val runner = manualEntryPoint().runner()
        lifecycleScope.launch {
            try {
                when (val result = runner.runFromIntent(ruleId, token)) {
                    is ManualRunResult.Accepted -> {
                        if (result.paused) {
                            toast(getString(R.string.manual_toast_paused))
                        } else {
                            toast(getString(R.string.manual_toast_started, result.ruleName))
                        }
                        // Il lavoro continua nello scope dell'app anche se qui si smette di aspettare.
                        withTimeoutOrNull(FOREGROUND_WAIT_MILLIS) { result.completion.join() }
                    }
                    is ManualRunResult.Rejected -> toast(getString(R.string.manual_toast_unavailable))
                }
            } finally {
                finish()
            }
        }
    }

    private fun toast(text: String) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** Un'attesa breve: oltre, la finestra trasparente non deve restare sopra la Home. */
        const val FOREGROUND_WAIT_MILLIS = 3_000L
    }
}
