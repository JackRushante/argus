package dev.argus.engine.model

/**
 * Regole chiuse delle azioni privilegiate tipizzate 0.5.1 ([Action.AppControl], [Action.AppPermission]):
 * forma del package, bersagli vietati ed elenco dei permessi runtime ammessi. Le usano il validator
 * (statico), l'interpolatore (forma risolta) e l'esecutore Android (ultima difesa, che aggiunge il
 * launcher in uso, noto solo sul telefono).
 *
 * `pkg` e `permission` sono LETTERALI: nessun `${var}`, perché un valore esterno qui sceglierebbe
 * quale app fermare o quale permesso toccare. La forma del package esclude `$`, `{` e `}`.
 */
object PrivilegedActionPolicy {
    const val ARGUS_PACKAGE = "dev.argus"
    const val MAX_PACKAGE_CHARS = 255

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /**
     * Package di sistema essenziali (lista chiusa) su cui `app_control` è vietato: framework, interfaccia
     * di sistema, telefono, impostazioni e i provider dei servizi Google di base (servizi Play, framework
     * dei servizi, Play Store). Il launcher di default si aggiunge sul telefono (v. esecutore).
     */
    val PROTECTED_SYSTEM_PACKAGES: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.phone",
        "com.android.settings",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.android.vending",
        // Il backend privilegiato stesso: fermarlo o congelarlo toglierebbe ad Argus Shizuku.
        "moe.shizuku.privileged.api",
        // I gestori root: congelarli toglierebbe il backend `su` (KernelSU, Magisk).
        "me.weishu.kernelsu",
        "com.topjohnwu.magisk",
    )

    /** Argus stesso, varianti comprese (`dev.argus.debug`): mai un bersaglio. */
    fun isArgus(pkg: String): Boolean = pkg == ARGUS_PACKAGE || pkg.startsWith("$ARGUS_PACKAGE.")

    fun isValidPackage(pkg: String): Boolean = pkg.length <= MAX_PACKAGE_CHARS && PACKAGE_NAME.matches(pkg)

    /**
     * Esito del controllo di un bersaglio. [launcher] sono i package launcher noti all'esecutore
     * (il validator statico passa l'insieme vuoto).
     */
    fun appControlTarget(pkg: String, launcher: Set<String> = emptySet()): TargetVerdict = when {
        !isValidPackage(pkg) -> TargetVerdict.PACKAGE_INVALID
        isArgus(pkg) || pkg in PROTECTED_SYSTEM_PACKAGES || pkg in launcher -> TargetVerdict.FORBIDDEN
        else -> TargetVerdict.OK
    }

    /** Per i permessi il solo bersaglio vietato è Argus (le altre app non hanno restrizioni qui). */
    fun appPermissionTarget(pkg: String): TargetVerdict = when {
        !isValidPackage(pkg) -> TargetVerdict.PACKAGE_INVALID
        isArgus(pkg) -> TargetVerdict.FORBIDDEN
        else -> TargetVerdict.OK
    }

    enum class TargetVerdict { OK, PACKAGE_INVALID, FORBIDDEN }

    /** Famiglie di permessi che l'utente riconosce (per il testo dell'approvazione). */
    enum class PermissionGroup {
        LOCATION, CAMERA, MICROPHONE, CONTACTS, SMS, PHONE, CALENDAR, STORAGE, NOTIFICATIONS,
        BODY_SENSORS, NEARBY_DEVICES,
    }

    private const val PREFIX = "android.permission."

    /**
     * Elenco CHIUSO dei permessi runtime concedibili/revocabili, per famiglia. Solo `android.permission.*`
     * di livello "dangerous": posizione, fotocamera, microfono, contatti, SMS, telefono, calendario,
     * archiviazione/media, notifiche, sensori del corpo, dispositivi vicini.
     */
    private val GROUPS: Map<PermissionGroup, List<String>> = linkedMapOf(
        PermissionGroup.LOCATION to listOf(
            "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION",
        ),
        PermissionGroup.CAMERA to listOf("CAMERA"),
        PermissionGroup.MICROPHONE to listOf("RECORD_AUDIO"),
        PermissionGroup.CONTACTS to listOf("READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS"),
        PermissionGroup.SMS to listOf(
            "SEND_SMS", "RECEIVE_SMS", "READ_SMS", "RECEIVE_WAP_PUSH", "RECEIVE_MMS",
        ),
        PermissionGroup.PHONE to listOf(
            "READ_PHONE_STATE", "READ_PHONE_NUMBERS", "CALL_PHONE", "ANSWER_PHONE_CALLS",
            "READ_CALL_LOG", "WRITE_CALL_LOG",
        ),
        PermissionGroup.CALENDAR to listOf("READ_CALENDAR", "WRITE_CALENDAR"),
        PermissionGroup.STORAGE to listOf(
            "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO",
            "READ_MEDIA_AUDIO", "READ_MEDIA_VISUAL_USER_SELECTED", "ACCESS_MEDIA_LOCATION",
        ),
        PermissionGroup.NOTIFICATIONS to listOf("POST_NOTIFICATIONS"),
        PermissionGroup.BODY_SENSORS to listOf("BODY_SENSORS", "BODY_SENSORS_BACKGROUND"),
        PermissionGroup.NEARBY_DEVICES to listOf(
            "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN", "BLUETOOTH_ADVERTISE", "NEARBY_WIFI_DEVICES",
            "UWB_RANGING",
        ),
    )

    private val BY_PERMISSION: Map<String, PermissionGroup> = buildMap {
        GROUPS.forEach { (group, names) -> names.forEach { put(PREFIX + it, group) } }
    }

    val RUNTIME_PERMISSIONS: Set<String> = BY_PERMISSION.keys

    fun isRuntimePermission(permission: String): Boolean = permission in BY_PERMISSION

    /** Famiglia del permesso, o null se non è nell'elenco chiuso. */
    fun groupOf(permission: String): PermissionGroup? = BY_PERMISSION[permission]

    /** Forma di un'azione già risolta: stessi controlli del validator, senza il launcher (lo aggiunge l'esecutore). */
    fun isShapeValid(action: Action): Boolean = when (action) {
        is Action.AppControl -> appControlTarget(action.pkg) == TargetVerdict.OK
        is Action.AppPermission ->
            appPermissionTarget(action.pkg) == TargetVerdict.OK && isRuntimePermission(action.permission)
        else -> true
    }
}
