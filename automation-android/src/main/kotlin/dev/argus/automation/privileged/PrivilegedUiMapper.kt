package dev.argus.automation.privileged

import dev.argus.device.PermanentGrant
import dev.argus.shizuku.PrivilegedBackend
import dev.argus.shizuku.RootState
import dev.argus.ui.model.PermanentGrantRow
import dev.argus.ui.model.PermanentGrantUi
import dev.argus.ui.model.PrivilegedBackendUi
import dev.argus.ui.model.PrivilegedUi
import dev.argus.ui.model.RootStateUi
import dev.argus.ui.presentation.RenderLanguage

internal fun PrivilegedBackend.toUi(): PrivilegedBackendUi = when (this) {
    PrivilegedBackend.NONE -> PrivilegedBackendUi.NONE
    PrivilegedBackend.SHIZUKU -> PrivilegedBackendUi.SHIZUKU
    PrivilegedBackend.SHIZUKU_SHELL -> PrivilegedBackendUi.SHIZUKU_SHELL
    PrivilegedBackend.SHIZUKU_ROOT -> PrivilegedBackendUi.SHIZUKU_ROOT
    PrivilegedBackend.SUI -> PrivilegedBackendUi.SUI
    PrivilegedBackend.ROOT -> PrivilegedBackendUi.ROOT
}

internal fun RootState.toUi(): RootStateUi = when (this) {
    RootState.OFF -> RootStateUi.OFF
    RootState.CHECKING -> RootStateUi.CHECKING
    RootState.ACTIVE -> RootStateUi.ACTIVE
    RootState.DENIED -> RootStateUi.DENIED
    RootState.NO_SU -> RootStateUi.NO_SU
}

internal fun PermanentGrant.toUi(): PermanentGrantUi = when (this) {
    PermanentGrant.WRITE_SECURE_SETTINGS -> PermanentGrantUi.WRITE_SECURE_SETTINGS
    PermanentGrant.WRITE_SETTINGS -> PermanentGrantUi.WRITE_SETTINGS
    PermanentGrant.USAGE_STATS -> PermanentGrantUi.USAGE_STATS
    PermanentGrant.DND_ACCESS -> PermanentGrantUi.DND_ACCESS
    PermanentGrant.ACCESSIBILITY -> PermanentGrantUi.ACCESSIBILITY
}

internal fun PermanentGrantUi.toDomain(): PermanentGrant = when (this) {
    PermanentGrantUi.WRITE_SECURE_SETTINGS -> PermanentGrant.WRITE_SECURE_SETTINGS
    PermanentGrantUi.WRITE_SETTINGS -> PermanentGrant.WRITE_SETTINGS
    PermanentGrantUi.USAGE_STATS -> PermanentGrant.USAGE_STATS
    PermanentGrantUi.DND_ACCESS -> PermanentGrant.DND_ACCESS
    PermanentGrantUi.ACCESSIBILITY -> PermanentGrant.ACCESSIBILITY
}

/** Una riga per ogni permesso, nell'ordine fisso dell'enum: Sistema mostra «concesso»/«non concesso». */
internal fun PrivilegedSnapshot.toUi(): PrivilegedUi = PrivilegedUi(
    backend = backend.toUi(),
    rootState = rootState.toUi(),
    grants = PermanentGrant.entries.map { grant ->
        PermanentGrantRow(grant.toUi(), granted = grants[grant] == true)
    },
)

/** Messaggio di fine «Concedi i permessi permanenti»: conta lo stato reale, non gli exit code. */
internal fun GrantReport.message(language: RenderLanguage): String {
    val total = outcomes.size
    if (total == 0) {
        return language.pick("Nothing selected.", "Nessuna voce selezionata.")
    }
    if (outcomes.values.all { it == dev.argus.device.GrantOutcome.UNAVAILABLE }) {
        return language.pick(
            "No privileged backend is available: start Shizuku or turn on root first.",
            "Nessun backend privilegiato disponibile: avvia Shizuku o accendi il root.",
        )
    }
    val granted = grantedNow.size
    val base = language.pick(
        "Permissions granted: $granted of $total.",
        "Permessi concessi: $granted su $total.",
    )
    return if (granted == total) {
        base
    } else {
        base + language.pick(" Check the list in System.", " Controlla l'elenco in Sistema.")
    }
}

internal fun RootState.message(language: RenderLanguage): String? = when (this) {
    RootState.ACTIVE -> language.pick("Root (su) is on.", "Root (su) attivo.")
    RootState.DENIED -> language.pick(
        "su did not give root access. Allow Argus in your root manager and try again.",
        "su non ha dato l'accesso root. Consenti Argus nel gestore root e riprova.",
    )
    RootState.NO_SU -> language.pick("No su found on this phone.", "Su questo telefono non c'è su.")
    RootState.OFF, RootState.CHECKING -> null
}
