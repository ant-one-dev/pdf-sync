package com.orbisrobotics.pdfsync

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Serveur de l'Expert. Il n'écoute que sur l'adresse du groupe Wi-Fi Direct,
 * jamais sur un autre réseau auquel la tablette serait connectée.
 */
class SyncServer(private val listener: Listener) {

    // appelés depuis des threads réseau
    interface Listener {
        fun onClientCountChanged(count: Int)
    }

    private class Pdf(val file: File, val name: String, val id: Long)

    @Volatile private var running = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var pdf: Pdf? = null
    @Volatile private var page = 0

    private val clients = CopyOnWriteArrayList<Client>()
    private val ids = AtomicInteger()
    private var pinger: ScheduledExecutorService? = null

    fun start() {
        running = true
        thread(name = "sync-accept", isDaemon = true) { acceptLoop() }
        pinger = Executors.newSingleThreadScheduledExecutor().also {
            it.scheduleWithFixedDelay({
                for (c in clients) c.send { o -> o.writeByte(Protocol.PING) }
            }, Protocol.PING_MS, Protocol.PING_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun acceptLoop() {
        val address = InetAddress.getByName(NetworkSettings.HOST_IP)
        while (running) {
            // l'adresse n'existe qu'une fois le groupe créé, et peut disparaître si le Wi-Fi se coupe
            val ss = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(address, Protocol.PORT))
                }
            } catch (e: IOException) {
                pause(1000)
                continue
            }
            serverSocket = ss
            try {
                while (running) {
                    val s = ss.accept()
                    s.tcpNoDelay = true
                    s.keepAlive = true
                    Client(s).start()
                }
            } catch (_: IOException) {
            } finally {
                try { ss.close() } catch (_: IOException) {}
            }
            if (running) pause(1000)
        }
    }

    /** Fin de séance : END à tout le monde (=> effacement côté Client). */
    fun stop() {
        running = false
        pinger?.shutdownNow()
        try { serverSocket?.close() } catch (_: IOException) {}
        clients.forEach { it.end() }
        clients.clear()
    }

    fun connectedClients(): List<Pair<Int, String>> = clients.map { it.id to it.name }

    fun setPdf(file: File, name: String, page: Int) {
        val p = Pdf(file, name, System.currentTimeMillis())
        pdf = p
        this.page = page
        for (c in clients) c.send { o ->
            writePdf(o, p)
            writePage(o, page)
        }
    }

    fun setPage(page: Int) {
        this.page = page
        for (c in clients) c.send { o -> writePage(o, page) }
    }

    private fun writePdf(o: DataOutputStream, p: Pdf) {
        o.writeByte(Protocol.PDF)
        o.writeLong(p.id)
        o.writeUTF(p.name)
        o.writeLong(p.file.length())
        p.file.inputStream().use { it.copyTo(o, 64 * 1024) }
    }

    private fun writePage(o: DataOutputStream, page: Int) {
        o.writeByte(Protocol.PAGE)
        o.writeInt(page)
    }

    private fun pause(ms: Long) = try {
        Thread.sleep(ms)
    } catch (_: InterruptedException) {
    }

    private inner class Client(private val socket: Socket) {
        // un exécuteur par client : l'ordre des messages est garanti et un client lent ne bloque pas les autres
        private val sender: ExecutorService = Executors.newSingleThreadExecutor()
        private val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 64 * 1024))
        @Volatile private var closed = false
        val id = ids.incrementAndGet()
        @Volatile var name = "Tablette"
        @Volatile var deviceId = ""
        private var cachedPdf = 0L

        fun start() = thread(name = "sync-client", isDaemon = true) {
            try {
                val input = DataInputStream(socket.getInputStream())
                send { o -> o.writeByte(Protocol.HELLO) }

                socket.soTimeout = 10_000
                cachedPdf = input.readLong()
                deviceId = input.readUTF().take(64)
                name = input.readUTF().trim().take(40).ifEmpty { "Tablette" }
                socket.soTimeout = 0

                admit()
                // on lit jusqu'à la fermeture pour détecter tout de suite un départ
                while (input.read() >= 0) {
                }
            } catch (_: IOException) {
            } finally {
                close()
            }
        }

        fun admit() {
            if (closed) return
            clients.add(this)
            listener.onClientCountChanged(clients.size)
            send { o ->
                o.writeByte(Protocol.ACCEPTED)
                pdf?.let {
                    if (it.id != cachedPdf) writePdf(o, it) // déjà reçu avant une coupure : pas besoin de renvoyer
                    writePage(o, page)
                }
            }
        }

        fun end() {
            send { o -> o.writeByte(Protocol.END) }
            closeLater()
        }

        fun send(block: (DataOutputStream) -> Unit) {
            if (closed) return
            try {
                sender.execute {
                    if (closed) return@execute
                    try {
                        block(out)
                        out.flush()
                    } catch (e: IOException) {
                        close()
                    }
                }
            } catch (_: Exception) {
                // exécuteur arrêté
            }
        }

        // laisse partir les messages en file avant de fermer
        private fun closeLater() {
            sender.shutdown()
            thread(isDaemon = true) {
                try { sender.awaitTermination(2, TimeUnit.SECONDS) } catch (_: InterruptedException) {}
                close()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            try { socket.close() } catch (_: IOException) {}
            sender.shutdownNow()
            if (clients.remove(this)) listener.onClientCountChanged(clients.size)
        }
    }
}
