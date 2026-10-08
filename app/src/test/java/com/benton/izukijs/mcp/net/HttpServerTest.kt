package com.benton.izukijs.mcp.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.net.Socket

/**
 * [HttpServer] 的端到端测试：通过真实回环 socket 发送原始 HTTP 报文，
 * 验证请求行 / 请求头解析、Content-Length 与 chunked 请求体、keep-alive 复用与响应写回。
 */
class HttpServerTest {

    private fun withServer(
        handler: (HttpRequest) -> HttpResponse,
        block: (Int) -> Unit,
    ) {
        val server = HttpServer(bindAddress = "127.0.0.1", port = 0, handler = handler)
        server.start()
        try {
            block(server.localPort)
        } finally {
            server.stop()
        }
    }

    /** 发送单个请求（自带 Connection: close）并读回完整响应文本。 */
    private fun roundTrip(port: Int, raw: String): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().apply {
                write(raw.toByteArray(Charsets.UTF_8))
                flush()
            }
            return String(socket.getInputStream().readBytes(), Charsets.UTF_8)
        }
    }

    @Test
    fun echoesMethodPathAndContentLengthBody() {
        withServer({ req -> HttpResponse.text(200, "${req.method} ${req.path} ${String(req.body)}") }) { port ->
            val raw = "POST /mcp?x=1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "Content-Length: 5\r\n" +
                "\r\n" +
                "hello"
            val response = roundTrip(port, raw)
            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.endsWith("POST /mcp hello"))
        }
    }

    @Test
    fun decodesChunkedRequestBody() {
        withServer({ req -> HttpResponse.text(200, String(req.body)) }) { port ->
            val raw = "POST /mcp HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
            val response = roundTrip(port, raw)
            assertTrue(response.endsWith("hello world"))
        }
    }

    @Test
    fun headersAreCaseInsensitive() {
        withServer({ req -> HttpResponse.text(200, req.header("x-test").orEmpty()) }) { port ->
            val raw = "GET /health HTTP/1.1\r\nHost: localhost\r\nX-Test: value\r\nConnection: close\r\n\r\n"
            val response = roundTrip(port, raw)
            assertTrue(response.endsWith("value"))
        }
    }

    @Test
    fun responseCarriesStatusAndContentLength() {
        withServer({ HttpResponse.text(404, "missing") }) { port ->
            val response = roundTrip(port, "GET /nope HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            assertTrue(response.startsWith("HTTP/1.1 404 Not Found"))
            assertTrue(response.contains("Content-Length: 7"))
        }
    }

    @Test
    fun emptyNotificationResponseHasNoBody() {
        withServer({ HttpResponse.empty(202) }) { port ->
            val response = roundTrip(
                port,
                "POST /mcp HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            )
            assertTrue(response.startsWith("HTTP/1.1 202 Accepted"))
            assertTrue(response.contains("Content-Length: 0"))
            assertEquals("", response.substringAfter("\r\n\r\n"))
        }
    }

    @Test
    fun reusesConnectionForKeepAliveRequests() {
        var count = 0
        withServer({ HttpResponse.text(200, "req${++count}") }) { port ->
            Socket("127.0.0.1", port).use { socket ->
                val output = socket.getOutputStream()
                val input = socket.getInputStream()
                output.write("POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
                output.flush()
                val first = readResponseBody(input)
                output.write("POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
                output.flush()
                val second = readResponseBody(input)
                assertEquals("req1", first)
                assertEquals("req2", second)
            }
        }
    }

    @Test
    fun expectContinueIsAcknowledgedBeforeBody() {
        withServer({ req -> HttpResponse.text(200, String(req.body)) }) { port ->
            Socket("127.0.0.1", port).use { socket ->
                val output = socket.getOutputStream()
                val input = socket.getInputStream()
                output.write(
                    ("POST /mcp HTTP/1.1\r\nHost: x\r\nExpect: 100-continue\r\n" +
                        "Content-Length: 2\r\nConnection: close\r\n\r\n").toByteArray(),
                )
                output.flush()
                // 读取 100 Continue 后再发送请求体。
                val interim = readLine(input)
                assertTrue(interim!!.startsWith("HTTP/1.1 100"))
                readLine(input)
                output.write("ok".toByteArray())
                output.flush()
                assertTrue(String(input.readBytes(), Charsets.UTF_8).endsWith("ok"))
            }
        }
    }

    private fun readResponseBody(input: InputStream): String {
        readLine(input) // status line
        var length = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                length = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return String(body, 0, offset, Charsets.UTF_8)
    }

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        var read = input.read()
        if (read < 0) return null
        while (read >= 0 && read != '\n'.code) {
            if (read != '\r'.code) buffer.append(read.toChar())
            read = input.read()
        }
        return buffer.toString()
    }
}
