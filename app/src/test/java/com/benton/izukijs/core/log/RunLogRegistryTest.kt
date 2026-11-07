package com.benton.izukijs.core.log

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunLogRegistryTest {

    private fun entry(id: Long, level: LogLevel, message: String) =
        LogEntry(id, System.currentTimeMillis(), level, message)

    @Test
    fun recordsEntriesByThreadRunContext() {
        val runId = RunLogRegistry.newRunId()
        RunLogRegistry.begin(runId, "script", "测试")
        RunLogRegistry.enter(runId)
        try {
            RunLogRegistry.record(entry(1, LogLevel.DEBUG, "console output"))
        } finally {
            RunLogRegistry.exit()
        }
        val record = RunLogRegistry.find(runId)
        assertNotNull(record)
        assertEquals(1, record!!.snapshot().size)
    }

    @Test
    fun contextElementKeepsRunIdAcrossThreadSwitch() = runBlocking {
        val runId = RunLogRegistry.newRunId()
        RunLogRegistry.begin(runId, "script", "测试")
        withContext(RunLogRegistry.asContextElement(runId) + Dispatchers.Default) {
            RunLogRegistry.record(entry(1, LogLevel.INFO, "before"))
            // 切到另一个调度器，模拟挂起点恢复后线程变化。
            withContext(Dispatchers.IO) {
                RunLogRegistry.record(entry(2, LogLevel.INFO, "after"))
            }
        }
        val record = RunLogRegistry.find(runId)
        assertNotNull(record)
        assertEquals(listOf("before", "after"), record!!.snapshot().map { it.message })
    }

    @Test
    fun filtersByLevelAndGrep() {
        val runId = RunLogRegistry.newRunId()
        val record = RunLogRegistry.begin(runId, "script", "测试")
        record.append(entry(1, LogLevel.DEBUG, "debug detail"))
        record.append(entry(2, LogLevel.INFO, "PROBE|start"))
        record.append(entry(3, LogLevel.ERROR, "boom"))
        RunLogRegistry.finish(runId, RunStatus.FAILED, "boom")

        val errors = RunLogRegistry.filter(record, 10, LogLevel.WARN, null)
        assertEquals(1, errors.size)
        assertEquals(LogLevel.ERROR, errors.first().level)

        val probe = RunLogRegistry.filter(record, 10, null, "probe")
        assertEquals(1, probe.size)
        assertEquals("PROBE|start", probe.first().message)

        assertEquals(RunStatus.FAILED, record.status)
        assertEquals("boom", record.error)
    }

    @Test
    fun keepsOnlyTailEntries() {
        val runId = RunLogRegistry.newRunId()
        val record = RunLogRegistry.begin(runId, "script", "测试")
        repeat(10) { record.append(entry(it.toLong(), LogLevel.INFO, "line-$it")) }
        val tail = RunLogRegistry.filter(record, 3, null, null)
        assertEquals(listOf("line-7", "line-8", "line-9"), tail.map { it.message })
        assertTrue(record.snapshot().isNotEmpty())
    }
}
