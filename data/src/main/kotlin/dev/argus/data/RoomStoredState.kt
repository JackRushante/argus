package dev.argus.data

import androidx.room.withTransaction
import dev.argus.data.backup.BackupState
import dev.argus.data.backup.BackupStateAccess
import dev.argus.data.entities.StoredValueEntity
import dev.argus.data.entities.UserModeEntity
import dev.argus.data.entities.UserModeStateEntity
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ChangeAuthor
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.ModeSwitch
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.StoredValue
import dev.argus.engine.model.StoredValueOps
import dev.argus.engine.model.StoredValuePolicy
import dev.argus.engine.model.StoredWrite
import dev.argus.engine.model.UserModeListIssue
import dev.argus.engine.model.UserModePolicy
import dev.argus.engine.model.UserModeSnapshot
import dev.argus.engine.runtime.StoredValueStore
import dev.argus.engine.runtime.UserModeStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Esito di una correzione fatta dall'utente nella schermata. Vocabolario chiuso: il testo lo scrive la UI. */
enum class StoredEditResult { SAVED, INVALID, MISSING }

/**
 * Valori conservati fra le esecuzioni (0.8), in Room. Ogni `set_stored` è una transazione: lettura del valore
 * corrente, calcolo con [StoredValueOps.plan] e scrittura insieme, così due regole che incrementano lo stesso
 * contatore non perdono un passo.
 */
class RoomStoredValueStore(
    private val db: ArgusDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : StoredValueStore {
    private val dao = db.storedStateDao()

    fun observe(): Flow<List<StoredValue>> = dao.observeValues().map { rows -> rows.mapNotNull(::toModel) }

    suspend fun all(): List<StoredValue> = dao.allValues().mapNotNull(::toModel)

    override suspend fun read(keys: Set<String>): Map<String, StoredValue> {
        if (keys.isEmpty()) return emptyMap()
        return dao.values(keys.toList()).mapNotNull(::toModel).associateBy { it.key }
    }

    override suspend fun apply(
        action: Action.SetStored,
        integrity: IntegrityLabel,
        confidentiality: ConfidentialityLabel,
        author: ChangeAuthor,
        atMillis: Long,
    ): StoredWrite = db.withTransaction {
        val current = dao.value(action.key)?.let(::toModel)
        val write = StoredValueOps.plan(action, current, dao.valueCount(), integrity, confidentiality, author, atMillis)
        when (write) {
            is StoredWrite.Put -> dao.upsertValue(toEntity(write.value))
            is StoredWrite.Remove -> dao.deleteValue(write.key)
            StoredWrite.Unchanged, is StoredWrite.Rejected -> Unit
        }
        write
    }

    /**
     * Correzione dall'app: il valore scritto dall'utente è CLEAN; resta il tipo e la riservatezza del valore
     * (un valore SECRET resta mascherato). [MISSING] se la chiave non c'è più, [INVALID] se il testo non è del tipo.
     */
    suspend fun editByUser(key: String, raw: String): StoredEditResult = db.withTransaction {
        val current = dao.value(key)?.let(::toModel) ?: return@withTransaction StoredEditResult.MISSING
        val text = StoredValuePolicy.canonical(raw, current.type) ?: return@withTransaction StoredEditResult.INVALID
        dao.upsertValue(
            toEntity(
                current.copy(
                    text = text,
                    integrity = IntegrityLabel.CLEAN,
                    writer = ChangeAuthor.User,
                    updatedAtMillis = nowMillis(),
                ),
            ),
        )
        StoredEditResult.SAVED
    }

    suspend fun delete(key: String): Boolean = dao.deleteValue(key) > 0

    /**
     * Ripristino da backup: i valori validi del file sostituiscono quelli con la stessa chiave, gli altri restano.
     * Una chiave nuova oltre [StoredValuePolicy.MAX_KEYS] si scarta. Restituisce quanti valori sono stati scritti.
     */
    suspend fun restore(values: List<StoredValue>): Int = db.withTransaction {
        var count = dao.valueCount()
        var written = 0
        values.filter(::valid).distinctBy { it.key }.forEach { value ->
            val exists = dao.value(value.key) != null
            if (!exists && count >= StoredValuePolicy.MAX_KEYS) return@forEach
            dao.upsertValue(toEntity(value))
            if (!exists) count++
            written++
        }
        written
    }

    private fun valid(value: StoredValue): Boolean =
        StoredValuePolicy.validKey(value.key) && StoredValuePolicy.canonical(value.text, value.type) == value.text

    private fun toEntity(value: StoredValue) = StoredValueEntity(
        storedKey = value.key,
        type = value.type.name,
        storedValue = value.text,
        integrity = value.integrity.name,
        confidentiality = value.confidentiality.name,
        writerRuleId = (value.writer as? ChangeAuthor.Rule)?.id?.value,
        updatedAtMillis = value.updatedAtMillis,
    )

    /** Una riga illeggibile (enum sconosciuto) è un valore assente: mai un default inventato. */
    private fun toModel(row: StoredValueEntity): StoredValue? {
        val type = enumOrNull<StateValueType>(row.type) ?: return null
        return StoredValue(
            key = row.storedKey,
            type = type,
            text = row.storedValue,
            integrity = enumOrNull<IntegrityLabel>(row.integrity) ?: return null,
            confidentiality = enumOrNull<ConfidentialityLabel>(row.confidentiality) ?: return null,
            writer = row.writerRuleId?.let { ChangeAuthor.Rule(AutomationId(it)) } ?: ChangeAuthor.User,
            updatedAtMillis = row.updatedAtMillis,
        ).takeIf { StoredValuePolicy.canonical(it.text, type) == it.text }
    }

    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }
}

