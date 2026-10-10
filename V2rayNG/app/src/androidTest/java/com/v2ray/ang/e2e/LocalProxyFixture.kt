package com.v2ray.ang.e2e

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback fixtures for the auto-select end-to-end test.
 *
 * The test must not depend on public servers, so it stands up a local HTTP endpoint that answers
 * the delay-test URL and a SOCKS5 proxy that only ever forwards to it. A profile pointed at
 * [socksPort] therefore measures a real delay, while one pointed at [closedPort] cannot connect.
 */
internal class LocalProxyFixture {

    private val running = AtomicBoolean(false)

    /** Answers every request with 204, standing in for the delay-test URL. */
    private var httpServer: ServerSocket? = null
    var httpPort: Int = 0
        private set

    /** SOCKS5 front end that only forwards to the local HTTP server. */
    private var socksServer: ServerSocket? = null
    var socksPort: Int = 0
        private set

    /**
     * Artificial upstream latency. Loopback alone can measure 0 ms, which this app treats as
     * "not measured" (a delay of 0 is indistinguishable from an absent result), so the fixture
     * adds a real cost to make the working config's measurement unambiguously positive.
     */
    var upstreamDelayMs: Long = 60L

    /** A port that is bound and immediately released, so connecting to it is refused. */
    var closedPort: Int = 0
        private set

    val delayTestUrl: String
        get() = "http://127.0.0.1:$httpPort/generate_204"

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "LocalProxyFixture").apply { isDaemon = true }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            httpServer = ServerSocket(0, 16, InetAddress.getByName(LOOPBACK)).also { httpPort = it.localPort }
            socksServer = ServerSocket(0, 16, InetAddress.getByName(LOOPBACK)).also { socksPort = it.localPort }
            closedPort = reserveClosedPort()
            executor.submit { acceptLoop(httpServer!!) { serveHttp(it) } }
            executor.submit { acceptLoop(socksServer!!) { serveSocks(it) } }
        } catch (e: IOException) {
            stop()
            throw e
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { httpServer?.close() }
        runCatching { socksServer?.close() }
        executor.shutdownNow()
    }

    /** Binds a port and releases it so the port is known-closed for the whole test. */
    private fun reserveClosedPort(): Int =
        ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }

    private fun acceptLoop(server: ServerSocket, handler: (Socket) -> Unit) {
        while (running.get()) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                return
            }
            executor.submit {
                try {
                    handler(socket)
                } catch (_: Exception) {
                    // A probe that gives up mid-handshake is an expected outcome here.
                } finally {
                    runCatching { socket.close() }
                }
            }
        }
    }

    private fun serveHttp(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        readRequestHead(input)
        output.write(HTTP_204)
        output.flush()
        // Closing straight after the write can reset the connection before the peer reads the
        // response, which surfaces as "awaiting headers" on the probe side. Drain until the peer
        // goes away so the 204 is actually delivered.
        runCatching { while (input.read() != -1) { /* discard */ } }
    }

    private fun serveSocks(socket: Socket) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // Greeting: VER, NMETHODS, methods.
        readFully(input, 2)
        val methodCount = readFully(input, 1)[0].toInt()
        readFully(input, methodCount)
        output.write(byteArrayOf(0x05, 0x00))
        output.flush()

        // Request: VER, CMD, RSV, ATYP + address + port. Only CONNECT is supported.
        val request = readFully(input, 4)
        if (request[1].toInt() != 0x01) {
            output.write(SOCKS_FAILURE)
            output.flush()
            return
        }
        when (request[3].toInt()) {
            0x01 -> readFully(input, 4)                                  // IPv4
            0x03 -> readFully(input, readFully(input, 1)[0].toInt())      // domain
            0x04 -> readFully(input, 16)                                 // IPv6
            else -> {
                output.write(SOCKS_FAILURE)
                output.flush()
                return
            }
        }
        readFully(input, 2)                                              // port

        val upstream = Socket()
        try {
            if (upstreamDelayMs > 0) Thread.sleep(upstreamDelayMs)
            upstream.connect(InetSocketAddress(LOOPBACK, httpPort), 5_000)
        } catch (e: IOException) {
            output.write(SOCKS_FAILURE)
            output.flush()
            return
        }
        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
        relay(input, output, upstream.getInputStream(), upstream.getOutputStream())
    }

    /** Reads up to the blank line that ends the request head, so the response is not sent early. */
    private fun readRequestHead(input: InputStream) {
        var matched = 0
        while (matched < 4) {
            val byte = input.read()
            if (byte == -1) throw IOException("closed before request head")
            matched = if (byte == CRLF[matched]) matched + 1 else if (byte == '\r'.code) 1 else 0
        }
    }

    /** Reads exactly [count] bytes so a split TCP segment cannot misalign the SOCKS handshake. */
    private fun readFully(input: InputStream, count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(buffer, read, count - read)
            if (n == -1) throw IOException("closed after $read of $count bytes")
            read += n
        }
        return buffer
    }

    private fun relay(clientIn: InputStream, clientOut: OutputStream, upIn: InputStream, upOut: OutputStream) {
        val up = Thread {
            copy(clientIn, upOut)
            runCatching { upOut.flush() }
        }.apply { isDaemon = true }
        up.start()
        copy(upIn, clientOut)
        runCatching { clientOut.flush() }
    }

    private fun copy(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(8192)
        while (true) {
            val read = try {
                from.read(buffer)
            } catch (e: IOException) {
                return
            }
            if (read == -1) return
            try {
                to.write(buffer, 0, read)
                to.flush()
            } catch (e: IOException) {
                return
            }
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        val CRLF = intArrayOf('\r'.code, '\n'.code, '\r'.code, '\n'.code)
        val HTTP_204 = "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            .toByteArray()
        val SOCKS_FAILURE = byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0)
    }
}