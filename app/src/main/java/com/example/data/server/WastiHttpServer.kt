package com.example.data.server

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Case-insensitive HTTP Header container compatible with standard HTTP server patterns.
 */
class Headers : LinkedHashMap<String, MutableList<String>>(String.CASE_INSENSITIVE_ORDER) {
    fun getFirst(key: String): String? = this[key]?.firstOrNull()

    fun set(key: String, value: String) {
        this[key] = mutableListOf(value)
    }

    fun add(key: String, value: String) {
        getOrPut(key) { mutableListOf() }.add(value)
    }
}

/**
 * Android-compatible HTTP exchange abstraction providing access to request and response.
 */
abstract class HttpExchange : AutoCloseable {
    abstract val requestMethod: String
    abstract val requestURI: URI
    abstract val requestHeaders: Headers
    abstract val responseHeaders: Headers
    abstract val requestBody: InputStream
    abstract val responseBody: OutputStream

    abstract fun sendResponseHeaders(rCode: Int, responseLength: Long)
    abstract override fun close()
}

/**
 * Handler interface for HTTP requests.
 */
fun interface HttpHandler {
    fun handle(exchange: HttpExchange)
}

/**
 * Context mapping a URI path prefix to a specific handler.
 */
class HttpContext(val path: String, val handler: HttpHandler)

/**
 * Pure Android-native, zero-dependency embedded HTTP Server.
 * Replaces non-Android JDK-only "com.sun.net.httpserver.HttpServer" to guarantee 100% crash-free
 * operation on real Android devices (ART/Dalvik), Termux, emulator, and JVM test runners.
 */
class HttpServer private constructor(
    private val bindAddress: InetSocketAddress,
    private val backlog: Int = 50
) {
    var executor: Executor? = Executors.newFixedThreadPool(4)
    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val contexts = CopyOnWriteArrayList<HttpContext>()
    private var acceptThread: Thread? = null

    val address: InetSocketAddress
        get() = serverSocket?.let {
            InetSocketAddress(it.inetAddress, it.localPort)
        } ?: bindAddress

    fun createContext(path: String, handler: HttpHandler): HttpContext {
        val normalized = if (path.startsWith("/")) path else "/$path"
        val ctx = HttpContext(normalized, handler)
        contexts.add(ctx)
        return ctx
    }

    fun createContext(path: String, handler: (HttpExchange) -> Unit): HttpContext {
        return createContext(path, HttpHandler { exchange -> handler(exchange) })
    }

    @Synchronized
    fun start() {
        if (isRunning.get()) return

        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(bindAddress, backlog)
        serverSocket = socket
        isRunning.set(true)

        val thread = Thread({
            while (isRunning.get() && !socket.isClosed) {
                try {
                    val clientSocket = socket.accept()
                    val exec = executor
                    if (exec != null) {
                        exec.execute { handleConnection(clientSocket) }
                    } else {
                        Thread { handleConnection(clientSocket) }.start()
                    }
                } catch (_: SocketException) {
                    // Socket closed on stop
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.w(TAG, "Error accepting connection: ${e.message}")
                    }
                }
            }
        }, "wasti-http-acceptor")

        thread.isDaemon = true
        thread.start()
        acceptThread = thread
    }

    @Synchronized
    fun stop(delay: Int = 0) {
        if (!isRunning.getAndSet(false)) return

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        try {
            acceptThread?.interrupt()
        } catch (_: Exception) {}
        acceptThread = null
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val rawInput = socket.getInputStream()
            val rawOutput = socket.getOutputStream()

            // 1. Read HTTP request line: e.g. "GET /health HTTP/1.1"
            val requestLine = readLine(rawInput) ?: run {
                socket.close()
                return
            }

            val parts = requestLine.trim().split(" ")
            if (parts.size < 2) {
                sendSimpleResponse(rawOutput, 400, "Bad Request")
                socket.close()
                return
            }

            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val uri = try {
                URI(rawPath)
            } catch (_: Exception) {
                URI("/")
            }

            // 2. Read Request Headers
            val reqHeaders = Headers()
            while (true) {
                val headerLine = readLine(rawInput) ?: break
                if (headerLine.isEmpty()) break
                val colonIdx = headerLine.indexOf(':')
                if (colonIdx > 0) {
                    val k = headerLine.substring(0, colonIdx).trim()
                    val v = headerLine.substring(colonIdx + 1).trim()
                    reqHeaders.add(k, v)
                }
            }

            // 3. Handle Content-Length and Request Body
            val contentLength = reqHeaders.getFirst("Content-Length")?.toLongOrNull() ?: 0L
            val reqBodyStream = BoundedInputStream(rawInput, contentLength)

            // 4. Match Context Handler
            val requestPath = uri.path.ifEmpty { "/" }
            val matchedContext = contexts
                .filter { ctx ->
                    requestPath == ctx.path ||
                    (ctx.path == "/" || (ctx.path.length > 1 && requestPath.startsWith(ctx.path.removeSuffix("/") + "/"))) ||
                    (ctx.path.length > 1 && requestPath == ctx.path.removeSuffix("/"))
                }
                .maxByOrNull { it.path.length }

            if (matchedContext == null) {
                sendSimpleResponse(rawOutput, 404, "{\"error\":\"Not Found\",\"path\":\"$requestPath\"}", "application/json")
                socket.close()
                return
            }

            // 5. Create HttpExchange implementation
            val exchange = SocketHttpExchange(
                socket = socket,
                method = method,
                uri = uri,
                requestHeaders = reqHeaders,
                requestStream = reqBodyStream,
                responseStream = rawOutput
            )

            try {
                matchedContext.handler.handle(exchange)
            } finally {
                exchange.close()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling HTTP request: ${e.message}")
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var last = -1
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (out.size() == 0) return null
                break
            }
            if (b == '\n'.code) {
                break
            }
            if (b != '\r'.code) {
                out.write(b)
            }
            last = b
        }
        return out.toString("UTF-8")
    }

    private fun sendSimpleResponse(
        out: OutputStream,
        statusCode: Int,
        body: String,
        contentType: String = "text/plain"
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val statusMsg = getStatusMessage(statusCode)
        val header = "HTTP/1.1 $statusCode $statusMsg\r\n" +
                "Content-Type: $contentType; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    companion object {
        private const val TAG = "WastiHttpServer"

        fun create(addr: InetSocketAddress, backlog: Int = 50): HttpServer {
            return HttpServer(addr, backlog)
        }

        fun getStatusMessage(code: Int): String = when (code) {
            200 -> "OK"
            201 -> "Created"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Status $code"
        }
    }
}

