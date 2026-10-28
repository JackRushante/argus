package dev.argus.automation.screen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Servizio di Accessibilità di Argus (0.5.1, F17). Attivo solo quando l'utente lo abilita nelle
 * impostazioni di sistema (o dal dialogo dei permessi permanenti, con un backend privilegiato).
 *
 * NON ascolta niente: gli eventi sono ignorati e nessun contenuto dello schermo viene letto, copiato
 * o registrato finché non c'è una macro approvata in corso. Quando c'è, [AccessibilityScreenSource]
 * legge l'albero della finestra attiva per un passo alla volta, e basta. Il servizio non dichiara
 * gesti né filtro dei tasti.
 */
class ArgusAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (current === this) current = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        /** L'istanza collegata, o null se il servizio non è acceso. */
        @Volatile
        internal var current: ArgusAccessibilityService? = null
            private set
    }
}
