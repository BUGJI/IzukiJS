package com.benton.izukijs.mcp.protocol

import com.benton.izukijs.mcp.resources.ResourceContent
import com.benton.izukijs.mcp.tools.McpTool
import com.benton.izukijs.mcp.tools.McpToolResult
import com.benton.izukijs.mcp.tools.OperationRegistry
import com.benton.izukijs.mcp.tools.OperationStatus
import com.benton.izukijs.testing.FakeAuditSink
import com.benton.izukijs.testing.FakeClock
import com.benton.izukijs.testing.FakeMcpCatalog
import com.benton.izukijs.testing.FakeMcpLogSink
import com.benton.izukijs.testing.FakeMcpResources
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class McpProtocolHandlerTest {

    private lateinit var dispatcher: ExecutorService
    private val audit = FakeAuditSink()
    private val logs = FakeMcpLogSink()
    private val clock = FakeClock()
    private val operations = OperationRegistry(now = clock::now)
    private val slowGate = CountDownLatch(1)

    private val syncTool = McpTool("sync", "同步工具", JSONObject(), synchronous = true) {
        McpToolResult.text("""{"ok":true}""")
    }
    private val asyncTool = McpTool("async", "异步工具", JSONObject()) {
        McpToolResult.text("""{"ok":true}""")
    }
    private val plainTool = McpTool("plain", "纯文本工具", JSONObject()) {
        McpToolResult.text("plain")
    }
    private val errorTool = McpTool("error", "失败工具", JSONObject()) {
        McpToolResult.error("bad tool")
    }
    private val readOnlyTool = McpTool("read", "只读工具", JSONObject(), readOnly = true) {
        McpToolResult.text("""{"ok":true}""")
    }
    private val slowTool = McpTool("slow", "慢工具", JSONObject()) {
        slowGate.await(2, TimeUnit.SECONDS)
        McpToolResult.text("""{"ok":true}""")
    }

    private val catalog = FakeMcpCatalog(
        listOf(syncTool, asyncTool, plainTool, errorTool, readOnlyTool, slowTool),
    )

    private val resources = FakeMcpResources(
        resources = mapOf(
            "izuki://device" to ResourceContent("izuki://device", "application/json", text = """{"ok":true}"""),
            "izuki://screenshot" to ResourceContent("izuki://screenshot", "image/jpeg", blob = "abc"),
        ),
        templates = JSONArray().put(JSONObject().put("uriTemplate", "izuki://scripts/{id}")),
    )

    private fun handler(syncWaitMs: Long = 2_000L): McpProtocolHandler = McpProtocolHandler(
        registry = catalog,
        resources = resources,
        operations = operations,
        auditLog = audit,
        logBus = logs,
        toolDispatcher = dispatcher,
        serverVersion = "test",
        syncWaitMs = syncWaitMs,
    )

    @Before
    fun setUp() {
        dispatcher = Executors.newSingleThreadExecutor()
    }

    @After
    fun tearDown() {
        slowGate.countDown()
        dispatcher.shutdownNow()
        dispatcher.awaitTermination(2, TimeUnit.SECONDS)
    }

    private fun request(id: Int?, method: String, params: JSONObject? = null): JSONObject =
        JSONObject().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            id?.let { put("id", it) }
            params?.let { put("params", it) }
        }

    private fun call(id: Int, name: String, arguments: JSONObject = JSONObject()): JSONObject =
        request(
            id,
            "tools/call",
            JSONObject().put("name", name).put("arguments", arguments),
        )

    private fun JSONObject.errorCode(): Int = getJSONObject("error").getInt("code")

    private fun JSONObject.resultObject(): JSONObject = getJSONObject("result")

    private fun firstText(result: JSONObject): String =
        result.getJSONArray("content").getJSONObject(0).getString("text")

    private fun awaitUntil(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("条件未在 ${timeoutMs}ms 内满足", condition())
    }

    @Test
    fun rejectsEmptyAndMalformedJson() {
        assertEquals(-32700, JSONObject(handler().handle("")!!).errorCode())
        assertEquals(-32700, JSONObject(handler().handle("{")!!).errorCode())
        assertEquals(-32600, JSONObject(handler().handle("1")!!).errorCode())
    }

    @Test
    fun handlesBatchAndOmitsNotifications() {
        val batch = JSONArray()
            .put(request(1, "ping"))
            .put(request(null, "notifications/initialized"))

        val response = JSONArray(handler().handle(batch.toString())!!)

        assertEquals(1, response.length())
        assertEquals(1, response.getJSONObject(0).getInt("id"))
    }

    @Test
    fun emptyBatchReturnsInvalidRequestElement() {
        val response = JSONArray(handler().handle("[]")!!)

        assertEquals(1, response.length())
        assertEquals(-32600, response.getJSONObject(0).errorCode())
    }

    @Test
    fun invalidBatchElementReturnsErrorAndKeepsValidOnes() {
        val batch = JSONArray().put("not-an-object").put(request(2, "ping"))

        val response = JSONArray(handler().handle(batch.toString())!!)

        assertEquals(2, response.length())
        assertEquals(-32600, response.getJSONObject(0).errorCode())
        assertEquals(2, response.getJSONObject(1).getInt("id"))
    }

    @Test
    fun notificationWithoutIdProducesNoResponse() {
        assertNull(handler().handle(request(null, "ping").toString()))
    }

    @Test
    fun initializeNegotiatesKnownProtocolVersion() {
        val response = JSONObject(
            handler().handle(
                request(1, "initialize", JSONObject().put("protocolVersion", "2024-11-05")).toString(),
            )!!,
        )
        val result = response.resultObject()

        assertEquals("2024-11-05", result.getString("protocolVersion"))
        assertEquals(McpProtocolHandler.SERVER_NAME, result.getJSONObject("serverInfo").getString("name"))
        assertEquals("test", result.getJSONObject("serverInfo").getString("version"))
        assertTrue(result.getJSONObject("capabilities").has("tools"))
    }

    @Test
    fun initializeFallsBackToLatestForUnknownVersion() {
        val response = JSONObject(
            handler().handle(
                request(1, "initialize", JSONObject().put("protocolVersion", "1999-01-01")).toString(),
            )!!,
        )

        assertEquals(
            McpProtocolHandler.LATEST_VERSION,
            response.resultObject().getString("protocolVersion"),
        )
    }

    @Test
    fun unknownMethodReturnsMethodNotFound() {
        val response = JSONObject(handler().handle(request(1, "nope").toString())!!)

        assertEquals(-32601, response.errorCode())
    }

    @Test
    fun toolsListIncludesCatalogAndReadOnlyAnnotation() {
        val response = JSONObject(handler().handle(request(1, "tools/list").toString())!!)
        val tools = response.resultObject().getJSONArray("tools")

        assertEquals(catalog.tools().size, tools.length())
        val read = (0 until tools.length())
            .map { tools.getJSONObject(it) }
            .single { it.getString("name") == "read" }
        assertTrue(read.getJSONObject("annotations").getBoolean("readOnlyHint"))
    }

    @Test
    fun toolsCallRequiresName() {
        val response = JSONObject(
            handler().handle(request(1, "tools/call", JSONObject()).toString())!!,
        )

        assertEquals(-32602, response.errorCode())
    }

    @Test
    fun toolsCallRejectsUnknownTool() {
        val response = JSONObject(handler().handle(call(1, "missing").toString())!!)

        assertEquals(-32602, response.errorCode())
    }

    @Test
    fun synchronousToolBypassesBlockedDispatcher() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        dispatcher.execute {
            started.countDown()
            release.await()
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))

        try {
            val response = JSONObject(handler().handle(call(1, "sync").toString())!!)
            val result = response.resultObject()

            assertFalse(result.optBoolean("isError", false))
            assertTrue(firstText(result).contains("\"ok\":true"))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun asynchronousToolReturnsResultWithElapsedMs() {
        val response = JSONObject(handler().handle(call(1, "async").toString())!!)
        val result = response.resultObject()

        assertFalse(result.optBoolean("isError", false))
        val payload = JSONObject(firstText(result))
        assertTrue(payload.getBoolean("ok"))
        assertTrue(payload.has("elapsed_ms"))
    }

    @Test
    fun plainTextResultGetsMetadataBlock() {
        val response = JSONObject(handler().handle(call(1, "plain").toString())!!)
        val content = response.resultObject().getJSONArray("content")

        assertEquals(2, content.length())
        assertEquals("plain", content.getJSONObject(0).getString("text"))
        val meta = JSONObject(content.getJSONObject(1).getString("text"))
        assertTrue(meta.has("elapsed_ms"))
    }

    @Test
    fun toolErrorIsReturnedAsErrorResultAndAudited() {
        val response = JSONObject(handler().handle(call(1, "error").toString())!!)
        val result = response.resultObject()

        assertTrue(result.getBoolean("isError"))
        val entry = audit.entries.single()
        assertFalse(entry.ok)
        assertTrue(entry.message!!.contains("bad tool"))
        assertTrue(logs.warnLines.any { it.contains("error") })
    }

    @Test
    fun slowToolTimesOutIntoRunningHandleAndCompletesLater() {
        val response = JSONObject(handler(syncWaitMs = 50L).handle(call(1, "slow").toString())!!)
        val payload = JSONObject(firstText(response.resultObject()))

        assertEquals("running", payload.getString("status"))
        assertEquals("slow", payload.getString("tool"))
        val requestId = payload.getString("request_id")
        assertNotNull(operations.get(requestId))

        slowGate.countDown()

        awaitUntil { operations.get(requestId)?.status == OperationStatus.DONE }
        awaitUntil { audit.entries.any { it.ok } }
        assertEquals(OperationStatus.DONE, operations.get(requestId)!!.status)
    }

    @Test
    fun resourcesListAndTemplatesAreExposed() {
        val list = JSONObject(handler().handle(request(1, "resources/list").toString())!!)
        assertEquals(2, list.resultObject().getJSONArray("resources").length())

        val templates = JSONObject(handler().handle(request(2, "resources/templates/list").toString())!!)
        assertEquals(
            1,
            templates.resultObject().getJSONArray("resourceTemplates").length(),
        )
    }

    @Test
    fun resourcesReadReturnsTextAndBlob() {
        val textResponse = JSONObject(
            handler().handle(
                request(1, "resources/read", JSONObject().put("uri", "izuki://device")).toString(),
            )!!,
        )
        val text = textResponse.resultObject().getJSONArray("contents").getJSONObject(0)
        assertEquals("application/json", text.getString("mimeType"))
        assertEquals("""{"ok":true}""", text.getString("text"))

        val blobResponse = JSONObject(
            handler().handle(
                request(2, "resources/read", JSONObject().put("uri", "izuki://screenshot")).toString(),
            )!!,
        )
        val blob = blobResponse.resultObject().getJSONArray("contents").getJSONObject(0)
        assertEquals("image/jpeg", blob.getString("mimeType"))
        assertEquals("abc", blob.getString("blob"))
        assertFalse(blob.has("text"))
    }

    @Test
    fun resourcesReadRejectsUnknownUri() {
        val response = JSONObject(
            handler().handle(
                request(1, "resources/read", JSONObject().put("uri", "izuki://missing")).toString(),
            )!!,
        )

        assertEquals(-32602, response.errorCode())
    }

    @Test
    fun logsRedactSensitiveArguments() {
        handler().handle(
            call(
                1,
                "async",
                JSONObject().put("token", "top-secret").put("content", "x".repeat(500)),
            ).toString(),
        )
        awaitUntil { logs.debugLines.isNotEmpty() }

        val line = logs.debugLines.first { it.contains("async") }
        assertFalse(line.contains("top-secret"))
        assertTrue(line.contains("<500 chars>"))
    }
}
