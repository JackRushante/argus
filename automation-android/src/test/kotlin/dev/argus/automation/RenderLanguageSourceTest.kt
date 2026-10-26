package dev.argus.automation

import dev.argus.ui.presentation.RenderLanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class RenderLanguageSourceTest {
    @Test
    fun `refresh follows the system language`() {
        var system = RenderLanguage.EN
        val source = RenderLanguageSource { system }
        assertEquals(RenderLanguage.EN, source.current)

        system = RenderLanguage.IT
        source.refresh()

        assertEquals(RenderLanguage.IT, source.current)
        assertEquals(RenderLanguage.IT, source.language.value)
    }
}
