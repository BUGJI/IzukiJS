package com.benton.izukijs.mcp.tools

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 一次异步工具调用的状态。 */
enum class OperationStatus { RUNNING, DONE, FAILED }

/** 一次异步工具调用的快照。 */
data class OperationState(
    val id: String,
    val tool: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val status: OperationStatus,
    val result: McpToolResult? = null,
    val error: String? = null,
) {
    val elapsedMs: Long get() = (finishedAt ?: System.currentTimeMillis()) - startedAt
}

/**
 * 异步操作登记表。
 *
 * MCP 工具在单线程 [java.util.concurrent.ExecutorService] 上串行执行设备操作。
 * 若一次调用（如复杂手势 / OCR）耗时超过同步等待窗口，服务器会先返回
 * `{status:"running", request_id}`，把控制权交还给客户端；客户端随后可用
 * `get_result` 回查。这样「超时」不再与「执行失败」混淆——超时只代表结果未知，
 * 不代表动作没做。
 */
class OperationRegistry(
    private val maxEntries: Int = MAX_ENTRIES,
    private val ttlMs: Long = TTL_MS,
) {

    private val ops = ConcurrentHashMap<String, OperationState>()

    fun create(tool: String): String {
        val id = UUID.randomUUID().toString()
        ops[id] = OperationState(
            id = id,
            tool = tool,
            startedAt = System.currentTimeMillis(),
            status = OperationStatus.RUNNING,
        )
        prune()
        return id
    }

    fun succeed(id: String, result: McpToolResult) {
        ops.computeIfPresent(id) { _, state ->
            state.copy(status = OperationStatus.DONE, result = result, finishedAt = System.currentTimeMillis())
        }
    }

    fun fail(id: String, message: String) {
        ops.computeIfPresent(id) { _, state ->
            state.copy(status = OperationStatus.FAILED, error = message, finishedAt = System.currentTimeMillis())
        }
    }

    fun get(id: String): OperationState? = ops[id]

    fun list(): List<OperationState> = ops.values.sortedByDescending { it.startedAt }

    fun clear() = ops.clear()

    private fun prune() {
        val cutoff = System.currentTimeMillis() - ttlMs
        ops.entries.removeIf { it.value.finishedAt?.let { f -> f < cutoff } == true }
        if (ops.size <= maxEntries) return
        // 超限时优先清理最旧的已完成项，仍在运行的保留。
        ops.values
            .filter { it.status != OperationStatus.RUNNING }
            .sortedBy { it.startedAt }
            .take(ops.size - maxEntries)
            .forEach { ops.remove(it.id) }
    }

    private companion object {
        const val MAX_ENTRIES = 64
        const val TTL_MS = 10 * 60 * 1000L
    }
}
