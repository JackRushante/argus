package dev.argus.nav

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.argus.R

/**
 * Stato di rifiuto per permesso su SharedPreferences (B33): il nome dello stato sotto la chiave del
 * permesso, nessun dato utente; [PermissionState.NONE] non si scrive. Il backup è escluso dal
 * manifest, quindi un ripristino non può far credere negato un permesso mai rifiutato su un altro
 * telefono.
 */
internal class SharedPreferencesPermissionStateStore(
    private val preferences: SharedPreferences,
) : PermissionStateStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
    )

    override fun state(permission: String): PermissionState =
        runCatching { preferences.getString(permission, null) }.getOrNull()
            ?.let { name -> PermissionState.entries.firstOrNull { it.name == name } }
            ?: PermissionState.NONE

    override fun setState(permission: String, state: PermissionState) {
        preferences.edit().apply {
            if (state == PermissionState.NONE) remove(permission) else putString(permission, state.name)
        }.apply()
    }

    private companion object {
        const val FILE_NAME = "argus_permission_requests"
    }
}

/**
 * Richiesta di permessi runtime che non resta muta quando Android non mostra più il dialogo
 * («negato per sempre», B33). La logica sta in [PermissionRequestCoordinator]: un tocco su
 * «Concedi» apre le impostazioni dell'app (con un messaggio) solo se il permesso è già
 * [PermissionState.PERMANENT]; un rifiuto che torna subito, senza dialogo, lo è già e porta alle
 * impostazioni all'istante; il secondo «Non consentire» esplicito mostra solo il messaggio e le
 * impostazioni si aprono al tocco dopo. Il primo rifiuto, o un dialogo chiuso toccando fuori, non è mai permanente.
 *
 * @param onResult chiamato quando la richiesta finisce (o non serviva): l'host rilegge lo stato.
 * @param openAppSettings apre `ACTION_APPLICATION_DETAILS_SETTINGS` dell'app.
 * @param showMessage mostra il messaggio «Abilita il permesso dalle impostazioni dell'app».
 */
@Composable
internal fun rememberPermissionRequester(
    onResult: () -> Unit,
    openAppSettings: () -> Unit,
    showMessage: (String) -> Unit,
): (Array<String>) -> Unit {
    val context = LocalContext.current
    val settingsMessage = stringResource(R.string.permission_enable_in_app_settings)
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOpenAppSettings by rememberUpdatedState(openAppSettings)
    val currentShowMessage by rememberUpdatedState(showMessage)
    val currentSettingsMessage by rememberUpdatedState(settingsMessage)
    // Il launcher serve al coordinatore e il coordinatore al callback del launcher: si chiude il giro qui.
    val coordinatorRef = remember { arrayOfNulls<PermissionRequestCoordinator>(1) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        coordinatorRef[0]?.onResult(result)
        currentOnResult()
    }
    val coordinator = remember(launcher) {
        PermissionRequestCoordinator(
            store = SharedPreferencesPermissionStateStore(context),
            isGranted = { permission ->
                ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
            },
            shouldShowRationale = { permission ->
                context.findActivity()?.let {
                    ActivityCompat.shouldShowRequestPermissionRationale(it, permission)
                } ?: false
            },
            launch = { permissions -> launcher.launch(permissions) },
            openAppSettings = { currentOpenAppSettings() },
            notifySettingsNeeded = { currentShowMessage(currentSettingsMessage) },
            nowMillis = SystemClock::elapsedRealtime,
        ).also { coordinatorRef[0] = it }
    }
    return remember<(Array<String>) -> Unit>(coordinator) {
        { permissions ->
            // Tutto già concesso: nessuna richiesta, ma l'host deve comunque rileggere lo stato.
            if (!coordinator.request(permissions)) currentOnResult()
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
