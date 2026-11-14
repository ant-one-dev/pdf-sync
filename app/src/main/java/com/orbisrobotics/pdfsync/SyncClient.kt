package com.orbisrobotics.pdfsync

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Connexion du Client à l'Expert, avec reconnexion automatique.
 * Les callbacks arrivent sur un thread réseau.
 */
class SyncClient(
    private val deviceId: String,
    private val name: String,
    private val dir: File,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(text: String, connected: Boolean)
        fun onPdfProgress(received: Long, total: Long)
        fun onPdfReceived(file: File, name: String)
        fun onPage(page: Int)
        fun onSessionEnded()
    }

    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    @Volatile private var cachedPdf = 0L

    fun start() {
        running = true
        dir.mkdirs()
        thread(name = "sync-client", isDaemon = true) {
            var retry = false
            while (running) {
                try {
                    listener.onStatus(if (retry) "Reconnexion à l'Expert…" else "Connexion à l'Expert…", false)
                    val s = Socket()
                    socket = s
                    s.tcpNoDelay = true
                    s.keepAlive = true
                    s.connect(InetSocketAddress(NetworkSettings.HOST_IP, Protocol.PORT), 5000)
                    s.soTimeout = Protocol.READ_TIMEOUT_MS
                    val input = DataInputStream(BufferedInputStream(s.getInputStream(), 64 * 1024))
                    val out = DataOutputStream(s.getOutputStream())

                    if (input.readByte().toInt() != Protocol.HELLO) throw IOException("pas un Expert")
                    out.writeLong(cachedPdf)
                    out.writeUTF(deviceId)
                    out.writeUTF(name)
                    out.flush()

                    if (!waitAccepted(input)) break
                    retry = false
                    listener.onStatus("Connecté à l'Expert", true)
                    readLoop(input)
                } catch (_: IOException) {
                } finally {
                    try { socket?.close() } catch (_: IOException) {}
                    socket = null
                }
                if (!running) break
                retry = true
                listener.onStatus("Expert injoignable, nouvel essai…", false)
                try {
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: IOException) {}
    }

    // false = refusé, on arrête
    private fun waitAccepted(input: DataInputStream): Boolean {
        while (true) {
            when (input.readByte().toInt()) {
                Protocol.ACCEPTED -> return true
                Protocol.PING -> Unit
                else -> throw IOException("réponse inattendue")
            }
        }
    }

    private fun readLoop(input: DataInputStream) {
        while (running) {
            when (input.readByte().toInt()) {
                Protocol.PDF -> receivePdf(input)
                Protocol.PAGE -> listener.onPage(input.readInt())
                Protocol.PING -> Unit
                Protocol.END -> {
                    running = false
                    cachedPdf = 0L
                    listener.onSessionEnded()
                    return
                }
                else -> throw IOException("message inconnu")
            }
        }
    }

    private fun receivePdf(input: DataInputStream) {
        val id = input.readLong()
        val name = input.readUTF()
        val size = input.readLong()
        val file = File(dir, "doc_${System.nanoTime()}.pdf")
        try {
            file.outputStream().buffered(64 * 1024).use { o ->
                val buf = ByteArray(64 * 1024)
                var left = size
                var lastReport = 0L
                listener.onPdfProgress(0, size)
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw EOFException()
                    o.write(buf, 0, n)
                    left -= n
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 200) {
                        lastReport = now
                        listener.onPdfProgress(size - left, size)
                    }
                }
            }
        } catch (e: IOException) {
            file.delete()
            throw e
        }
        cachedPdf = id
        listener.onPdfProgress(size, size)
        listener.onPdfReceived(file, name)
    }
}
