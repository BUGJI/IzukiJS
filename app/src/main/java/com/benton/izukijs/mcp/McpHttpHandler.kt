package com.benton.izukijs.mcp

import com.benton.izukijs.mcp.net.HttpRequest
import com.benton.izukijs.mcp.net.HttpResponse
import com.benton.izukijs.mcp.protocol.McpProtocolHandler
import com.benton.izukijs.runtime.LogBus
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP 端点的 HTTP 路由：鉴权、会话与转发。
 *
 * - `POST /mcp`：JSON-RPC（Streamable HTTP 的 JSON 响应模式），带 `Mcp-Session-Id` 会话头。
 * - `GET /mcp`：返回 405，表示不提供服务器主动推送流。
 * - `DELETE /mcp`：结束会话。
 * - `GET /health`：健康检查（无需鉴权，不含敏感信息）。
 */
class McpHttpHandler(
    private val protocol: McpProtocolHandler,
    private val token: String,
    private val logBus: LogBus,
) {

    // 会话 id -> 最近访问时间，用于空闲过期与容量控制，避免长期运行下无界增长。
    private val sessions = ConcurrentHashMap<String, Long>()

    val sessionCount: Int get() = sessions.size

    fun handle(request: HttpRequest): HttpResponse = when (request.path) {
        "/health" -> health()
        "/mcp" -> mcp(request)
        else -> HttpResponse.text(404, "Not Found")
    }

    private fun health(): HttpResponse {
        val body = JSONObject()
            .put("status", "ok")
            .put("server", McpProtocolHandler.SERVER_NAME)
            .put("sessions", sessions.size)
            .toString()
        return HttpResponse.json(body)
    }

    private fun mcp(request: HttpRequest): HttpResponse {
        if (!authorized(request)) {
            logBus.warn("MCP 拒绝未授权请求（${request.method}）")
            return HttpResponse.text(401, "Unauthorized")
        }
        pruneSessions()
        return when (request.method) {
            "POST" -> post(request)
            "DELETE" -> {
                request.header("mcp-session-id")?.let { sessions.remove(it) }
                HttpResponse.empty(200)
            }
            // 不提供服务器主动推送流，符合规范允许的 405 语义。
            "GET" -> HttpResponse.text(405, "Method Not Allowed")
            else -> HttpResponse.text(405, "Method Not Allowed")
        }
    }

    private fun post(request: HttpRequest): HttpResponse {
        val incoming = request.header("mcp-session-id")
        val sessionId = when {
            incoming == null -> UUID.randomUUID().toString()
            sessions.containsKey(incoming) -> incoming
            else -> return HttpResponse.text(404, "Session not found")
        }
        sessions[sessionId] = System.currentTimeMillis()
        // 容量保护：极端情况下清理最旧的会话。
        if (sessions.size > MAX_SESSIONS) {
            sessions.entries.sortedBy { it.value }.take(sessions.size - MAX_SESSIONS)
                .forEach { sessions.remove(it.key) }
        }

        val bodyText = String(request.body, Charsets.UTF_8)
        val responseText = protocol.handle(bodyText)
        val sessionHeader = mapOf("Mcp-Session-Id" to sessionId)
        return if (responseText == null) {
            // 纯通知：返回 202，无正文。
            HttpResponse.empty(202, sessionHeader)
        } else {
            HttpResponse(
                status = 200,
                body = responseText.toByteArray(Charsets.UTF_8),
                contentType = "application/json; charset=utf-8",
                headers = sessionHeader,
            )
        }
    }

    /** 清理超过空闲时限的会话，避免长期运行下会话表无界增长。 */
    private fun pruneSessions() {
        val cutoff = System.currentTimeMillis() - SESSION_TTL_MS
        sessions.entries.removeIf { it.value < cutoff }
    }

    /** 常量时间比较 Bearer 令牌，避免时序侧信道。 */
    private fun authorized(request: HttpRequest): Boolean {
        if (token.isBlank()) return false
        val header = request.header("authorization") ?: return false
        if (!header.startsWith(BEARER, ignoreCase = true)) return false
        val provided = header.substring(BEARER.length).trim()
        return MessageDigest.isEqual(provided.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))
    }

    companion object {
        /** MCP 端点路径。 */
        const val ENDPOINT = "/mcp"

        private const val BEARER = "Bearer "

        /** 会话空闲过期时长。 */
        private const val SESSION_TTL_MS = 10 * 60 * 1000L

        /** 会话表容量上限。 */
        private const val MAX_SESSIONS = 256
    }
}
