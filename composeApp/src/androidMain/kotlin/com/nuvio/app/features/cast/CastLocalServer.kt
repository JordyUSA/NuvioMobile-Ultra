package com.nuvio.app.features.cast

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A minimal HTTP server so a Chromecast can pull media from the phone.
 *
 * This exists because a receiver fetches bytes from its own position on the network. Anything
 * we produce locally (a remux or a transcode) and anything already bound to loopback (the
 * torrent engine's stream URL) is invisible to the television until it is served from the
 * phone's LAN address.
 *
 * Range support is not optional: without `206 Partial Content` the receiver cannot seek, and
 * some firmware refuses to start playback at all.
 */
class CastLocalServer {

    private val workers = Executors.newCachedThreadPool()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** Registered payloads keyed by the path segment they are served under. */
    private val routes = mutableMapOf<String, Payload>()

    sealed interface Payload {
        val contentType: String

        /** A file on disk, typically the output of a remux or transcode. */
        data class LocalFile(val file: File, override val contentType: String) : Payload

        /**
         * A remote URL fetched on the receiver's behalf. Used when the origin needs request
         * headers the Cast receiver cannot send, or lives on an address only the phone can
         * reach.
         */
        data class Proxy(
            val url: String,
            override val contentType: String,
            val headers: Map<String, String>,
        ) : Payload
    }

    val port: Int
        get() = serverSocket?.localPort ?: -1

    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            serverSocket = socket
            running.set(true)
            acceptThread = Thread({ acceptLoop(socket) }, "cast-local-server").apply {
                isDaemon = true
                start()
            }
            true
        } catch (error: IOException) {
            Log.w(TAG, "Could not start local cast server", error)
            running.set(false)
            false
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        synchronized(routes) { routes.clear() }
    }

    /**
     * Publishes [payload] and returns the URL to hand the receiver, or null when no usable
     * network address is available (for example while the phone is offline).
     */
    fun publish(id: String, payload: Payload): String? {
        if (!start()) return null
        synchronized(routes) { routes[id] = payload }
        val host = localAddress() ?: return null
        return "http://$host:$port/media/$id"
    }

    fun unpublish(id: String) {
        synchronized(routes) { routes.remove(id) }
    }

    /**
     * The phone's LAN address. Loopback is useless here: it has to be an address the
     * television can route to.
     */
    fun localAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    // -------------------------------------------------------------------------------------

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (error: IOException) {
                if (running.get()) Log.w(TAG, "Accept failed", error)
                return
            }
            workers.execute {
                try {
                    client.use(::handle)
                } catch (error: Throwable) {
                    Log.w(TAG, "Request failed", error)
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = SOCKET_TIMEOUT_MS
        val input = BufferedInputStream(client.getInputStream())
        val output = client.getOutputStream()

        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return respondStatus(output, 400, "Bad Request")
        val method = parts[0].uppercase()
        val path = parts[1]

        var rangeHeader: String? = null
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
            val separator = header.indexOf(':')
            if (separator <= 0) continue
            if (header.substring(0, separator).trim().equals("Range", ignoreCase = true)) {
                rangeHeader = header.substring(separator + 1).trim()
            }
        }

        if (method != "GET" && method != "HEAD") {
            return respondStatus(output, 405, "Method Not Allowed")
        }

        val id = path.substringAfter("/media/", "").substringBefore('?')
        val payload = synchronized(routes) { routes[id] }
            ?: return respondStatus(output, 404, "Not Found")

        when (payload) {
            is Payload.LocalFile -> serveFile(payload, rangeHeader, method == "HEAD", output)
            is Payload.Proxy -> serveProxy(payload, rangeHeader, method == "HEAD", output)
        }
    }

    private fun serveFile(
        payload: Payload.LocalFile,
        rangeHeader: String?,
        headOnly: Boolean,
        output: OutputStream,
    ) {
        val file = payload.file
        if (!file.isFile) return respondStatus(output, 404, "Not Found")
        val total = file.length()
        val (start, end) = parseRange(rangeHeader, total) ?: run {
            writeHeaders(
                output,
                status = 416,
                reason = "Requested Range Not Satisfiable",
                contentType = payload.contentType,
                contentLength = 0,
                contentRange = "bytes */$total",
            )
            return
        }
        val length = end - start + 1

        writeHeaders(
            output,
            status = if (rangeHeader != null) 206 else 200,
            reason = if (rangeHeader != null) "Partial Content" else "OK",
            contentType = payload.contentType,
            contentLength = length,
            contentRange = if (rangeHeader != null) "bytes $start-$end/$total" else null,
        )
        if (headOnly) return

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            copy(raf::read, output, length)
        }
    }

    private fun serveProxy(
        payload: Payload.Proxy,
        rangeHeader: String?,
        headOnly: Boolean,
        output: OutputStream,
    ) {
        val connection = (URL(payload.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = SOCKET_TIMEOUT_MS
            instanceFollowRedirects = true
            payload.headers.forEach { (key, value) -> setRequestProperty(key, value) }
            rangeHeader?.let { setRequestProperty("Range", it) }
        }
        try {
            val status = connection.responseCode
            val length = connection.getHeaderFieldLong("Content-Length", -1L)
            writeHeaders(
                output,
                status = status,
                reason = if (status == 206) "Partial Content" else "OK",
                contentType = connection.contentType ?: payload.contentType,
                contentLength = length.takeIf { it >= 0 },
                contentRange = connection.getHeaderField("Content-Range"),
            )
            if (headOnly) return
            connection.inputStream.use { stream ->
                copy(stream::read, output, length.takeIf { it >= 0 } ?: Long.MAX_VALUE)
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Streams up to [limit] bytes from [read] into [output].
     *
     * A broken pipe here is normal rather than exceptional: the receiver closes the connection
     * on every seek and when playback stops, so it is swallowed instead of logged as an error.
     */
    private inline fun copy(read: (ByteArray, Int, Int) -> Int, output: OutputStream, limit: Long) {
        val buffer = ByteArray(BUFFER_BYTES)
        var remaining = limit
        try {
            while (remaining > 0) {
                val want = minOf(remaining, buffer.size.toLong()).toInt()
                val got = read(buffer, 0, want)
                if (got <= 0) break
                output.write(buffer, 0, got)
                remaining -= got
            }
            output.flush()
        } catch (_: IOException) {
            // Receiver hung up.
        }
    }

    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header == null) return 0L to (total - 1).coerceAtLeast(0L)
        val spec = header.substringAfter("bytes=", "").substringBefore(',').trim()
        if (spec.isEmpty()) return null
        val startText = spec.substringBefore('-').trim()
        val endText = spec.substringAfter('-', "").trim()

        return when {
            // "bytes=-500": the trailing 500 bytes.
            startText.isEmpty() -> {
                val suffix = endText.toLongOrNull() ?: return null
                val start = (total - suffix).coerceAtLeast(0L)
                start to total - 1
            }
            else -> {
                val start = startText.toLongOrNull() ?: return null
                if (start >= total) return null
                val end = endText.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
                if (end < start) return null
                start to end
            }
        }
    }

    private fun writeHeaders(
        output: OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        contentLength: Long?,
        contentRange: String?,
    ) {
        val builder = StringBuilder()
            .append("HTTP/1.1 ").append(status).append(' ').append(reason).append(CRLF)
            .append("Content-Type: ").append(contentType).append(CRLF)
            .append("Accept-Ranges: bytes").append(CRLF)
            .append("Connection: close").append(CRLF)
        contentLength?.let { builder.append("Content-Length: ").append(it).append(CRLF) }
        contentRange?.let { builder.append("Content-Range: ").append(it).append(CRLF) }
        builder.append(CRLF)
        output.write(builder.toString().toByteArray())
        output.flush()
    }

    private fun respondStatus(output: OutputStream, status: Int, reason: String) {
        writeHeaders(output, status, reason, "text/plain", 0, null)
    }

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val value = input.read()
            if (value == -1) return if (buffer.isEmpty()) null else buffer.toString()
            if (value == '\n'.code) return buffer.toString().removeSuffix("\r")
            buffer.append(value.toChar())
            if (buffer.length > MAX_HEADER_LENGTH) return buffer.toString()
        }
    }

    private companion object {
        const val TAG = "CastLocalServer"
        const val CRLF = "\r\n"
        const val BUFFER_BYTES = 64 * 1024
        const val SOCKET_TIMEOUT_MS = 30_000
        const val CONNECT_TIMEOUT_MS = 15_000
        const val MAX_HEADER_LENGTH = 8 * 1024
    }
}
