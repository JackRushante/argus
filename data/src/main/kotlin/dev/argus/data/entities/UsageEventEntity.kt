package dev.argus.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Tipo di chiamata LLM misurata. */
enum class UsageEventKind { COMPILE, ACT, ACT_V2 }

/**
 * Esito della chiamata ai fini del budget. [BLOCKED_BUDGET] è pre-call: la chiamata non è
 * partita perché il tetto era già superato, quindi non ha token e il modello può essere ignoto.
 */
enum class UsageEventOutcome { OK, ERROR, BLOCKED_BUDGET }

/**
 * Evento append-only di consumo LLM (S11). Nessun payload, nessun segreto: solo conteggi,
 * provider e modello. Scritto da S13 (MeteredBrain), letto da S14 (UI budget). Retention 35gg.
 *
 * - [providerId] è il `ProviderId.wireName` come stringa: `:data` non dipende da `:brain-android`,
 *   la conversione la fa chi scrive (T2).
 * - [model] è NOT NULL da vincolo di slice; per gli eventi pre-call ([UsageEventOutcome.BLOCKED_BUDGET])
 *   che non risolvono il modello, la convenzione è la stringa `"unknown"` (T9).
 * - [tokensIn]/[tokensOut] null = provider senza usage (es. Hermes pre-S15): semantica "n/d", non 0.
 * - [costMicros] (micro-USD) e [pricingVersion] restano colonne vuote in S11: le popola S13.
 */
@Entity(tableName = "usage_events", indices = [Index("timestampMs")])
data class UsageEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val providerId: String,
    val model: String,
    val kind: UsageEventKind,
    val outcome: UsageEventOutcome,
    val tokensIn: Long?,
    val tokensOut: Long?,
    val costMicros: Long?,
    val pricingVersion: String?,
)
