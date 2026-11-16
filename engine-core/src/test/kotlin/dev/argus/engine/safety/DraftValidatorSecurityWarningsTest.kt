package dev.argus.engine.safety

import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.SettingNamespace
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateQueryPolicy
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.TriggerField
import dev.argus.engine.model.VarBinding
import dev.argus.engine.model.VarType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * D1 (segnaposto non quotabile nel comando shell) e D2 (impostazioni di sicurezza e valori esterni):
 * sono WARNING, mai ERROR. L'arm resta possibile, ma la review lo mostra sopra la fold.
 */
class DraftValidatorSecurityWarningsTest {
    private val validator = DraftValidator(knownTools = setOf("whatsapp_reply", "state.read"))
    private val time = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome")
    private val sms = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED)

    private fun draft(
        actions: List<Action>,
        trigger: Trigger = time,
        vars: List<VarBinding> = emptyList(),
    ) = AutomationDraft(name = "sec", trigger = trigger, actions = actions, vars = vars)

    private fun issues(draft: AutomationDraft) = validator.validate(draft, emptySet())
    private fun warnings(draft: AutomationDraft) =
        issues(draft).filter { it.severity == Severity.WARNING }.map { it.code }
    private fun errors(draft: AutomationDraft) =
        issues(draft).filter { it.severity == Severity.ERROR }.map { it.code }

    private val smsBody = VarBinding.TriggerPayload("body", TriggerField.TEXT, null, ConfidentialityLabel.PRIVATE)
    private val literal = VarBinding.Literal("fixed", "30000", VarType.TEXT, ConfidentialityLabel.PUBLIC)

    // --- D2: chiavi ad alto impatto -------------------------------------------------------------

    @Test
    fun `security sensitive setting keys raise a high impact warning but never an error`() {
        val sensitive = listOf(
            SettingNamespace.SECURE to "enabled_accessibility_services",
            SettingNamespace.SECURE to "accessibility_enabled",
            SettingNamespace.SECURE to "default_input_method",
            SettingNamespace.SECURE to "enabled_input_methods",
            SettingNamespace.SECURE to "install_non_market_apps",
            SettingNamespace.GLOBAL to "adb_enabled",
            SettingNamespace.GLOBAL to "adb_wifi_enabled",
            SettingNamespace.GLOBAL to "development_settings_enabled",
            SettingNamespace.GLOBAL to "package_verifier_enable",
            SettingNamespace.GLOBAL to "verifier_verify_adb_installs",
            SettingNamespace.GLOBAL to "install_non_market_apps",
        )
        for ((namespace, key) in sensitive) {
            val d = draft(listOf(Action.WriteSetting(namespace, key, "1")))
            assertTrue("write_setting_high_impact" in warnings(d), "$namespace $key")
            assertEquals(emptyList(), errors(d), "$namespace $key non deve bloccare l'arm")
        }
    }

    @Test
    fun `other setting keys and the wrong namespace do not raise the high impact warning`() {
        val harmless = listOf(
            SettingNamespace.SYSTEM to "screen_off_timeout",
            SettingNamespace.GLOBAL to "airplane_mode_on",
            // Il namespace conta: adb_enabled vive in global, non in secure.
            SettingNamespace.SECURE to "adb_enabled",
            SettingNamespace.SYSTEM to "adb_enabled",
            SettingNamespace.GLOBAL to "accessibility_enabled",
        )
        for ((namespace, key) in harmless) {
            val d = draft(listOf(Action.WriteSetting(namespace, key, "1")))
            assertFalse("write_setting_high_impact" in warnings(d), "$namespace $key")
        }
    }

    @Test
    fun `high impact warning is found inside control flow too`() {
        val nested = Action.If(
            condition = dev.argus.engine.model.Condition.BooleanLiteral(true),
            then = listOf(Action.WriteSetting(SettingNamespace.GLOBAL, "adb_enabled", "1")),
        )
        assertTrue("write_setting_high_impact" in warnings(draft(listOf(nested))))
    }

    // --- D2: valore guidato da un dato esterno -----------------------------------------------------

    @Test
    fun `a tainted variable in the key or the value raises the external value warning`() {
        val inValue = draft(
            actions = listOf(Action.WriteSetting(SettingNamespace.SYSTEM, "screen_off_timeout", "\${body}")),
            trigger = sms,
            vars = listOf(smsBody),
        )
        assertTrue("write_setting_external_value" in warnings(inValue))
        assertEquals(emptyList(), errors(inValue))

        val inKey = draft(
            actions = listOf(Action.WriteSetting(SettingNamespace.SYSTEM, "\${body}", "1")),
            trigger = sms,
            vars = listOf(smsBody),
        )
        assertTrue("write_setting_external_value" in warnings(inKey))
    }

    @Test
    fun `text read from a parametric state reader counts as external data`() {
        val ssid = VarBinding.State(
            "ssid",
            StateQuery.DumpsysField("wifi", "SSID"),
            StateValueType.TEXT,
            StateQueryPolicy.VERSION,
            ConfidentialityLabel.SECRET,
        )
        val written = draft(
            actions = listOf(Action.WriteSetting(SettingNamespace.GLOBAL, "device_name", "\${ssid}")),
            vars = listOf(ssid),
        )
        assertTrue("write_setting_external_value" in warnings(written))
        val shell = draft(actions = listOf(Action.RunShell("sh -c \"log \${ssid}\"")), vars = listOf(ssid))
        assertTrue("interpolation_shell_reevaluated" in errors(shell))
        // Un numero letto dallo stesso reader resta fidato: niente avvisi sul comando.
        val level = ssid.copy(name = "level", valueType = StateValueType.NUMBER)
        val numeric = draft(actions = listOf(Action.RunShell("[ \${level} -gt 3 ] && sh -c \"log \${level}\"")), vars = listOf(level))
        assertTrue(errors(numeric).none { it.startsWith("interpolation_") }, errors(numeric).toString())
        assertFalse("interpolation_shell_numeric" in warnings(numeric))
    }

    @Test
    fun `captured shell output counts as external data for a setting`() {
        val d = draft(
            actions = listOf(
                Action.RunShell("cat /proc/loadavg", captureAs = "out"),
                Action.WriteSetting(SettingNamespace.SYSTEM, "screen_off_timeout", "\${out}"),
            ),
        )
        assertTrue("write_setting_external_value" in warnings(d))
    }

    @Test
    fun `a clean variable or a literal value does not raise the external value warning`() {
        val clean = draft(
            actions = listOf(Action.WriteSetting(SettingNamespace.SYSTEM, "screen_off_timeout", "\${fixed}")),
            vars = listOf(literal),
        )
        assertFalse("write_setting_external_value" in warnings(clean))
        assertFalse(
            "write_setting_external_value" in
                warnings(draft(listOf(Action.WriteSetting(SettingNamespace.SYSTEM, "screen_off_timeout", "30000")))),
        )
        // Un dato esterno in un altro campo (notifica) non riguarda le impostazioni.
        val notify = draft(
            actions = listOf(Action.ShowNotification("t", "\${body}")),
            trigger = sms,
            vars = listOf(smsBody),
        )
        assertFalse("write_setting_external_value" in warnings(notify))
    }

    @Test
    fun `a sensitive key written from external data raises both warnings`() {
        val d = draft(
            actions = listOf(Action.WriteSetting(SettingNamespace.GLOBAL, "adb_enabled", "\${body}")),
            trigger = sms,
            vars = listOf(smsBody),
        )
        val codes = warnings(d)
        assertTrue("write_setting_high_impact" in codes)
        assertTrue("write_setting_external_value" in codes)
        assertEquals(emptyList(), errors(d))
    }

    // --- D1: contesto non quotabile nel comando shell -----------------------------------------------

    private fun shellDraft(command: String) = draft(
        actions = listOf(
            Action.RunShell("cat /proc/loadavg", captureAs = "out"),
            Action.RunShell(command),
        ),
    )

    @Test
    fun `a tainted variable in a quotable shell position raises no shell context warning`() {
        for (command in listOf("echo \${out}", "echo \"\${out}\"", "echo '\${out}'", "echo a\${out}b")) {
            val d = shellDraft(command)
            assertFalse("interpolation_shell_context" in warnings(d), command)
            assertEquals(emptyList(), errors(d), command)
        }
    }

    @Test
    fun `a tainted variable in an unquotable shell position raises a warning not an error`() {
        for (command in listOf("echo \$(date) \${out}", "echo \\\${out}", "echo `date` \${out}", "cat <<EOF \${out}")) {
            val d = shellDraft(command)
            assertTrue("interpolation_shell_context" in warnings(d), command)
            assertFalse("interpolation_shell_context" in errors(d), command)
        }
    }

    @Test
    fun `a clean variable in an unquotable shell position raises no warning`() {
        val d = draft(
            actions = listOf(Action.RunShell("echo \$(date) \${fixed}")),
            vars = listOf(literal),
        )
        assertFalse("interpolation_shell_context" in warnings(d))
    }

    // --- S2/S3: numeri, posizione del comando, comandi che rileggono --------------------------------

    @Test
    fun `external data in command position or in a reevaluating command is an error`() {
        for (command in listOf("\${out}", "echo hi; \${out} -x", "env \${out}")) {
            val d = shellDraft(command)
            assertTrue("interpolation_shell_command_position" in errors(d), command)
        }
        for (command in listOf("sh -c \"echo \${out}\"", "su -c '\${out}'", "eval echo \${out}", "echo \${out} | sh")) {
            val d = shellDraft(command)
            assertTrue("interpolation_shell_reevaluated" in errors(d), command)
            assertFalse("interpolation_shell_context" in warnings(d), command)
        }
    }

    @Test
    fun `a trusted variable in command position or inside sh -c raises nothing`() {
        val d = draft(
            actions = listOf(Action.RunShell("\${fixed} 5; sh -c \"sleep \${fixed}\"")),
            vars = listOf(literal),
        )
        assertTrue(errors(d).none { it.startsWith("interpolation_shell") }, errors(d).toString())
        assertTrue(warnings(d).none { it.startsWith("interpolation_shell") }, warnings(d).toString())
    }

    @Test
    fun `external data where the shell computes a number raises a whole number warning`() {
        for (command in listOf("[ \${out} -gt 5 ] && echo big", "(( \${out} > 5 ))", "let n=\${out}")) {
            val d = shellDraft(command)
            assertTrue("interpolation_shell_numeric" in warnings(d), command)
            assertTrue(errors(d).none { it.startsWith("interpolation_shell") }, command)
        }
        assertTrue("interpolation_shell_context" in warnings(shellDraft("[ x\${out} -eq 1 ]")))
    }

    @Test
    fun `external data that may be read as an option raises a warning unless a double dash precedes it`() {
        assertTrue("interpolation_shell_option" in warnings(shellDraft("rm \${out}")))
        assertFalse("interpolation_shell_option" in warnings(shellDraft("rm -- \${out}")))
        assertFalse("interpolation_shell_option" in warnings(shellDraft("echo x\${out}")))
    }
}
