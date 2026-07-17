package dev.argus.engine.model

/**
 * Tier di esecuzione di un'azione rispetto al privilegio richiesto (decision record §7.3).
 * Ortogonale a [ActionTier] (deterministica/generativa): qui si decide solo se serve lo shell
 * elevato Shizuku.
 */
enum class ActionPrivilege {
    /** Eseguibile con API Android normali o lane generativa: nessun privilegio elevato. */
    BASE,

    /** Richiede lo shell privilegiato (Shizuku): deve fallire pulito quando non è disponibile. */
    PRIVILEGED,
}

/**
 * Router chiuso azione → tier. Il `when` è esaustivo **senza `else`**: aggiungere un nuovo
 * [Action] non compila finché non se ne dichiara il privilegio, così nessuna azione resta
 * classificata per default (piano P3-3 §1). La base non deve mai bloccarsi per un outage Shizuku.
 */
object ActionPrivileges {
    fun of(action: Action): ActionPrivilege = when (action) {
        // Privilegiate: toggle radio, shell e injection UI passano dal gateway Shizuku.
        is Action.SetWifi,
        is Action.SetBluetooth,
        is Action.RunShell,
        is Action.Tap,
        is Action.InputText,
        // Scrittura impostazioni: `settings put` su secure/global passa dallo shell Shizuku.
        is Action.WriteSetting,
        -> ActionPrivilege.PRIVILEGED

        // Base: Intent, policy/audio manager, notifiche, clipboard, RemoteInput reply e lane LLM
        // non richiedono Shizuku.
        is Action.SetDnd,
        is Action.SetRinger,
        is Action.LaunchApp,
        is Action.OpenUrl,
        is Action.ShowNotification,
        is Action.WhatsAppReply,
        is Action.CopyToClipboard,
        // Sveglia/timer: Intent AlarmClock col permesso normal SET_ALARM, nessuno Shizuku.
        is Action.SetAlarm,
        is Action.SetTimer,
        is Action.InvokeLlm,
        is Action.InvokeLlmV2,
        -> ActionPrivilege.BASE
    }

    fun requiresShizuku(action: Action): Boolean = of(action) == ActionPrivilege.PRIVILEGED
}
