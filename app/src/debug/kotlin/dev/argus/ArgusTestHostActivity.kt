package dev.argus

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.argus.nav.ArgusNavHost
import dev.argus.ui.theme.ArgusTheme

/** Host non esportato del solo build debug: rende deterministici i test UI anche con keyguard. */
@AndroidEntryPoint
class ArgusTestHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        setContent {
            ArgusTheme {
                ArgusNavHost()
            }
        }
    }
}
