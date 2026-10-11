package com.lanvault.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class HttpRequest(
    val method: String,
    val rawTarget: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: InputStream,
    val remoteAddress: String,
) {
    fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]

    fun headerOrNull(name: String): String? = header(name)
}

class HttpResponse {
    var statusCode: Int = 200
    var reasonPhrase: String = "OK"
    val headers: LinkedHashMap<String, String> = LinkedHashMap()
    var bodyBytes: ByteArray? = null
    var bodyStream: InputStream? = null
    var contentLength: Long = -1L

    fun header(name: String, value: String) {
        headers[name] = value
    }

    companion object {
        fun status(code: Int, phrase: String = reasonForCode(code)): HttpResponse = HttpResponse().apply {
            statusCode = code
            reasonPhrase = phrase
        }

        fun text(code: Int, body: String, contentType: String = "text/plain; charset=utf-8"): HttpResponse =
            status(code).apply {
                header("Content-Type", contentType)
                bodyBytes = body.toByteArray(Charsets.UTF_8)
            }

        fun noContent(): HttpResponse = status(204, "No Content")
    }
}

class LengthLimitedInputStream(
    private val delegate: InputStream,
    private var remaining: Long,
) : InputStream() {

    override fun read(): Int {
        if (remaining <= 0L) return -1
        val value = delegate.read()
        if (value >= 0) remaining -= 1
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0L) return -1
        val capped = min(len.toLong(), remaining).toInt()
        val read = delegate.read(b, off, capped)
        if (read > 0) remaining -= read
        return read
    }

    override fun available(): Int = min(delegate.available().toLong(), remaining).toInt()

    val fullyConsumed: Boolean
        get() = remaining == 0L
}

class ChunkedInputStream(private val delegate: InputStream) : InputStream() {

    private var chunkRemaining: Long = 0
    private var finished = false

    override fun read(): Int {
        if (finished && chunkRemaining <= 0L) return -1
        if (chunkRemaining <= 0L) {
            if (!readNextChunkHeader()) {
                finished = true
                return -1
            }
            if (chunkRemaining == 0L) {
                finished = true
                return -1
            }
        }
        val value = delegate.read()
        if (value >= 0) chunkRemaining -= 1
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        if (chunkRemaining <= 0L) {
            if (!readNextChunkHeader()) return -1
            if (chunkRemaining == 0L) return -1
        }
        val toRead = min(len.toLong(), chunkRemaining).toInt()
        val read = delegate.read(b, off, toRead)
        if (read > 0) chunkRemaining -= read
        return read
    }

    private fun readNextChunkHeader(): Boolean {
        while (true) {
            val line = readLine(delegate) ?: return false
            val sizeText = line.substringBefore(';').trim()
            if (sizeText.isEmpty()) continue
            val size = sizeText.toLongOrNull(16) ?: return false
            chunkRemaining = size
            if (size == 0L) {
                // trailers
                while (true) {
                    val trailer = readLine(delegate) ?: break
                    if (trailer.isEmpty()) break
                }
            }
            return true
        }
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val value = input.read()
            if (value == -1) return if (builder.isEmpty()) null else builder.toString()
            if (value == '\n'.code) return builder.toString().trimEnd('\r')
            if (value != '\r'.code) builder.append(value.toChar())
        }
    }
}

fun reasonForCode(code: Int): String = when (code) {
    100 -> "Continue"
    101 -> "Switching Protocols"
    200 -> "OK"
    201 -> "Created"
    202 -> "Accepted"
    204 -> "No Content"
    206 -> "Partial Content"
    207 -> "Multi-Status"
    301 -> "Moved Permanently"
    302 -> "Found"
    304 -> "Not Modified"
    400 -> "Bad Request"
    401 -> "Unauthorized"
    403 -> "Forbidden"
    404 -> "Not Found"
    405 -> "Method Not Allowed"
    409 -> "Conflict"
    412 -> "Precondition Failed"
    413 -> "Payload Too Large"
    415 -> "Unsupported Media Type"
    416 -> "Range Not Satisfiable"
    422 -> "Unprocessable Entity"
    423 -> "Locked"
    500 -> "Internal Server Error"
    501 -> "Not Implemented"
    507 -> "Insufficient Storage"
    else -> "Status $code"
}

