package dev.argus

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.argus.engine.model.InterpolationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
