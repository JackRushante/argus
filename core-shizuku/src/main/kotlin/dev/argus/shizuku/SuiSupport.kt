package dev.argus.shizuku

import rikka.sui.Sui

/**
 * Supporto a Sui (0.5.0): Shizuku integrato come modulo Magisk/KernelSU, parte all'avvio senza app
 * di gestione e senza adb. Il client `rikka.sui.Sui` è già dentro `dev.rikka.shizuku:api` (MIT, su
 * Maven Central): niente libreria in più. Con Sui attivo il [ShizukuGateway] esistente funziona così
 * com'è, perché Sui consegna il binder alla stessa API di Shizuku.
 */
internal object SuiSupport {
    /** Idempotente e senza eccezioni: `false` se Sui non c'è. */
    fun ensureInit(packageName: String): Boolean = runCatching {
        Sui.isSui() || Sui.init(packageName)
    }.getOrDefault(false)

    fun isSui(): Boolean = runCatching { Sui.isSui() }.getOrDefault(false)
}
