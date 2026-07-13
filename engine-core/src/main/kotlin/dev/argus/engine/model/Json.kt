package dev.argus.engine.model
import kotlinx.serialization.json.Json
val ArgusJson: Json = Json {
    classDiscriminator = "type"
    encodeDefaults = true
    ignoreUnknownKeys = true
    prettyPrint = false
}
