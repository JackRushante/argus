package dev.argus.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Valore conservato fra le esecuzioni (0.8). [type], [integrity] e [confidentiality] sono i nomi degli enum
 * di engine-core; [storedValue] è già nella forma canonica del tipo. [writerRuleId] null = l'ha scritto l'utente.
 * Il valore non entra mai nel Log: solo la schermata Modalità lo mostra (mascherato se SECRET).
 */
@Entity(tableName = "stored_values")
data class StoredValueEntity(
    @PrimaryKey val storedKey: String,
    val type: String,
    val storedValue: String,
    val integrity: String,
    val confidentiality: String,
    val writerRuleId: String?,
    val updatedAtMillis: Long,
)

/**
 * Una modalità definita dall'utente (0.8). [nameKey] è il nome senza maiuscole: due modalità non possono
 * differire solo per le maiuscole. [position] è l'ordine dell'elenco (e del ciclo del riquadro).
 */
@Entity(tableName = "user_modes")
data class UserModeEntity(
    @PrimaryKey val nameKey: String,
    val name: String,
    val position: Int,
)

/**
 * La modalità attiva (0.8): una sola riga, [id] = [SINGLETON]. [activeKey] null = nessuna modalità.
 * [changedByRuleId] null con [changedAtMillis] presente = l'ha cambiata l'utente.
 */
@Entity(tableName = "user_mode_state")
data class UserModeStateEntity(
    @PrimaryKey val id: Int = SINGLETON,
    val activeKey: String?,
    val changedByRuleId: String?,
    val changedAtMillis: Long?,
) {
    companion object {
        const val SINGLETON = 0
    }
}
