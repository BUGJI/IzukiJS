package com.benton.izukijs.runtime.api

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.JavascriptInterface
import com.benton.izukijs.controller.accessibility.AccessibilityController
import com.benton.izukijs.controller.accessibility.releaseNode

/**
 * 控件选择 API。挂在全局 `selector` 命名空间。
 *
 * 出于稳定性考虑，这里全部返回原始类型（Boolean/String/Int），避免 JS 与 Java 对象互转。
 * 每个从无障碍服务获取的节点都会在返回前回收，防止节点池泄漏。
 */
class SelectorApi(
    private val controllerProvider: () -> AccessibilityController?,
) {

    @JavascriptInterface
    fun existsById(viewId: String): Boolean = findById(viewId).exists()

    @JavascriptInterface
    fun existsByText(text: String): Boolean = findByText(text).exists()

    @JavascriptInterface
    fun existsByDesc(desc: String): Boolean = findByDesc(desc).exists()

    @JavascriptInterface
    fun countByText(text: String): Int {
        val nodes = controller()?.findAll(AccessibilityController.NodeCriteria(text = text)) ?: return 0
        return try {
            nodes.size
        } finally {
            nodes.forEach { it.releaseNode() }
        }
    }

    @JavascriptInterface
    fun clickById(viewId: String): Boolean = clickNode(findById(viewId))

    @JavascriptInterface
    fun clickByText(text: String): Boolean = clickNode(findByText(text))

    @JavascriptInterface
    fun clickByDesc(desc: String): Boolean = clickNode(findByDesc(desc))

    @JavascriptInterface
    fun longClickById(viewId: String): Boolean = longClickNode(findById(viewId))

    @JavascriptInterface
    fun longClickByText(text: String): Boolean = longClickNode(findByText(text))

    @JavascriptInterface
    fun setTextById(viewId: String, text: String): Boolean {
        val node = findById(viewId) ?: return false
        return try {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally {
            node.releaseNode()
        }
    }

    @JavascriptInterface
    fun getTextById(viewId: String): String? {
        val node = findById(viewId) ?: return null
        return try {
            node.text?.toString()
        } finally {
            node.releaseNode()
        }
    }

    @JavascriptInterface
    fun getTextByText(text: String): String? {
        val node = findByText(text) ?: return null
        return try {
            node.text?.toString()
        } finally {
            node.releaseNode()
        }
    }

    @JavascriptInterface
    fun boundsById(viewId: String): String? {
        val node = findById(viewId) ?: return null
        return try {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            "${rect.left},${rect.top},${rect.right},${rect.bottom}"
        } finally {
            node.releaseNode()
        }
    }

    private fun controller(): AccessibilityController? = controllerProvider()

    private fun findById(viewId: String) =
        controller()?.findFirst(AccessibilityController.NodeCriteria(viewId = viewId), 0)

    private fun findByText(text: String) =
        controller()?.findFirst(AccessibilityController.NodeCriteria(text = text), 0)

    private fun findByDesc(desc: String) =
        controller()?.findFirst(AccessibilityController.NodeCriteria(desc = desc), 0)

    private fun AccessibilityNodeInfo?.exists(): Boolean {
        this ?: return false
        releaseNode()
        return true
    }

    private fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return try {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                true
            } else {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                controller()?.click(rect.exactCenterX(), rect.exactCenterY()) ?: false
            }
        } finally {
            node.releaseNode()
        }
    }

    private fun longClickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        return try {
            if (node.isLongClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                true
            } else {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                controller()?.longClick(
                    rect.exactCenterX(),
                    rect.exactCenterY(),
                    DEFAULT_LONG_CLICK_MS,
                ) ?: false
            }
        } finally {
            node.releaseNode()
        }
    }

    private companion object {
        const val DEFAULT_LONG_CLICK_MS = 700L
    }
}
