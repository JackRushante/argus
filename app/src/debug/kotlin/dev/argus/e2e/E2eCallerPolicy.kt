package dev.argus.e2e

/**
 * Chi può parlare con l'aggancio di debug degli E2E (0.6.0): solo la shell di adb (uid 2000) e root
 * (uid 0). Qualsiasi altro uid — un'altra app, il sistema, lo stesso Argus — è rifiutato. La regola
 * sta in una funzione pura così si prova su JVM senza Android.
 */
object E2eCallerPolicy {
    const val ROOT_UID = 0
    const val SHELL_UID = 2000

    fun allows(uid: Int): Boolean = uid == ROOT_UID || uid == SHELL_UID
}
