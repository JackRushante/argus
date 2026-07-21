package dev.argus

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.argus.engine.model.Action
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.InterpolationPolicy
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarType
import dev.argus.engine.model.VarValue
import dev.argus.engine.runtime.ActionResolution
import dev.argus.engine.runtime.ActionPath
import dev.argus.engine.runtime.TaintAwareInterpolator
import dev.argus.engine.runtime.VarScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Regression coverage for Android's ICU regex implementation. */
@RunWith(AndroidJUnit4::class)
class InterpolationPolicyInstrumentedTest {

    @Test
    fun validReferenceParsesOnAndroidRuntime() {
        val result = InterpolationPolicy.parse("Hello \${name}")

        assertEquals(listOf("name"), result.refs)
        assertFalse(result.malformed)
    }

    @Test
    fun taintAwareRenderingUsesAndroidRegexWithoutCrashing() {
        assertEquals("1.while[1].1", ActionPath("1.while[1].1").value)
        val scope = VarScope(
            mapOf(
                "message" to VarValue(
                    "runtime text",
                    VarType.TEXT,
                    IntegrityLabel.TAINTED,
                    ConfidentialityLabel.PRIVATE,
                    setOf(ValueProvenance.SMS),
                ),
            ),
        )

        val result = TaintAwareInterpolator().resolve(
            Action.ShowNotification("Argus", "\${message}"),
            scope,
        )

        assertTrue(result is ActionResolution.Resolved)
        val notification = (result as ActionResolution.Resolved).value.action as Action.ShowNotification
        assertEquals("runtime text", notification.text)
    }
}
