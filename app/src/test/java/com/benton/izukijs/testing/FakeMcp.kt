package com.benton.izukijs.testing

import com.benton.izukijs.mcp.protocol.McpAuditSink
import com.benton.izukijs.mcp.protocol.McpLogSink
import com.benton.izukijs.mcp.protocol.McpResourceProvider
import com.benton.izukijs.mcp.protocol.McpToolCatalog
import com.benton.izukijs.mcp.resources.ResourceContent
import com.benton.izukijs.mcp.tools.McpTool
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

class FakeMcpCatalog(private val tools: List<McpTool>) : McpToolCatalog {

    override fun tools(): List<McpTool> = tools

    override fun find(name: String): McpTool? = tools.firstOrNull { it.name == name }
}

class FakeMcpResources(
    private val resources: Map<String, ResourceContent> = emptyMap(),
    private val templates: JSONArray = JSONArray(),
) : McpResourceProvider {

    override fun listJson(): JSONArray = JSONArray().apply {
        resources.values.forEach { content ->
            put(
                JSONObject()
                    .put("uri", content.uri)
                    .put("mimeType", content.mimeType),
            )
        }
    }

    override fun templatesJson(): JSONArray = templates

    override fun read(uri: String): ResourceContent? = resources[uri]
}

class FakeAuditSink : McpAuditSink {

    data class Entry(
        val tool: String,
        val arguments: String,
        val ok: Boolean,
        val message: String?,
    )

    val entries = CopyOnWriteArrayList<Entry>()

    override fun record(
        tool: String,
        arguments: JSONObject,
        ok: Boolean,
        message: String?,
    ) {
        entries += Entry(tool, arguments.toString(), ok, message)
    }
}

class FakeMcpLogSink : McpLogSink {

    val debugLines = CopyOnWriteArrayList<String>()
    val infoLines = CopyOnWriteArrayList<String>()
    val warnLines = CopyOnWriteArrayList<String>()

    override fun debug(message: String) {
        debugLines += message
    }

    override fun info(message: String) {
        infoLines += message
    }

    override fun warn(message: String) {
        warnLines += message
    }
}