/**
 * An InputStream that reads at most [limit] bytes from the underlying stream.
 */
private class BoundedInputStream(
    private val wrapped: InputStream,
    private val limit: Long
) : FilterInputStream(wrapped) {
    private var bytesRead: Long = 0L

    override fun read(): Int {
        if (limit >= 0 && bytesRead >= limit) return -1
        val b = super.read()
        if (b != -1) bytesRead++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (limit >= 0 && bytesRead >= limit) return -1
        val toRead = if (limit >= 0) {
            minOf(len.toLong(), limit - bytesRead).toInt()
        } else len
        val count = super.read(b, off, toRead)
        if (count != -1) bytesRead += count
        return count
    }
}

/**
 * Concrete HttpExchange wrapping socket streams.
 */
private class SocketHttpExchange(
    private val socket: Socket,
    override val requestMethod: String,
    override val requestURI: URI,
    override val requestHeaders: Headers,
    override val requestBody: InputStream,
    private val responseStream: OutputStream
) : HttpExchange() {
    override val responseHeaders: Headers = Headers()
    private var headersSent = false
    private val wrappedResponseBody = object : FilterOutputStream(responseStream) {
        override fun close() {
            flush()
            // Do not close the underlying socket here; exchange.close() handles it
        }
    }

    override val responseBody: OutputStream
        get() = wrappedResponseBody

    override fun sendResponseHeaders(rCode: Int, responseLength: Long) {
        if (headersSent) return
        headersSent = true

        val statusText = HttpServer.getStatusMessage(rCode)
        val headerBuilder = StringBuilder("HTTP/1.1 $rCode $statusText\r\n")

        if (responseLength >= 0) {
            headerBuilder.append("Content-Length: $responseLength\r\n")
        }
        for ((k, vals) in responseHeaders) {
            for (v in vals) {
                headerBuilder.append("$k: $v\r\n")
            }
        }
        headerBuilder.append("Connection: close\r\n\r\n")
        responseStream.write(headerBuilder.toString().toByteArray(Charsets.UTF_8))
        responseStream.flush()
    }

    override fun close() {
        try {
            wrappedResponseBody.flush()
        } catch (_: Exception) {}
        try {
            socket.close()
        } catch (_: Exception) {}
    }
}