/** Esito di una modifica all'elenco delle modalità. [renamedTo] è il nome salvato. */
sealed interface ModeEditResult {
    data class Done(val name: String) : ModeEditResult

    data class Refused(val issue: UserModeListIssue) : ModeEditResult
}

/** Modalità dell'utente e modalità attiva (0.8), in Room. Ogni cambio è una transazione. */
class RoomUserModeStore(
    private val db: ArgusDatabase,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : UserModeStore {
    private val dao = db.storedStateDao()

    fun observe(): Flow<UserModeSnapshot> =
        combine(dao.observeModes(), dao.observeModeState()) { modes, state -> snapshotOf(modes, state) }

    override suspend fun snapshot(): UserModeSnapshot = db.withTransaction {
        snapshotOf(dao.allModes(), dao.modeState())
    }

    override suspend fun activate(mode: String?, author: ChangeAuthor, atMillis: Long): ModeSwitch =
        db.withTransaction {
            val modes = dao.allModes()
            val state = dao.modeState()
            val before = snapshotOf(modes, state)
            val target = if (mode == null) {
                null
            } else {
                modes.firstOrNull { it.nameKey == UserModePolicy.key(mode) } ?: return@withTransaction ModeSwitch.UnknownMode
            }
            if (target?.nameKey == before.active?.let(UserModePolicy::key)) return@withTransaction ModeSwitch.Unchanged
            dao.upsertModeState(
                UserModeStateEntity(
                    activeKey = target?.nameKey,
                    changedByRuleId = (author as? ChangeAuthor.Rule)?.id?.value,
                    changedAtMillis = atMillis,
                ),
            )
            ModeSwitch.Changed(before.active, target?.name)
        }

    suspend fun add(raw: String): ModeEditResult = db.withTransaction {
        val modes = dao.allModes()
        UserModePolicy.checkAdd(modes.map { it.name }, raw)?.let { return@withTransaction ModeEditResult.Refused(it) }
        val name = requireNotNull(UserModePolicy.normalize(raw))
        dao.insertMode(UserModeEntity(UserModePolicy.key(name), name, (modes.maxOfOrNull { it.position } ?: -1) + 1))
        ModeEditResult.Done(name)
    }

    /** Rinomina tenendo posizione e, se era attiva, la modalità attiva. */
    suspend fun rename(from: String, rawTo: String): ModeEditResult = db.withTransaction {
        val modes = dao.allModes()
        UserModePolicy.checkRename(modes.map { it.name }, from, rawTo)?.let { return@withTransaction ModeEditResult.Refused(it) }
        val old = modes.first { it.nameKey == UserModePolicy.key(from) }
        val name = requireNotNull(UserModePolicy.normalize(rawTo))
        dao.deleteMode(old.nameKey)
        dao.insertMode(UserModeEntity(UserModePolicy.key(name), name, old.position))
        val state = dao.modeState()
        if (state?.activeKey == old.nameKey) {
            dao.upsertModeState(state.copy(activeKey = UserModePolicy.key(name)))
        }
        ModeEditResult.Done(name)
    }

    /** Elimina; se era attiva, non resta nessuna modalità attiva (cambio fatto dall'utente). */
    suspend fun delete(name: String): ModeEditResult = db.withTransaction {
        val key = UserModePolicy.key(name)
        val existing = dao.allModes().firstOrNull { it.nameKey == key }
            ?: return@withTransaction ModeEditResult.Refused(UserModeListIssue.UNKNOWN)
        dao.deleteMode(key)
        val state = dao.modeState()
        if (state?.activeKey == key) {
            dao.upsertModeState(UserModeStateEntity(activeKey = null, changedByRuleId = null, changedAtMillis = nowMillis()))
        }
        ModeEditResult.Done(existing.name)
    }

    /**
     * Ripristino da backup: aggiunge in fondo le modalità del file che mancano (nomi validi, senza doppioni, al
     * più [UserModePolicy.MAX_MODES] in tutto); le esistenti restano. [active] diventa la modalità attiva solo
     * se adesso non ce n'è una: un ripristino non cambia una scelta già fatta e non è un evento per le regole.
     * Restituisce quante modalità sono state aggiunte.
     */
    suspend fun restore(modes: List<String>, active: String?): Int = db.withTransaction {
        val current = dao.allModes()
        val names = current.map { it.name }.toMutableList()
        var position = (current.maxOfOrNull { it.position } ?: -1) + 1
        var added = 0
        modes.forEach { raw ->
            if (UserModePolicy.checkAdd(names, raw) != null) return@forEach
            val name = requireNotNull(UserModePolicy.normalize(raw))
            dao.insertMode(UserModeEntity(UserModePolicy.key(name), name, position++))
            names += name
            added++
        }
        val activeKey = active?.let(UserModePolicy::key)?.takeIf { key -> names.any { UserModePolicy.key(it) == key } }
        if (activeKey != null && dao.modeState()?.activeKey == null) {
            dao.upsertModeState(UserModeStateEntity(activeKey = activeKey, changedByRuleId = null, changedAtMillis = nowMillis()))
        }
        added
    }

    private fun snapshotOf(modes: List<UserModeEntity>, state: UserModeStateEntity?): UserModeSnapshot {
        val active = state?.activeKey?.let { key -> modes.firstOrNull { it.nameKey == key }?.name }
        val changedAt = state?.changedAtMillis
        return UserModeSnapshot(
            modes = modes.map { it.name },
            active = active,
            changedBy = when {
                changedAt == null -> null
                state.changedByRuleId != null -> ChangeAuthor.Rule(AutomationId(state.changedByRuleId))
                else -> ChangeAuthor.User
            },
            changedAtMillis = changedAt,
        )
    }
}

/** Modalità e valori conservati nel backup completo e nel suo ripristino (0.8). */
class RoomBackupState(
    private val values: RoomStoredValueStore,
    private val modes: RoomUserModeStore,
) : BackupStateAccess {
    override suspend fun export(): BackupState {
        val snapshot = modes.snapshot()
        return BackupState(snapshot.modes, snapshot.active, values.all())
    }

    override suspend fun restore(state: BackupState) {
        modes.restore(state.modes, state.activeMode)
        values.restore(state.values)
    }
}