private val httpDate = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("GMT")
}

fun formatHttpDate(epochMillis: Long): String = httpDate.format(Date(epochMillis))

internal object EmptyInputStream : InputStream() {
    override fun read(): Int = -1
    override fun read(b: ByteArray, off: Int, len: Int): Int = -1
    override fun available(): Int = 0
}

/** Minimal embedded HTTP/1.1 server with keep-alive, chunked bodies and range writes. */
class LiteHttpServer(
    private val initialPort: Int,
    private val handler: (HttpRequest) -> HttpResponse,
) : Closeable {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = Executors.newCachedThreadPool(object : ThreadFactory {
        private val counter = java.util.concurrent.atomic.AtomicInteger(0)
        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "lanvault-http-${counter.incrementAndGet()}").apply { isDaemon = true }
    })

    val isRunning: Boolean get() = running.get()

    /** Actual port the socket ended up on; 0 means not started yet. */
    var port: Int = 0
        private set

    fun start(): Boolean {
        if (running.get()) return true
        var lastError: Throwable? = null
        repeat(BIND_ATTEMPTS) { attempt ->
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", initialPort), 128)
                serverSocket = socket
                this.port = socket.localPort
                running.set(true)
                acceptThread = Thread({ acceptLoop(socket) }, "lanvault-accept").apply { isDaemon = true }
                acceptThread?.start()
                return true
            } catch (error: Throwable) {
                lastError = error
                if (attempt + 1 < BIND_ATTEMPTS) {
                    runCatching { Thread.sleep(BIND_RETRY_DELAY_MILLIS) }
                }
            }
        }
        running.set(false)
        this.port = 0
        serverSocket?.runCatching { close() }
        serverSocket = null
        throw lastError ?: IllegalStateException("Unable to bind port $initialPort")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val connection = try {
                socket.accept()
            } catch (error: Throwable) {
                if (running.get()) EventLog.shared.error("accept failed: ${error.message}")
                break
            }
            connection.tcpNoDelay = true
            workers.execute { runCatching { serveConnection(connection) } }
        }
    }

    private fun serveConnection(socket: Socket) {
        socket.use {
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            while (running.get() && !socket.isClosed) {
                val request = try {
                    readRequest(input, socket)
                } catch (error: Throwable) {
                    return
                } ?: return

                if (request.header("Expect")?.contains("100-continue", ignoreCase = true) == true) {
                    writeBytes(output, "HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
                    output.flush()
                }
                val response = try {
                    handler(request)
                } catch (error: Throwable) {
                    EventLog.shared.error("handler error for ${request.method} ${request.rawTarget}: ${error}")
                    HttpResponse.status(500, "Internal Server Error").apply {
                        header("Content-Type", "text/plain; charset=utf-8")
                        bodyBytes = "Internal Server Error".toByteArray()
                    }
                }

                writeResponse(output, response)
                drainBody(request.body)

                val keepAlive = response.headerOrEmpty("connection").lowercase(Locale.ROOT) != "close" &&
                    request.header("connection")?.lowercase(Locale.ROOT) != "close" &&
                    request.method != "CONNECT"
                if (!keepAlive) break
            }
        }
    }

    private fun drainBody(body: InputStream) {
        runCatching {
            val buffer = ByteArray(16 * 1024)
            while (body.read(buffer) > 0) { /* discard unread body to keep the connection aligned */ }
        }
    }

    private fun HttpResponse.headerOrEmpty(name: String): String = headers[name] ?: ""

    private fun readRequest(input: InputStream, socket: Socket): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isEmpty()) return readRequest(input, socket)
        val parts = requestLine.split(' ')
        if (parts.size < 3) throw IOException("malformed request line: $requestLine")
        val method = parts[0].uppercase(Locale.ROOT)
        val target = parts[1]
        val version = parts[2]
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().lowercase(Locale.ROOT)
            headers[name] = line.substring(separator + 1).trim()
        }

        val pathAndQuery = target.split('?', limit = 2)
        val rawPath = pathAndQuery[0]
        val queryString = pathAndQuery.getOrNull(1).orEmpty()
        val query = LinkedHashMap<String, String>()
        if (queryString.isNotEmpty()) {
            queryString.split('&').forEach { pair ->
                if (pair.isEmpty()) return@forEach
                val keyValue = pair.split('=', limit = 2)
                @Suppress("DEPRECATION")
                val key = URLDecoder.decode(keyValue[0], "UTF-8")
                @Suppress("DEPRECATION")
                val value = if (keyValue.size > 1) URLDecoder.decode(keyValue[1], "UTF-8") else ""
                query[key] = value
            }
        }

        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> ChunkedInputStream(input)
            else -> headers["content-length"]?.toLongOrNull()?.takeIf { it > 0L }
                ?.let { LengthLimitedInputStream(input, it) } ?: EmptyInputStream
        }

        return HttpRequest(
            method = method,
            rawTarget = target,
            path = decodePath(rawPath),
            query = query,
            headers = headers,
            body = body,
            remoteAddress = socket.inetAddress?.hostAddress ?: "unknown",
        )
    }

    private fun decodePath(rawPath: String): String {
        if (rawPath.isEmpty()) return "/"
        return try {
            @Suppress("DEPRECATION")
            URLDecoder.decode(rawPath, "UTF-8")
        } catch (error: Throwable) {
            rawPath
        }
    }

    private fun writeResponse(output: OutputStream, response: HttpResponse) {
        val builder = StringBuilder()
        builder.append("HTTP/1.1 ").append(response.statusCode).append(' ').append(response.reasonPhrase).append("\r\n")
        builder.append("Date: ").append(formatHttpDate(System.currentTimeMillis())).append("\r\n")
        builder.append("Server: LANVault/1.0\r\n")

        val bytes = response.bodyBytes
        val stream = response.bodyStream

        if (bytes != null) {
            response.headers.getOrPut("Content-Type") { "application/octet-stream" }
            response.headers["Content-Length"] = bytes.size.toString()
        } else if (stream != null) {
            val length = response.contentLength
            if (length >= 0) {
                response.headers["Content-Length"] = length.toString()
            } else {
                response.headers["Transfer-Encoding"] = "chunked"
            }
        } else {
            response.headers.getOrPut("Content-Length") { "0" }
        }

        response.headers.forEach { (name, value) ->
            builder.append(name).append(": ").append(value).append("\r\n")
        }
        builder.append("\r\n")
        writeBytes(output, builder.toString().toByteArray(Charsets.ISO_8859_1))

        when {
            bytes != null -> writeBytes(output, bytes)
            stream != null -> {
                // Cap the stream to the advertised length: a range response must never leak
                // the rest of the file to the client.
                val limited = if (response.contentLength >= 0) {
                    LengthLimitedInputStream(stream, response.contentLength)
                } else {
                    stream
                }
                try {
                    if (response.contentLength >= 0) copyStream(limited, output)
                    else writeChunked(stream, output)
                } finally {
                    runCatching { stream.close() }
                }
            }
        }
        output.flush()
    }

    private fun copyStream(source: InputStream, destination: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = source.read(buffer)
            if (read <= 0) break
            destination.write(buffer, 0, read)
        }
    }

    private fun writeChunked(source: InputStream, destination: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = source.read(buffer)
            if (read <= 0) break
            destination.write(read.toString(16).toByteArray(Charsets.ISO_8859_1))
            destination.write("\r\n".toByteArray())
            destination.write(buffer, 0, read)
            destination.write("\r\n".toByteArray())
        }
        destination.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
    }

    private fun writeBytes(output: OutputStream, bytes: ByteArray) {
        output.write(bytes)
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val value = input.read()
            if (value == -1) return if (builder.isEmpty()) null else builder.toString()
            if (value == '\n'.code) return builder.toString().trimEnd('\r')
            if (value != '\r'.code) builder.append(value.toChar())
        }
    }

    override fun close() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        this.port = 0
        // Let the accept loop notice the closed socket so the port is free for a restart.
        acceptThread?.let { thread -> runCatching { thread.join(500) } }
        acceptThread = null
        workers.shutdownNow()
    }
}

private const val BIND_ATTEMPTS = 6
private const val BIND_RETRY_DELAY_MILLIS = 150L
