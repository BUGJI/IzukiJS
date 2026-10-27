package com.benton.izukijs.ui.scripts

import com.benton.izukijs.data.ScriptGroupMode
import com.benton.izukijs.model.ScriptInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ScriptGroupsTest {

    private val daily = script("daily")
    private val game = script("game")

    @Test
    fun ungroupedReturnsSingleGroup() {
        val groups = buildScriptGroups(listOf(daily, game), emptyMap(), ScriptGroupMode.NONE)

        assertEquals(1, groups.size)
        assertEquals(listOf(daily, game), groups.single().scripts)
    }

    @Test
    fun multiTagScriptAppearsInEveryTagGroup() {
        val groups = buildScriptGroups(
            scripts = listOf(daily, game),
            tags = mapOf(
                daily.id to listOf("日常", "游戏"),
                game.id to listOf("游戏"),
            ),
            mode = ScriptGroupMode.TAG,
        )

        assertEquals(listOf("日常", "游戏"), groups.map { it.tag })
        assertEquals(listOf(daily), groups[0].scripts)
        assertEquals(listOf(daily, game), groups[1].scripts)
    }

    @Test
    fun untaggedGroupIsLastAndEmptyGroupsAreHidden() {
        val groups = buildScriptGroups(
            scripts = listOf(daily, game),
            tags = mapOf(daily.id to listOf("日常")),
            mode = ScriptGroupMode.TAG,
        )

        assertEquals(listOf("日常", null), groups.map { it.tag })
        assertNull(groups.last().tag)
        assertEquals(listOf(game), groups.last().scripts)
    }

    @Test
    fun tagGroupsAreSortedCaseInsensitively() {
        val groups = buildScriptGroups(
            scripts = listOf(daily, game),
            tags = mapOf(
                daily.id to listOf("zeta"),
                game.id to listOf("Alpha"),
            ),
            mode = ScriptGroupMode.TAG,
        )

        assertEquals(listOf("Alpha", "zeta"), groups.map { it.tag })
    }

    @Test
    fun emptyScriptsProduceNoTagGroups() {
        val groups = buildScriptGroups(emptyList(), emptyMap(), ScriptGroupMode.TAG)

        assertEquals(emptyList<ScriptGroup>(), groups)
    }

    private fun script(id: String) = ScriptInfo(
        id = id,
        name = id,
        file = File("$id.js"),
        updatedAt = 0L,
    )
}
