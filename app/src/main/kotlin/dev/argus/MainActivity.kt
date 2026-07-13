package dev.argus

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.argus.nav.ArgusNavHost
import dev.argus.ui.theme.ArgusTheme

/** Host Compose edge-to-edge. Il runtime reale viene inizializzato da [ArgusApplication]. */

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArgusTheme {
                ArgusNavHost()
            }
        }
    }
}
