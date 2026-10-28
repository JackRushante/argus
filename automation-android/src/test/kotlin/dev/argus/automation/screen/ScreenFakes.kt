package dev.argus.automation.screen

/** Un nodo finto dell'albero dello schermo, con i tocchi registrati. */
internal class FakeNode(
    override val text: String? = null,
    override val contentDescription: String? = null,
    override val viewId: String? = null,
    override val isClickable: Boolean = false,
    override val isEditable: Boolean = false,
    override val isScrollable: Boolean = false,
    override val isVisibleToUser: Boolean = true,
    children: List<FakeNode> = emptyList(),
    private val clickResult: Boolean = true,
    private val typeResult: Boolean = true,
    private val scrollResult: Boolean = true,
    private val clickThrows: Boolean = false,
) : ScreenNode {
    override val children: List<ScreenNode> = children
    override var parent: ScreenNode? = null

    val clicks = mutableListOf<String>()
    val typed = mutableListOf<String>()
    val scrolls = mutableListOf<Boolean>()

    init {
        children.forEach { it.parent = this }
    }

    override fun click(): Boolean {
        if (clickThrows) throw IllegalStateException("nodo scaduto")
        clicks += text ?: contentDescription ?: viewId ?: "?"
        return clickResult
    }

    override fun setText(text: String): Boolean {
        typed += text
        return typeResult
    }

    override fun scroll(forward: Boolean): Boolean {
        scrolls += forward
        return scrollResult
    }
}

/** Lo schermo finto: albero e app in primo piano possono cambiare nel tempo. */
internal class FakeScreen(
    private val time: () -> Long,
    override var available: Boolean = true,
    var active: () -> String? = { "com.example.app" },
    var tree: (Long) -> ScreenNode? = { null },
    var focus: () -> ScreenNode? = { null },
    var backResult: Boolean = true,
    var homeResult: Boolean = true,
) : ScreenSource {
    val globals = mutableListOf<String>()

    override fun activePackage(): String? = active()
    override fun root(): ScreenNode? = tree(time())
    override fun focusedInput(): ScreenNode? = focus()

    override fun back(): Boolean {
        globals += "back"
        return backResult
    }

    override fun home(): Boolean {
        globals += "home"
        return homeResult
    }
}

internal class FakeSession(
    private val canShow: Boolean = true,
    var stopWhen: () -> Boolean = { false },
) : UiMacroSession {
    var begins = 0
    var ends = 0

    override fun begin(): Boolean {
        begins++
        return canShow
    }

    override fun end() {
        ends++
    }

    override val stopRequested: Boolean get() = stopWhen()
}

/** Un orologio finto: ogni pausa fa avanzare il tempo. */
internal class FakeClock {
    var time = 0L
    val now: () -> Long = { time }
    val pause: suspend (Long) -> Unit = { time += it }
}
