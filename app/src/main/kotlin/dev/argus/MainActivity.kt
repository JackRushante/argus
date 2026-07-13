package dev.argus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.argus.nav.ArgusNavHost
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Host Compose dell'APK demo (M2 Task 12). Monta ArgusTheme + ArgusNavHost, che
// cabla i 6 schermi stateless su `Fixtures` (dati finti). Edge-to-edge: le barre
// di sistema sono trasparenti (tema) e gli inset sono gestiti dallo Scaffold del
// NavHost. NIENTE ViewModel/Room/Shizuku/rete: è una demo della UI, non esegue
// automazioni (quello è P0-B).
// =============================================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ArgusTheme {
                ArgusNavHost()
            }
        }
    }
}
