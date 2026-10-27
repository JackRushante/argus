package dev.argus.automation.privileged

import android.content.Context
import android.content.SharedPreferences
import dev.argus.device.GrantOutcome
import dev.argus.device.PermanentGrant
import dev.argus.device.PermanentGrantStatus
import dev.argus.device.PermanentGrantsController
import dev.argus.shizuku.PrivilegedBackend
import dev.argus.shizuku.RootPreference
import dev.argus.shizuku.RootAccess
import dev.argus.shizuku.RootState
import dev.argus.shizuku.ShizukuAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/** Interruttore «Usa root (su)» su SharedPreferences. Spento di default. */
class PrefsRootPreference internal constructor(
    private val preferences: SharedPreferences,
) : RootPreference {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    override fun isEnabled(): Boolean = runCatching { preferences.getBoolean(KEY_ENABLED, false) }
        .getOrDefault(false)

    override fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "argus_root"
        const val KEY_ENABLED = "enabled"
    }
}

/** Fotografia di ciò che Sistema mostra nella sezione «Privilegi». */
data class PrivilegedSnapshot(
    val backend: PrivilegedBackend,
    val rootState: RootState,
    /** Stato REALE di ogni permesso permanente, riletto da Android (mai dedotto dal comando eseguito). */
    val grants: Map<PermanentGrant, Boolean>,
)

/** Esito di «Concedi i permessi permanenti», per voce: comando eseguito e permesso effettivo dopo. */
data class GrantReport(
    val outcomes: Map<PermanentGrant, GrantOutcome>,
    val effective: Map<PermanentGrant, Boolean>,
) {
    val grantedNow: List<PermanentGrant> get() = outcomes.keys.filter { effective[it] == true }
    val notGranted: List<PermanentGrant> get() = outcomes.keys.filter { effective[it] != true }
}

/**
 * Sistema → «Privilegi» (0.5.0): chi fornisce i comandi privilegiati, interruttore root `su` e
 * permessi permanenti. Unico punto che il ViewModel usa; la logica pura sta nei tipi che compone.
 */
class PrivilegedAccessCenter(
    private val shizuku: ShizukuAccess,
    private val root: RootAccess,
    private val grants: PermanentGrantsController,
    private val grantStatus: () -> Map<PermanentGrant, Boolean>,
) {
    private val refreshTick = MutableStateFlow(0)

    /** Ricalcola quando cambiano Shizuku, la sua identità, il root o dopo una concessione. */
    val snapshots: Flow<PrivilegedSnapshot> = combine(
        shizuku.observeStatus(),
        shizuku.observeIdentity(),
        root.observeState(),
        refreshTick,
    ) { status, identity, rootState, _ ->
        PrivilegedSnapshot(
            backend = PrivilegedBackend.resolve(
                status = status,
                identity = identity,
                sui = runCatching(shizuku::isSui).getOrDefault(false),
                rootActive = rootState == RootState.ACTIVE,
            ),
            rootState = rootState,
            grants = grantStatus(),
        )
    }

    /** Rilegge i permessi (per esempio al ritorno dalle impostazioni di sistema). */
    fun refresh() {
        refreshTick.value = refreshTick.value + 1
    }

    suspend fun setRootEnabled(enabled: Boolean): RootState = root.setEnabled(enabled)

    /** Un backend privilegiato risponde ORA. */
    fun backendAvailable(): Boolean = PrivilegedBackend.resolve(
        status = shizuku.status(),
        identity = shizuku.observeIdentity().value,
        sui = runCatching(shizuku::isSui).getOrDefault(false),
        rootActive = root.isActive(),
    ).available

    /**
     * Concede le voci scelte. Senza backend non esegue nulla ([GrantOutcome.UNAVAILABLE]). Dopo i
     * comandi rilegge i permessi: l'esito per l'utente è lo stato reale, non l'exit code.
     */
    suspend fun grant(selected: Set<PermanentGrant>): GrantReport {
        val outcomes = if (selected.isEmpty()) {
            emptyMap()
        } else if (!backendAvailable()) {
            PermanentGrant.entries.filter { it in selected }.associateWith { GrantOutcome.UNAVAILABLE }
        } else {
            grants.grant(selected)
        }
        refresh()
        return GrantReport(outcomes, grantStatus())
    }

    companion object {
        /** Lettura dello stato reale dei permessi per un `Context`. */
        fun statusReader(context: Context): () -> Map<PermanentGrant, Boolean> =
            { PermanentGrantStatus.read(context) }
    }
}
