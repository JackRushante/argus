package dev.argus.engine.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.Locale

/**
 * Tasto multimediale di [Action.MediaControl]. Enum CHIUSO, mappato 1:1 su un `KEYCODE_MEDIA_*`
 * (nessun keycode libero). Il wire documentato è maiuscolo; in lettura si accetta anche il minuscolo
 * (i modelli scrivono `play` o `PLAY`), in scrittura resta maiuscolo.
 */
@kotlinx.serialization.Serializable(with = MediaCommandSerializer::class)
enum class MediaCommand { PLAY, PAUSE, PLAY_PAUSE, NEXT, PREVIOUS }

/**
 * Metodo HTTP di [Action.HttpRequest]. Enum CHIUSO (niente PATCH/HEAD/OPTIONS/CONNECT: ogni metodo in
 * più è superficie da giustificare). Wire maiuscolo, lettura tollerante al minuscolo come
 * [MediaCommand].
 */
@kotlinx.serialization.Serializable(with = HttpMethodSerializer::class)
enum class HttpMethod { GET, POST, PUT, DELETE }

/**
 * Operazione di [Action.AppControl]. Enum CHIUSO mappato 1:1 su un comando fisso (`am force-stop`,
 * `pm disable-user`, `pm enable`). Wire maiuscolo, lettura tollerante al minuscolo.
 */
@kotlinx.serialization.Serializable(with = AppControlOpSerializer::class)
enum class AppControlOp { FORCE_STOP, FREEZE, UNFREEZE }

/** Serializer di un enum chiuso: scrive il nome, legge il nome senza badare alle maiuscole. */
abstract class CaseInsensitiveEnumSerializer<E : Enum<E>>(
    private val serialName: String,
    private val values: Array<E>,
) : KSerializer<E> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: E) {
        encoder.encodeString(value.name)
    }

    override fun deserialize(decoder: Decoder): E {
        val raw = decoder.decodeString()
        val wanted = raw.trim().uppercase(Locale.ROOT)
        return values.firstOrNull { it.name == wanted }
            ?: throw IllegalArgumentException("$serialName non valido: $raw")
    }
}

object MediaCommandSerializer : CaseInsensitiveEnumSerializer<MediaCommand>(
    "dev.argus.engine.model.MediaCommand",
    MediaCommand.entries.toTypedArray(),
)

object HttpMethodSerializer : CaseInsensitiveEnumSerializer<HttpMethod>(
    "dev.argus.engine.model.HttpMethod",
    HttpMethod.entries.toTypedArray(),
)

object AppControlOpSerializer : CaseInsensitiveEnumSerializer<AppControlOp>(
    "dev.argus.engine.model.AppControlOp",
    AppControlOp.entries.toTypedArray(),
)
