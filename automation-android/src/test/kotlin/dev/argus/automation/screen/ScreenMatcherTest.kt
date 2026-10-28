package dev.argus.automation.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Il confronto dei criteri con l'albero: esatto prima del largo, ordine del documento, solo visibili. */
class ScreenMatcherTest {
    @Test
    fun `candidates come in document order, parents before later siblings`() {
        val deepFirst = FakeNode(text = "Voce")
        val second = FakeNode(text = "Voce")
        val root = FakeNode(children = listOf(FakeNode(children = listOf(deepFirst)), second))
        val found = ScreenMatcher.find(root, ScreenQuery(text = "Voce"))
        assertSame(deepFirst, found[0])
        assertSame(second, found[1])
    }

    @Test
    fun `all the criteria given must hold together`() {
        val both = FakeNode(text = "Invia", viewId = "a:id/send")
        val onlyText = FakeNode(text = "Invia", viewId = "a:id/other")
        val root = FakeNode(children = listOf(onlyText, both))
        assertEquals(listOf<ScreenNode>(both), ScreenMatcher.find(root, ScreenQuery(text = "Invia", id = "send")))
    }

    @Test
    fun `an id matches whole or by its part after id slash, never as a fragment`() {
        val node = FakeNode(viewId = "com.example:id/send_button")
        val root = FakeNode(children = listOf(node))
        assertEquals(1, ScreenMatcher.find(root, ScreenQuery(id = "send_button")).size)
        assertEquals(1, ScreenMatcher.find(root, ScreenQuery(id = "com.example:id/send_button")).size)
        assertTrue(ScreenMatcher.find(root, ScreenQuery(id = "send")).isEmpty())
    }

    @Test
    fun `text matching ignores case and edge spaces`() {
        val node = FakeNode(text = "  Invia ")
        assertEquals(1, ScreenMatcher.find(FakeNode(children = listOf(node)), ScreenQuery(text = "INVIA")).size)
    }

    @Test
    fun `a text query reaches the content description only when asked to`() {
        val node = FakeNode(contentDescription = "Cerca")
        val root = FakeNode(children = listOf(node))
        assertTrue(ScreenMatcher.find(root, ScreenQuery(text = "Cerca")).isEmpty())
        assertEquals(1, ScreenMatcher.find(root, ScreenQuery(text = "Cerca", textAlsoDescription = true)).size)
    }

    @Test
    fun `a query with no criterion matches nothing`() {
        assertTrue(ScreenMatcher.find(FakeNode(text = "x"), ScreenQuery()).isEmpty())
    }

    @Test
    fun `clickable ancestors are found only a few levels up`() {
        var chain = FakeNode(text = "profondo")
        val leaf = chain
        repeat(10) { chain = FakeNode(children = listOf(chain)) }
        // Il nodo cliccabile sta 11 livelli sopra la foglia: troppo lontano per essere "il suo pulsante".
        FakeNode(isClickable = true, children = listOf(chain))
        assertNull(ScreenMatcher.clickableFor(leaf))

        val near = FakeNode(isClickable = true, children = listOf(FakeNode(children = listOf(FakeNode(text = "x")))))
        val nearLeaf = near.children.single().children.single()
        assertSame(near, ScreenMatcher.clickableFor(nearLeaf))
    }

    @Test
    fun `the first scrollable skips hidden containers`() {
        val hidden = FakeNode(isScrollable = true, isVisibleToUser = false)
        val shown = FakeNode(isScrollable = true)
        assertSame(shown, ScreenMatcher.firstScrollable(FakeNode(children = listOf(hidden, shown))))
    }
}
