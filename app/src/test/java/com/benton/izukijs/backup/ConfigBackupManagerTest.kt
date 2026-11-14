package com.benton.izukijs.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConfigBackupManagerTest {

    @Test
    fun rejectsNewerBackupVersion() {
        val section = FakeSection("a", JSONObject().put("value", 1))
        val manager = ConfigBackupManager(listOf(section))

        try {
            manager.importJson(JSONObject().put("version", 99).toString())
            fail("expected newer backup to be rejected")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
        assertEquals(1, section.value.optInt("value"))
    }

    @Test
    fun rollsBackSectionsWhenLaterSectionFails() {
        val first = FakeSection("a", JSONObject().put("value", 1))
        val broken = FakeSection("b", JSONObject().put("value", 2), failOnImport = true)
        val manager = ConfigBackupManager(listOf(first, broken))

        try {
            manager.importJson(
                JSONObject()
                    .put("version", 1)
                    .put("a", JSONObject().put("value", 9))
                    .put("b", JSONObject().put("value", 8))
                    .toString(),
            )
            fail("expected import failure")
        } catch (expected: IllegalStateException) {
            // expected
        }
        assertEquals(1, first.value.optInt("value"))
        assertEquals(2, broken.value.optInt("value"))
    }

    @Test
    fun exportsVersionSectionsAndTimestamp() {
        val manager = ConfigBackupManager(
            listOf(
                FakeSection("a", JSONObject().put("value", 1)),
                FakeSection("b", JSONObject().put("value", 2)),
            ),
        )

        val root = JSONObject(manager.exportJson())

        assertEquals(1, root.getInt("version"))
        assertTrue(root.has("exportedAt"))
        assertEquals(1, root.getJSONObject("a").getInt("value"))
        assertEquals(2, root.getJSONObject("b").getInt("value"))
    }

    @Test
    fun importsOlderVersionAndIgnoresUnknownSections() {
        val section = FakeSection("a", JSONObject().put("value", 1))
        var applied = 0
        val manager = ConfigBackupManager(listOf(section), onApplied = { applied++ })

        manager.importJson(
            JSONObject()
                .put("version", 0)
                .put("a", JSONObject().put("value", 9))
                .put("unknown", JSONObject().put("value", 7))
                .toString(),
        )

        assertEquals(9, section.value.optInt("value"))
        assertEquals(1, applied)
    }

    @Test
    fun missingSectionKeepsCurrentValue() {
        val section = FakeSection("a", JSONObject().put("value", 1))
        val manager = ConfigBackupManager(listOf(section))

        manager.importJson(JSONObject().put("version", 1).toString())

        assertEquals(1, section.value.optInt("value"))
    }

    @Test
    fun resetAllResetsSectionsAndResetOnlyAndNotifiesOnce() {
        val section = FakeSection("a", JSONObject().put("value", 1))
        var resetOnly = 0
        var applied = 0
        val manager = ConfigBackupManager(
            sections = listOf(section),
            resetOnly = listOf { resetOnly++ },
            onApplied = { applied++ },
        )

        manager.resetAll()

        assertEquals(0, section.value.length())
        assertEquals(1, resetOnly)
        assertEquals(1, applied)
    }

    @Test
    fun resetAllRollsBackSectionsWhenResetOnlyFails() {
        val section = FakeSection("a", JSONObject().put("value", 1))
        val manager = ConfigBackupManager(
            sections = listOf(section),
            resetOnly = listOf { error("reset-only failed") },
        )

        try {
            manager.resetAll()
            fail("expected reset failure")
        } catch (expected: IllegalStateException) {
            // expected
        }

        assertEquals(1, section.value.optInt("value"))
    }

    private class FakeSection(
        override val key: String,
        var value: JSONObject,
        private val failOnImport: Boolean = false,
    ) : ConfigSection {

        override fun export(): JSONObject = JSONObject(value.toString())

        override fun import(value: JSONObject) {
            if (failOnImport) error("broken section")
            this.value = JSONObject(value.toString())
        }

        override fun reset() {
            value = JSONObject()
        }
    }
}
