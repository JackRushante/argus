package dev.argus

import android.location.Address
import android.location.Geocoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.argus.automation.DraftSubmissionResult
import dev.argus.automation.FlowArmResult
import dev.argus.automation.ReconcileReason
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.Transition
import dev.argus.engine.model.Trigger
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Harness per i gate FISICI/RADIO di P2 (chiamata, cavo, ACL Bluetooth, geofence reale).
 *
 * A differenza dei gate `production-path synthetic`, qui l'evento NON viene iniettato: le regole
 * diagnostiche vengono armate e lasciate vive, il processo instrumentation esce, l'app torna
 * cached e l'evento arriva davvero da modem/cavo/radio/framework. Da qui i tre tempi separati:
 *
 *   1. `arm*Gate`     — arma e lascia armato (nessun cleanup: il processo deve poter morire);
 *   2. azione fisica  — la fa Lorenzo;
 *   3. `reportGates`  — legge il journal; `cleanupGates` — rimuove ogni regola del prefisso.
 *
 * Il report stampa SOLO nomi di regola (scelti qui), kind, esito e tipo azione: mai `detail`,
 * numeri, SSID o nomi/address Bluetooth. `cleanupGates` è idempotente e va eseguito comunque,
 * anche dopo un gate fallito, perché queste regole restano armate per costruzione.
 */
