package dev.argus.ui.screens

import dev.argus.ui.model.TemplateCardUi
import dev.argus.ui.model.TemplateCategoryUi
import dev.argus.ui.model.TemplateGalleryState
import kotlin.test.Test
import kotlin.test.assertEquals

class TemplateGalleryLogicTest {
    private fun card(id: String, category: TemplateCategoryUi) =
        TemplateCardUi(id, category, id, "descrizione", "time", isGenerative = false, needs = emptyList())

    private val cards = listOf(
        card("a", TemplateCategoryUi.HOME),
        card("b", TemplateCategoryUi.BATTERY),
        card("c", TemplateCategoryUi.HOME),
    )

    @Test
    fun `all templates show without a filter, only the category with one`() {
        assertEquals(listOf("a", "b", "c"), visibleTemplates(TemplateGalleryState(cards)).map { it.id })
        assertEquals(
            listOf("a", "c"),
            visibleTemplates(TemplateGalleryState(cards, filter = TemplateCategoryUi.HOME)).map { it.id },
        )
        assertEquals(emptyList(), visibleTemplates(TemplateGalleryState(cards, filter = TemplateCategoryUi.WORK)))
    }

    @Test
    fun `the chips offer all, then only the categories that have templates, in order`() {
        assertEquals(
            listOf(null, TemplateCategoryUi.HOME, TemplateCategoryUi.BATTERY),
            templateFilters(TemplateGalleryState(cards)),
        )
        assertEquals(listOf<TemplateCategoryUi?>(null), templateFilters(TemplateGalleryState()))
    }
}
