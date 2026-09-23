package com.example.tanlishkeyboard.accessibility

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.example.tanlishkeyboard.translate.Clipboard
import com.example.tanlishkeyboard.translate.EditableField

/**
 * An [EditableField] backed by a live accessibility node.
 *
 * `ACTION_SET_TEXT` is the clean way to replace the text and most fields honour it, but WebViews and
 * some Samsung editors ignore it, so there is a second attempt that selects everything and pastes
 * over it. When both fail the caller falls back to leaving the translation on the clipboard.
 */
internal class AccessibilityField(
    private val node: AccessibilityNodeInfo,
    private val clipboard: Clipboard,
) : EditableField {

    override val isPassword: Boolean get() = node.isPassword

    override fun read(): String? {
        node.refresh()
        return node.text?.toString()
    }

    override fun replace(text: String): Boolean {
        node.refresh()
        val before = node.text?.toString()
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        if (setText(text, before)) return true
        return paste(text, before)
    }

    private fun setText(text: String, before: String?): Boolean {
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return false
        return changed(text, before)
    }

    private fun paste(text: String, before: String?): Boolean {
        clipboard.write(text)
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, before?.length ?: 0)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return false
        return changed(text, before)
    }

    /**
     * Some editors reformat or autocorrect what was written, so the text does not have to match
     * exactly — it only has to be different from what was there before.
     */
    private fun changed(text: String, before: String?): Boolean {
        node.refresh()
        val after = node.text?.toString()
        return after == text || after != before
    }
}
