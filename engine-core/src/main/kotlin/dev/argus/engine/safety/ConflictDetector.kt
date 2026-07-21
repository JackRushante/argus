package dev.argus.engine.safety
import dev.argus.engine.model.*

data class ConflictWarning(val targetKey: String, val automationIds: List<AutomationId>, val message: String)

/** Euristica volutamente semplice (spec §13/C1): azioni sullo STESSO target con valori opposti,
 *  MA con soppressione delle coppie complementari legittime (enter/exit, connected/disconnected).
 *  NON è analisi statica completa dello spazio-trigger (indecidibile). Serve a segnalare, non a bloccare. */
class ConflictDetector {
    private data class Setting(val key: String, val value: String)
    private data class Entry(val auto: Automation, val setting: Setting)

    private fun setting(a: Action): Setting? = when (a) {
        is Action.SetWifi -> Setting("wifi", a.on.toString())
        is Action.SetBluetooth -> Setting("bluetooth", a.on.toString())
        is Action.SetMobileData -> Setting("mobile_data", a.on.toString())
        is Action.SetDnd -> Setting("dnd", a.mode.name)
        is Action.SetRinger -> Setting("ringer", a.mode)
        else -> null
    }

    /** Coppie di trigger complementari = pattern legittimo, non conflitto. */
    private fun complementary(t1: Trigger, t2: Trigger): Boolean = when {
        t1 is Trigger.Geofence && t2 is Trigger.Geofence ->
            t1.lat == t2.lat && t1.lng == t2.lng && t1.transition != t2.transition
        t1 is Trigger.Connectivity && t2 is Trigger.Connectivity ->
            t1.medium == t2.medium && t1.match == t2.match && t1.state != t2.state
        else -> false
    }

    fun detect(automations: List<Automation>): List<ConflictWarning> {
        val entries = automations.flatMap { a -> a.actions.mapNotNull { act -> setting(act)?.let { Entry(a, it) } } }
        val warnings = mutableListOf<ConflictWarning>()
        for (i in entries.indices) for (j in i + 1 until entries.size) {
            val e1 = entries[i]; val e2 = entries[j]
            if (e1.auto.id == e2.auto.id) continue
            if (e1.setting.key != e2.setting.key || e1.setting.value == e2.setting.value) continue
            if (complementary(e1.auto.trigger, e2.auto.trigger)) continue
            warnings += ConflictWarning(e1.setting.key, listOf(e1.auto.id, e2.auto.id),
                "'${e1.auto.name}' e '${e2.auto.name}' impostano '${e1.setting.key}' a valori opposti " +
                    "(${e1.setting.value} vs ${e2.setting.value}) su trigger potenzialmente sovrapposti")
        }
        return warnings.distinct()
    }
}
