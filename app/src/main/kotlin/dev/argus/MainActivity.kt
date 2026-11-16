package dev.argus

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import dev.argus.nav.AppDeepLink
import dev.argus.nav.ArgusNavHost
import dev.argus.ui.theme.ArgusTheme

/** Host Compose edge-to-edge. Il runtime reale viene inizializzato da [ArgusApplication]. */

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** 0.7.2: schermata chiesta dal tocco di un avviso di salute, finché il NavHost non la apre. */
    private var deepLink by mutableStateOf<AppDeepLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Dopo una ricreazione (rotazione) l'intent è lo stesso: la navigazione è già avvenuta.
        if (savedInstanceState == null) deepLink = AppDeepLink.from(intent)
        setContent {
            ArgusTheme {
                ArgusNavHost(deepLink = deepLink, onDeepLinkHandled = { deepLink = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AppDeepLink.from(intent)?.let { deepLink = it }
    }
}
