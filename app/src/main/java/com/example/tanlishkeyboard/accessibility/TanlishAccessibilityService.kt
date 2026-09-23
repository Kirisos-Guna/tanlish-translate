package com.example.tanlishkeyboard.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.example.tanlishkeyboard.data.AppServices
import com.example.tanlishkeyboard.translate.AndroidClipboard
import com.example.tanlishkeyboard.translate.EditableField
import com.example.tanlishkeyboard.translate.FieldProvider

/**
 * The only part of the app that can see text inside other apps.
 *
 * It reads nothing on its own: the focused field is looked at only when the floating button asks for
 * it. Its second job is telling the app whether a soft keyboard is on screen, which decides whether
 * that button is visible at all — only an accessibility service can see this without being the
 * keyboard itself.
 */
class TanlishAccessibilityService : AccessibilityService(), FieldProvider {

    private val clipboard by lazy { AndroidClipboard(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppServices.get(this).settings.accessibilitySeenAtMillis = System.currentTimeMillis()
        notifyKeyboard()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        notifyKeyboard()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        notifyKeyboard()
        super.onDestroy()
    }

    /**
     * Window changes are the only events this service listens to, and only to answer one question:
     * is a keyboard on screen? Typed text is still never read here.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            -> notifyKeyboard()

            else -> Unit
        }
    }

    override fun onInterrupt() = Unit

    /**
     * Whether a soft keyboard is showing.
     *
     * Measured on the emulator against `dumpsys input_method`'s `mInputShown`: the IME window is in
     * [windows] while the keyboard is up and gone the moment it closes. Its `isActive` and `isFocused`
     * flags stay false throughout, so presence is the signal, not focus.
     *
     * The height check guards the other direction: a keyboard window that has been left behind with no
     * size is not a keyboard on screen.
     */
    fun isKeyboardVisible(): Boolean = runCatching {
        val bounds = Rect()
        windows.any { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@any false
            window.getBoundsInScreen(bounds)
            bounds.height() > 0
        }
    }.getOrDefault(false)

    override fun focusedField(): EditableField? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val editable = focused?.let { node -> if (node.isEditable) node else firstEditable(node, 0, IntArray(1)) }
            ?: firstEditable(root, 0, IntArray(1))
        return editable?.let { AccessibilityField(it, clipboard) }
    }

    private fun firstEditable(node: AccessibilityNodeInfo, depth: Int, visited: IntArray): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH || visited[0]++ > MAX_NODES) return null
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (child.isEditable) return child
            firstEditable(child, depth + 1, visited)?.let { return it }
        }
        return null
    }

    private fun notifyKeyboard() {
        keyboardListener?.invoke()
    }

    companion object {
        private const val MAX_DEPTH = 12
        private const val MAX_NODES = 400

        @Volatile
        private var instance: TanlishAccessibilityService? = null

        @Volatile
        private var keyboardListener: (() -> Unit)? = null

        /** Whether the user has switched this service on, as far as the app can tell. */
        fun isConnected(): Boolean = instance != null

        /**
         * Be notified whenever the keyboard may have appeared or gone, so the listener can re-read
         * [isKeyboardVisibleNow]. Pass null to stop listening.
         */
        fun observeKeyboard(listener: (() -> Unit)?) {
            keyboardListener = listener
        }

        /** Whether a soft keyboard is on screen, or false when this service is switched off. */
        fun isKeyboardVisibleNow(): Boolean =
            runCatching { instance?.isKeyboardVisible() == true }.getOrDefault(false)

        /** The field in whatever app is in front, or null when this service is switched off. */
        fun focusedFieldOrNull(): EditableField? =
            runCatching { instance?.focusedField() }.getOrNull()
    }
}