@RunWith(AndroidJUnit4::class)
class ArgusPhysicalGateHarness {
    private val services: ArgusApplicationEntryPoint
        get() = EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            ArgusApplicationEntryPoint::class.java,
        )

    @Test
    fun armCallGate(): Unit = runBlocking {
        arm("$GATE_PREFIX chiamata in arrivo", Trigger.PhoneState(event = PhoneEvent.INCOMING_CALL))
        arm("$GATE_PREFIX chiamata terminata", Trigger.PhoneState(event = PhoneEvent.CALL_ENDED))
    }

    @Test
    fun armPowerGate(): Unit = runBlocking {
        arm("$GATE_PREFIX cavo collegato", Trigger.Connectivity(ConnMedium.POWER, ConnState.CONNECTED))
        arm("$GATE_PREFIX cavo scollegato", Trigger.Connectivity(ConnMedium.POWER, ConnState.DISCONNECTED))
    }

    @Test
    fun armBluetoothGate(): Unit = runBlocking {
        arm("$GATE_PREFIX bt connesso", Trigger.Connectivity(ConnMedium.BT, ConnState.CONNECTED))
        arm("$GATE_PREFIX bt disconnesso", Trigger.Connectivity(ConnMedium.BT, ConnState.DISCONNECTED))
    }

    /**
     * Raggio 120 m: sopra la soglia di warning del validator (100 m) e sotto una camminata lunga.
     * `resolveCurrentLocation` congela la posizione corrente AL MOMENTO DELL'ARM: il bordo scatta
     * uscendo da dove ti trovi quando lanci questo metodo, non da dove andrai dopo.
     */
    @Test
    fun armGeofenceGate(): Unit = runBlocking {
        arm(
            "$GATE_PREFIX uscita geofence",
            Trigger.Geofence(
                radiusM = 120.0,
                transition = Transition.EXIT,
                resolveCurrentLocation = true,
            ),
        )
    }

    /**
     * Scenario reale richiesto da Lorenzo: uscita dal lavoro (posizione corrente congelata
     * all'arm) → Wi-Fi off + Bluetooth on + notifica. L'annuncio vocale NON è esprimibile:
     * nessuna azione TTS nel catalogo e Android non espone il TTS via shell (backlog P3).
     */
    @Test
    fun armWorkExitScenario(): Unit = runBlocking {
        arm(
            "$GATE_PREFIX uscito dal lavoro",
            Trigger.Geofence(
                radiusM = 200.0,
                transition = Transition.EXIT,
                resolveCurrentLocation = true,
            ),
            listOf(
                Action.SetWifi(false),
                Action.SetBluetooth(true),
                Action.ShowNotification("Uscito dal geofence lavoro", "Wi-Fi off, Bluetooth on"),
            ),
        )
    }

    /**
     * Arrivo a casa: ENTER su coordinate esplicite, quindi serve una geocodifica. L'indirizzo
     * arriva come argomento di instrumentation e viene risolto DAL DEVICE (stesso backend che
     * usa già Maps): non viene mai scritto nel repo, nel journal o in un log applicativo.
     */
    @Test
    fun armHomeArrivalScenario(): Unit = runBlocking {
        val address = InstrumentationRegistry.getArguments().getString("homeAddress")
        assertTrue("Manca l'argomento homeAddress", !address.isNullOrBlank())
        val point = geocode(requireNotNull(address))
        arm(
            "$GATE_PREFIX arrivato a casa",
            Trigger.Geofence(
                lat = point.first,
                lng = point.second,
                radiusM = 200.0,
                transition = Transition.ENTER,
            ),
            listOf(
                // Il Bluetooth resta acceso: nessuna azione, così la regola non lo ri-tocca.
                Action.SetWifi(true),
                Action.ShowNotification("Entrato nel geofence casa", "Wi-Fi on"),
            ),
        )
    }

    /** Geocodifica on-device. Non stampa né l'indirizzo né le coordinate risolte. */
    private fun geocode(address: String): Pair<Double, Double> {
        val geocoder = Geocoder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            Locale.ITALY,
        )
        assertTrue("Geocoder non disponibile su questo device", Geocoder.isPresent())
        val latch = CountDownLatch(1)
        val found = AtomicReference<Address?>(null)
        geocoder.getFromLocationName(address, 1) { results ->
            found.set(results.firstOrNull())
            latch.countDown()
        }
        assertTrue("Geocodifica in timeout", latch.await(30, TimeUnit.SECONDS))
        val hit = found.get()
        assertTrue("Indirizzo non risolto", hit != null && hit.hasLatitude() && hit.hasLongitude())
        return requireNotNull(hit).latitude to requireNotNull(hit).longitude
    }

    /** Esempio 1 della spec: EXIT → Wi-Fi off + Bluetooth on. Il Wi-Fi sostiene ADB: vedi handoff. */
    @Test
    fun armExample1Gate(): Unit = runBlocking {
        arm(
            "$GATE_PREFIX esempio 1",
            Trigger.Geofence(
                radiusM = 120.0,
                transition = Transition.EXIT,
                resolveCurrentLocation = true,
            ),
            listOf(Action.SetWifi(false), Action.SetBluetooth(true)),
        )
    }

    @Test
    fun reportGates(): Unit = runBlocking {
        val gates = services.automationStore().all().filter { it.name.startsWith(GATE_PREFIX) }
        println("$TAG regole diagnostiche: ${gates.size}")
        gates.forEach { automation ->
            println("$TAG --- ${automation.name} [${automation.status}]")
            val records = services.database().auditDao()
                .observeLogForAutomation(automation.id.value, 20)
                .first()
            if (records.isEmpty()) {
                println("$TAG     nessun evento")
                return@forEach
            }
            records.forEach { record ->
                // `detail` non viene MAI stampato: stamparlo sarebbe la fuga che stiamo cercando.
                // Viene solo asserito che non contenga una sequenza lunga di cifre: numero di
                // telefono (match a suffisso ≥7 cifre) o codice OTP (4-8 cifre).
                assertTrue(
                    "PII sospetta nel campo detail di ${automation.name} (${record.kind})",
                    !DIGIT_RUN.containsMatchIn(record.detail),
                )
                println(
                    "$TAG     ${record.kind} at=${record.atMillis} " +
                        "status=${record.executionStatus} detail-pulito=true",
                )
                record.executionId?.let { executionId ->
                    services.database().executionJournalDao().actions(executionId).forEach {
                        println("$TAG       azione ${it.actionType} -> ${it.outcome}")
                    }
                }
            }
        }
    }

    /**
     * Inventario non distruttivo per l'audit pre-merge: prova che le regole di Lorenzo sono
     * sopravvissute ai gate. Stampa solo conteggi per stato e famiglia di trigger — mai nomi,
     * destinatari o comandi, che sono contenuto personale.
     */
    @Test
    fun reportInventory(): Unit = runBlocking {
        val all = services.automationStore().all()
        println("$TAG inventario totale: ${all.size}")
        all.groupingBy { it.status }.eachCount().forEach { (status, count) ->
            println("$TAG   stato $status: $count")
        }
        all.groupingBy { it.trigger::class.simpleName ?: "?" }.eachCount().forEach { (kind, count) ->
            println("$TAG   trigger $kind: $count")
        }
        assertTrue(
            "Regole diagnostiche ancora presenti: il cleanup non ha funzionato",
            all.none { it.name.startsWith(GATE_PREFIX) },
        )
    }

    @Test
    fun cleanupGates(): Unit = runBlocking {
        val store = services.automationStore()
        val gates = store.all().filter { it.name.startsWith(GATE_PREFIX) }
        gates.forEach {
            println("$TAG rimuovo ${it.name}")
            store.delete(it.id)
        }

        // Room è la source of truth, ma delete() da solo non cancella PendingIntent, sentinella
        // condivisa o geofence già registrati nell'OS. Ogni runtime va riconciliato anche se uno
        // degli altri fallisce, altrimenti il cleanup diagnostico lascia trigger orfani.
        val runtimeFailures = mutableListOf<String>()
        try {
            services.timeAlarmRuntime().reconcile(ReconcileReason.CAPABILITY_CHANGED)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            runtimeFailures += "time:${error::class.java.simpleName}"
        }
        try {
            val report = services.connectivityTriggerRuntime().reconcile()
            if (!report.cleanupSucceeded) runtimeFailures += "connectivity:cleanup_failed"
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            runtimeFailures += "connectivity:${error::class.java.simpleName}"
        }
        try {
            val report = services.geofenceTriggerRuntime()
                .reconcile(recreateOsRegistrations = false)
            if (!report.cleanupSucceeded) runtimeFailures += "geofence:cleanup_failed"
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            runtimeFailures += "geofence:${error::class.java.simpleName}"
        }

        assertTrue(
            "Cleanup gate diagnostici non completato",
            store.all().none { it.name.startsWith(GATE_PREFIX) },
        )
        assertTrue(
            "Cleanup runtime incompleto: ${runtimeFailures.joinToString()}",
            runtimeFailures.isEmpty(),
        )
        println("$TAG cleanup completato")
    }

    private suspend fun arm(
        name: String,
        trigger: Trigger,
        actions: List<Action> = listOf(Action.ShowNotification(name, "gate fisico P2")),
        // Il default 0 di AutomationDraft lascia una regola geofence esposta al flapping del
        // motore di posizione (trovato sul campo il 2026-07-15: due EXIT in 3 minuti, con un
        // ENTER invisibile in mezzo perché una regola solo-EXIT non lo matcha). C2 del design
        // prescrive proprio un cooldown per-regola: qui lo applichiamo a tutti i gate.
        cooldownMs: Long = GATE_COOLDOWN_MS,
    ) {
        val submission = services.approvalFlow().submit(
            CompileResult(
                reply = "diagnostic",
                draft = AutomationDraft(
                    name = name,
                    trigger = trigger,
                    actions = actions,
                    cooldownMs = cooldownMs,
                ),
                metaError = null,
            ),
        )
        assertTrue("Draft non pronto [$name]: $submission", submission is DraftSubmissionResult.Ready)
        val ready = submission as DraftSubmissionResult.Ready
        assertTrue("Draft non armabile [$name]: ${ready.review.draft.issues}", ready.review.canArm)

        val snapshot = ready.review.draft.snapshot
        val arm = services.approvalFlow().arm(snapshot.id, snapshot.revision, snapshot.fingerprint)
        assertTrue("Arm non riuscito [$name]: $arm", arm is FlowArmResult.Armed)
        println("$TAG armata: $name")
    }

    private companion object {
        const val GATE_PREFIX = "Argus GATE"
        const val TAG = "ArgusGate:"

        /**
         * Assorbe il ping-pong ravvicinato senza nascondere un attraversamento vero, che dura
         * molto più di cinque minuti. NON è la soluzione del flapping: mitiga la frequenza, non
         * la causa (le due EXIT osservate distavano 3 minuti e questo cooldown le avrebbe
         * fermate, ma un flap a 6 minuti passerebbe comunque).
         */
        const val GATE_COOLDOWN_MS = 5 * 60 * 1000L

        /** 4+ cifre consecutive: coprono sia un OTP sia il suffisso usato dal match numero. */
        val DIGIT_RUN = Regex("\\d{4,}")
    }
}
