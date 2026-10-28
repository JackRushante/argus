package dev.argus.automation.screen

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Nodo reale: un guscio sottile su [AccessibilityNodeInfo]. I figli e il genitore si leggono solo
 * quando l'esecutore li chiede (un albero grande non si copia tutto). Nessun contenuto viene
 * conservato oltre la vita del nodo.
 */
internal class AccessibilityScreenNode(private val info: AccessibilityNodeInfo) : ScreenNode {
    override val text: String? get() = info.text?.toString()
    override val contentDescription: String? get() = info.contentDescription?.toString()
    override val viewId: String? get() = info.viewIdResourceName
    override val isClickable: Boolean get() = info.isClickable && info.isEnabled
    override val isEditable: Boolean get() = info.isEditable
    override val isScrollable: Boolean get() = info.isScrollable
    override val isVisibleToUser: Boolean get() = info.isVisibleToUser

    override val children: List<ScreenNode> by lazy {
        (0 until info.childCount).mapNotNull { index ->
            info.getChild(index)?.let(::AccessibilityScreenNode)
        }
    }

    override val parent: ScreenNode? get() = info.parent?.let(::AccessibilityScreenNode)

    override fun click(): Boolean = info.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    override fun setText(text: String): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return info.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    override fun scroll(forward: Boolean): Boolean = info.performAction(
        if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
    )
}

/**
 * Lo schermo, letto e toccato dal servizio di Accessibilità di Argus. Ogni lettura chiede l'albero
 * della finestra attiva in quel momento ([AccessibilityService.getRootInActiveWindow]); senza
 * macro in corso nessuno chiama questa classe, quindi non si legge niente.
 */
internal class AccessibilityScreenSource(
    private val service: () -> AccessibilityService? = { ArgusAccessibilityService.current },
) : ScreenSource {
    override val available: Boolean get() = service() != null

    override fun activePackage(): String? = runCatching {
        service()?.rootInActiveWindow?.packageName?.toString()
    }.getOrNull()

    override fun root(): ScreenNode? = runCatching {
        service()?.rootInActiveWindow?.let(::AccessibilityScreenNode)
    }.getOrNull()

    override fun focusedInput(): ScreenNode? = runCatching {
        service()?.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let(::AccessibilityScreenNode)
    }.getOrNull()

    override fun back(): Boolean =
        service()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true

    override fun home(): Boolean =
        service()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) == true
}
