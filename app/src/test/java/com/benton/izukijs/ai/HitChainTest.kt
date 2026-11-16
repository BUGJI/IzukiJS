package com.benton.izukijs.ai

import com.benton.izukijs.controller.NodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HitChainTest {

    @Test
    fun returnsDeepestChildContainingPoint() {
        val root = node("FrameLayout", 0, 0, 100, 100) {
            child("Button", 10, 10, 40, 40, clickable = true) {
                child("Text", 15, 15, 35, 35)
            }
            child("Other", 60, 60, 90, 90, clickable = true)
        }

        val chain = hitChain(root, 20, 20)

        assertEquals(listOf("FrameLayout", "Button", "Text"), chain.map { it.className })
        assertEquals("Button", chain.last { it.clickable }.className)
    }

    @Test
    fun prefersDeepestClickableAncestor() {
        val root = node("FrameLayout", 0, 0, 100, 100) {
            child("Button", 10, 10, 40, 40, clickable = true) {
                child("Text", 15, 15, 35, 35)
            }
        }

        val chain = hitChain(root, 20, 20)

        assertEquals("Text", chain.last().className)
        assertEquals("Button", chain.last { it.clickable }.className)
    }

    @Test
    fun outsideBoundsReturnsEmptyChain() {
        val root = node("FrameLayout", 0, 0, 100, 100)
        assertTrue(hitChain(root, 101, 50).isEmpty())
    }

    private fun node(
        className: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        clickable: Boolean = false,
        children: MutableList<NodeSnapshot>.() -> Unit = {},
    ): NodeSnapshot = NodeSnapshot(
        className = className,
        text = null,
        viewId = null,
        contentDescription = null,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        clickable = clickable,
        editable = false,
        children = mutableListOf<NodeSnapshot>().apply(children),
    )

    private fun MutableList<NodeSnapshot>.child(
        className: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        clickable: Boolean = false,
        children: MutableList<NodeSnapshot>.() -> Unit = {},
    ) {
        add(node(className, left, top, right, bottom, clickable, children))
    }
}
