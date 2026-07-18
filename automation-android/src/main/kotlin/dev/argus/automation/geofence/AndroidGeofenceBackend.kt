package dev.argus.automation.geofence

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.argus.automation.foreground.launchReceiverWork
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.Transition
import kotlinx.coroutines.CancellationException

data class GeofenceSignal(
    val automationId: AutomationId,
    val approvalFingerprint: ApprovalFingerprint,
    val transition: Transition,
)

class AndroidGeofenceBackend(context: Context) : GeofenceBackend {
    private val appContext = context.applicationContext
    private val locationManager = requireNotNull(
        appContext.getSystemService(LocationManager::class.java),
    ) { "LocationManager non disponibile" }

    @SuppressLint("MissingPermission")
    override fun register(registration: GeofenceRegistration) {
        check(granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            "fine_location_permission_unavailable"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            check(granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                "background_location_permission_unavailable"
            }
        }
        try {
            locationManager.addProximityAlert(
                registration.latitude,
                registration.longitude,
                registration.radiusM,
                NO_EXPIRATION,
                operation(appContext, registration),
            )
        } catch (error: SecurityException) {
            // Il grant può essere revocato tra il controllo e la chiamata framework.
            throw IllegalStateException("location_permission_revoked", error)
        }
    }

    override fun unregister(automationId: AutomationId) {
        val operation = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            identityIntent(appContext, automationId),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE,
        ) ?: return
        try {
            locationManager.removeProximityAlert(operation)
        } finally {
            operation.cancel()
        }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        internal const val ACTION_GEOFENCE_TRANSITION =
            "dev.argus.automation.action.GEOFENCE_TRANSITION"
        internal const val EXTRA_AUTOMATION_ID = "automation_id"
        internal const val EXTRA_APPROVAL_FINGERPRINT = "approval_fingerprint"
        private const val REQUEST_CODE = 0
        private const val NO_EXPIRATION = -1L

        internal fun identityIntent(context: Context, automationId: AutomationId): Intent =
            Intent(context, GeofenceTransitionReceiver::class.java).apply {
                action = ACTION_GEOFENCE_TRANSITION
                data = Uri.Builder()
                    .scheme("argus")
                    .authority("geofence")
                    .appendPath(automationId.value)
                    .build()
            }

        /**
         * LocationManager aggiunge KEY_PROXIMITY_ENTERING al fill-in Intent e, da target S,
         * rifiuta i PendingIntent immutabili. La destinazione resta però esplicita e interna.
         */
        internal fun operation(
            context: Context,
            registration: GeofenceRegistration,
        ): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            identityIntent(context, registration.automationId).apply {
                putExtra(EXTRA_AUTOMATION_ID, registration.automationId.value)
                putExtra(EXTRA_APPROVAL_FINGERPRINT, registration.approvalFingerprint.value)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        fun parseSignal(intent: Intent?): GeofenceSignal? = runCatching {
            require(intent?.action == ACTION_GEOFENCE_TRANSITION) { "action_invalid" }
            val id = requireNotNull(intent.getStringExtra(EXTRA_AUTOMATION_ID)).trim()
            require(id.isNotEmpty() && id.length <= 256) { "automation_id_invalid" }
            require(intent.data?.scheme == "argus" && intent.data?.host == "geofence") {
                "uri_invalid"
            }
            require(intent.data?.lastPathSegment == id) { "uri_id_mismatch" }
            val fingerprint = ApprovalFingerprint(
                requireNotNull(intent.getStringExtra(EXTRA_APPROVAL_FINGERPRINT)),
            )
            require(intent.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) {
                "transition_missing"
            }
            val transition = if (
                intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
            ) Transition.ENTER else Transition.EXIT
            GeofenceSignal(AutomationId(id), fingerprint, transition)
        }.getOrNull()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface GeofenceIngressEntryPoint {
    fun geofenceEventIngress(): GeofenceEventIngress
}

/** Receiver non esportato: viene attivato soltanto dal PendingIntent posseduto dal framework. */
class GeofenceTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val signal = AndroidGeofenceBackend.parseSignal(intent) ?: return
        val entry = EntryPointAccessors.fromApplication(
            context.applicationContext,
            GeofenceIngressEntryPoint::class.java,
        )
        launchReceiverWork(context, "geofence") {
            try {
                entry.geofenceEventIngress().onTransition(
                    signal.automationId,
                    signal.approvalFingerprint,
                    signal.transition,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "dispatch geofence fallito: ${error::class.java.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "ArgusGeofence"
    }
}
