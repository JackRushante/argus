package dev.argus.automation.bench

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Server HTTP minimo su loopback per provare il motore del banco con risposte registrate (solo
 * socket del JDK: nessuna dipendenza di test in piu'). Una richiesta per connessione, risposta
 * JSON con `Connection: close`. Il gestore gira su thread diversi: deve essere privo di stato.
 */
internal class FakeChatServer(private val handler: (path: String, body: String) -> Reply) : Closeable {
    data class Reply(val status: Int, val body: String)

    private val server = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
    private val pool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "fake-chat-server").apply { isDaemon = true }
    }

    /** Corpi delle richieste ricevute, nell'ordine di arrivo. */
    val requests = CopyOnWriteArrayList<String>()

    val baseUrl: String = "http://127.0.0.1:${server.localPort}/v1"

    init {
        pool.execute { acceptLoop() }
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (error: IOException) {
                return
            }
            pool.execute { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            try {
                socket.soTimeout = READ_TIMEOUT_MS
                val input = BufferedInputStream(socket.getInputStream())
                val requestLine = readLine(input) ?: return
                var length = 0
                while (true) {
                    val line = readLine(input) ?: return
                    if (line.isEmpty()) break
                    if (line.startsWith("content-length:", ignoreCase = true)) {
                        length = line.substringAfter(':').trim().toInt()
                    }
                }
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val count = input.read(body, read, length - read)
                    if (count < 0) break
                    read += count
                }
                val text = String(body, 0, read, Charsets.UTF_8)
                requests += text
                val path = requestLine.split(' ').getOrElse(1) { "/" }
                val reply = handler(path, text)
                val payload = reply.body.toByteArray(Charsets.UTF_8)
                val head = "HTTP/1.1 ${reply.status} ${reasonOf(reply.status)}\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${payload.size}\r\n" +
                    "Connection: close\r\n\r\n"
                val output = socket.getOutputStream()
                output.write(head.toByteArray(Charsets.US_ASCII))
                output.write(payload)
                output.flush()
            } catch (_: IOException) {
                // il client ha chiuso (timeout, cancellazione): niente da segnalare
            }
        }
    }

    private fun reasonOf(status: Int): String = when (status) {
        200 -> "OK"
        429 -> "Too Many Requests"
        else -> "Error"
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val next = input.read()
            if (next < 0) return if (line.isEmpty()) null else line.toString()
            if (next == '\n'.code) return line.toString().trimEnd('\r')
            line.append(next.toChar())
        }
    }

    override fun close() {
        server.close()
        pool.shutdownNow()
    }

    private companion object {
        const val BACKLOG = 64
        const val READ_TIMEOUT_MS = 15_000
    }
}
