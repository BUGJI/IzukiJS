package com.benton.izukijs.ui.scripts

import com.benton.izukijs.model.ScriptInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class ScriptSelectionTest {

    private val daily = script("daily")
    private val game = script("game")
    private val work = script("work")

    @Test
    fun toggleAddsAndRemoves() {
        val once = toggleSelection(emptySet(), daily.id)
        assertEquals(setOf(daily.id), once)

        val twice = toggleSelection(once, daily.id)
        assertEquals(emptySet<String>(), twice)
    }

    @Test
    fun pruneDropsMissingIds() {
        val selected = setOf(daily.id, game.id, work.id)

        val pruned = pruneSelection(selected, listOf(daily, game))

        assertEquals(setOf(daily.id, game.id), pruned)
    }

    @Test
    fun pruneKeepsSelectionWhenNothingRemoved() {
        val selected = setOf(daily.id)

        assertEquals(selected, pruneSelection(selected, listOf(daily, game)))
    }

    @Test
    fun selectAllVisibleOnlyTouchesVisibleItems() {
        val initial = setOf(work.id)

        val all = toggleSelectAllVisible(initial, listOf(daily, game))

        assertEquals(setOf(work.id, daily.id, game.id), all)
    }

    @Test
    fun togglingAllVisibleOffLeavesHiddenSelection() {
        val all = setOf(daily.id, game.id, work.id)

        val visibleCleared = toggleSelectAllVisible(all, listOf(daily, game))

        assertEquals(setOf(work.id), visibleCleared)
    }

    @Test
    fun emptyVisibleSetIsNoOp() {
        val selected = setOf(daily.id)

        assertEquals(selected, toggleSelectAllVisible(selected, emptyList()))
    }

    private fun script(id: String) = ScriptInfo(
        id = id,
        name = id,
        file = File("$id.js"),
        updatedAt = 0L,
    )
}
