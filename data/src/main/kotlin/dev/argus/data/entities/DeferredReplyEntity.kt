package dev.argus.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Reply generativa in attesa di consegna manuale (E13). Il testo esiste qui soltanto come
 * ciphertext AES-GCM (chiave Android Keystore non esportabile): il plaintext non tocca mai il
 * database, i log o i backup. La riga muore per consumo, TTL o revoca privacy.
 */
@Entity(
    tableName = "deferred_replies",
    primaryKeys = ["executionId", "actionIndex"],
    foreignKeys = [
        ForeignKey(
            entity = FireClaimEntity::class,
            parentColumns = ["executionId"],
            childColumns = ["executionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("expiresAtMillis")],
)
data class DeferredReplyEntity(
    val executionId: String,
    val actionIndex: Int,
    /** Package WhatsApp trusted d'origine: serve solo ad aprire l'app giusta al tap. */
    val packageName: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val consumedAtMillis: Long?,
    /** Payload cifrato (base64 versione|iv|ciphertext); mai plaintext. */
    val payload: String,
)
