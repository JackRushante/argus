package dev.argus.nav

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
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
 * Registro «già chiesto» su SharedPreferences (B33): un booleano per permesso, nessun dato utente.
 * Il backup è escluso dal manifest, quindi un ripristino non può far credere negato un permesso
 * mai chiesto su un altro telefono.
 */
internal class SharedPreferencesPermissionAskStore(
    private val preferences: SharedPreferences,
) : PermissionAskStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE),
    )

    override fun wasAsked(permission: String): Boolean = preferences.getBoolean(permission, false)

    override fun markAsked(permission: String) {
        preferences.edit().putBoolean(permission, true).apply()
    }

    override fun clearAsked(permission: String) {
        preferences.edit().remove(permission).apply()
    }

    private companion object {
        const val FILE_NAME = "argus_permission_requests"
    }
}

/**
 * Richiesta di permessi runtime che non resta muta dopo due rifiuti (B33): se Android non mostra
 * più la richiesta («negato per sempre») apre le impostazioni dell'app con un messaggio.
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
            store = SharedPreferencesPermissionAskStore(context),
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
