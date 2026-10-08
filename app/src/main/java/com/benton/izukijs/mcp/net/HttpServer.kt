package com.benton.izukijs.mcp.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 一个已解析的 HTTP 请求。 */
data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

/** 一个待写回的 HTTP 响应。 */
data class HttpResponse(
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val contentType: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        fun json(body: String): HttpResponse =
            HttpResponse(200, body.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")

        fun text(status: Int, body: String): HttpResponse =
            HttpResponse(status, body.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")

        fun empty(status: Int, headers: Map<String, String> = emptyMap()): HttpResponse =
            HttpResponse(status, headers = headers)
    }
}

/**
 * 极简 HTTP/1.1 服务器，仅服务 MCP 单端点所需能力：
 * 解析请求行 / 请求头、`Content-Length` 与 `chunked` 请求体，写回定长响应后关闭连接。
 *
 * 不引入任何三方依赖；每个连接交给 [clients] 线程池处理，[handler] 在连接线程上执行。
 */
class HttpServer(
    private val bindAddress: String,
    private val port: Int,
    private val maxBodyBytes: Int = 2 * 1024 * 1024,
    private val handler: (HttpRequest) -> HttpResponse,
) {

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var running = false

    private val clients: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "izuki-mcp-http").apply { isDaemon = true }
    }

    /** 已接受的连接，用于停止时主动关闭，避免 keep-alive 连接拖到空闲超时。 */
    private val activeClients: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    val localPort: Int get() = serverSocket?.localPort ?: port

    fun start() {
        if (running) return
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, port))
        }
        serverSocket = socket
        running = true
        acceptThread = Thread({ acceptLoop(socket) }, "izuki-mcp-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        // 主动关闭在途连接并终止工作线程，避免 keep-alive 连接阻塞到空闲超时。
        activeClients.forEach { runCatching { it.close() } }
        activeClients.clear()
        clients.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (t: Throwable) {
                if (running) continue else break
            }
            activeClients.add(client)
            runCatching { clients.execute { handleClient(client) } }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                serveConnection(client)
            } finally {
                activeClients.remove(client)
            }
        }
    }

    private fun serveConnection(client: Socket) {
            // 空闲超时：keep-alive 连接在无新请求时于该时限后关闭。
            runCatching { client.soTimeout = IDLE_TIMEOUT_MS }
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            while (running) {
                val request = try {
                    readRequest(input, output)
                } catch (t: Throwable) {
                    runCatching { writeResponse(output, HttpResponse.text(400, "Bad Request: ${t.message}"), keepAlive = false) }
                    return
                } ?: return

                val response = runCatching { handler(request) }
                    .getOrElse { HttpResponse.text(500, "Internal Server Error: ${it.message}") }
                val keepAlive = shouldKeepAlive(request, response)
                if (!runCatching { writeResponse(output, response, keepAlive) }.isSuccess) return
                if (!keepAlive) return
            }
    }

    /** 读取一个请求；返回 null 表示对端已正常关闭。 */
    private fun readRequest(input: InputStream, output: BufferedOutputStream): HttpRequest? {
        // 跳过前导空行（部分客户端会在请求之间发送多余 CRLF）。
        var requestLine = readLine(input)
        while (requestLine != null && requestLine.isBlank()) requestLine = readLine(input)
        if (requestLine == null) return null

        val parts = requestLine.split(' ')
        if (parts.size < 3) throw IllegalArgumentException("非法请求行：$requestLine")
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')
        val version = parts[2]

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val index = line.indexOf(':')
            if (index > 0) {
                headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
            }
        }

        // 处理 Expect: 100-continue：先回 100，客户端才会发送请求体。
        if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        }

        // 记录 HTTP 版本，供 keep-alive 判定使用。
        headers[HEADER_VERSION] = version
        val body = readBody(input, headers)
        return HttpRequest(method, path, headers, body)
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        val transferEncoding = headers["transfer-encoding"]
        if (transferEncoding != null && transferEncoding.contains("chunked", ignoreCase = true)) {
            return readChunked(input)
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return ByteArray(0)
        if (length > maxBodyBytes) throw IllegalArgumentException("请求体过大")
        return readFully(input, length)
    }

    /**
     * 判定连接是否复用：HTTP/1.1 默认 keep-alive，HTTP/1.0 需显式 `Connection: keep-alive`；
     * 任一方要求 `close` 则关闭。
     */
    private fun shouldKeepAlive(request: HttpRequest, response: HttpResponse): Boolean {
        if (request.header("connection")?.contains("close", ignoreCase = true) == true) return false
        if (response.headers["Connection"]?.contains("close", ignoreCase = true) == true) return false
        val version = request.header(HEADER_VERSION) ?: "HTTP/1.1"
        return if (version.endsWith("1.0")) {
            request.header("connection")?.contains("keep-alive", ignoreCase = true) == true
        } else {
            true
        }
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                // 消费尾部空行 / trailer。
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            if (out.size() + size > maxBodyBytes) throw IllegalArgumentException("请求体过大")
            out.write(readFully(input, size))
            readLine(input) // 每个分块后的 CRLF
        }
        return out.toByteArray()
    }

    private fun readFully(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(data, offset, length - offset)
            if (read < 0) throw IllegalStateException("请求体不完整")
            offset += read
        }
        return data
    }

    /** 读取一行（到 `\n`），去掉结尾的 `\r`。 */
    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream(64)
        var read = input.read()
        if (read < 0) return null
        while (read >= 0 && read != '\n'.code) {
            buffer.write(read)
            read = input.read()
        }
        val bytes = buffer.toByteArray()
        val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    private fun writeResponse(output: BufferedOutputStream, response: HttpResponse, keepAlive: Boolean) {
        val header = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(statusText(response.status)).append("\r\n")
            append("Content-Length: ").append(response.body.size).append("\r\n")
            response.contentType?.let { append("Content-Type: ").append(it).append("\r\n") }
            response.headers.forEach { (key, value) -> append(key).append(": ").append(value).append("\r\n") }
            append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.US_ASCII))
        if (response.body.isNotEmpty()) output.write(response.body)
        output.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        202 -> "Accepted"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        else -> "Status"
    }

    private companion object {
        /** keep-alive 连接的空闲超时；超过则关闭连接。 */
        const val IDLE_TIMEOUT_MS = 120_000

        /** 内部伪请求头：记录 HTTP 版本，不参与业务逻辑。 */
        const val HEADER_VERSION = ":http-version"
    }
}
