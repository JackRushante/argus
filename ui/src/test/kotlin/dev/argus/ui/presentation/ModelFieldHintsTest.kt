package dev.argus.ui.presentation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelFieldHintsTest {
    @Test
    fun `a preset written with the extra s is flagged`() {
        assertTrue(ModelFieldHints.isMistypedPreset("@presets/mio"))
        assertTrue(ModelFieldHints.isMistypedPreset("  @presets/mio  "))
        assertTrue(ModelFieldHints.isMistypedPreset("@Presets/Mio"))
    }

    @Test
    fun `a correct preset or a normal model is not flagged`() {
        assertFalse(ModelFieldHints.isMistypedPreset("@preset/mio"))
        assertFalse(ModelFieldHints.isMistypedPreset("deepseek/deepseek-v4.1-flash"))
        assertFalse(ModelFieldHints.isMistypedPreset("openai/@presets/x"))
        assertFalse(ModelFieldHints.isMistypedPreset("@presets"))
        assertFalse(ModelFieldHints.isMistypedPreset(""))
        assertFalse(ModelFieldHints.isMistypedPreset(null))
    }
}
