package com.stellar.twinstar.sync

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * Same-network transport: UDP broadcast to find linked devices, short TCP connections to deliver
 * encrypted frames. Frames are already sealed with the pair key.
 */
class LanTransport(
    context: Context,
    private val deviceId: () -> String,
    private val onFrame: (pairId: String, bytes: ByteArray) -> Unit,
    private val onAnnounce: (deviceId: String, addr: InetSocketAddress) -> Unit,
) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableListOf<Job>()

    val port: Int get() = server?.localPort ?: 0

    fun start() {
        if (server != null) return
        try {
            lock = wifi.createMulticastLock("twinstar").apply { setReferenceCounted(false); acquire() }
        } catch (_: Exception) { }
        val ss = try { ServerSocket(0) } catch (e: Exception) { return }
        server = ss
        jobs += scope.launch {
            while (isActive) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                launch { serve(s) }
            }
        }
        try {
            udp = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(UDP_PORT)) }
        } catch (_: Exception) {
            udp = try { DatagramSocket().apply { broadcast = true } } catch (e: Exception) { null }
        }
        jobs += scope.launch { listen() }
        jobs += scope.launch {
            while (isActive) { announce(); delay(5_000) }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }; jobs.clear()
        try { server?.close() } catch (_: Exception) { }
        try { udp?.close() } catch (_: Exception) { }
        server = null; udp = null
        try { lock?.release() } catch (_: Exception) { }
        lock = null
    }

    private fun serve(s: Socket) {
        try {
            s.use { sock ->
                sock.soTimeout = 15_000
                val inp = DataInputStream(BufferedInputStream(sock.getInputStream()))
                while (true) {
                    val pairId = try { inp.readUTF() } catch (e: Exception) { break }
                    val len = inp.readInt()
                    if (len <= 0 || len > 64 * 1024 * 1024) break
                    val buf = ByteArray(len)
                    inp.readFully(buf)
                    onFrame(pairId, buf)
                }
            }
        } catch (_: Exception) { }
    }

    private fun listen() {
        val sock = udp ?: return
        val buf = ByteArray(512)
        while (!sock.isClosed) {
            try {
                val p = DatagramPacket(buf, buf.size)
                sock.receive(p)
                val parts = String(p.data, 0, p.length, Charsets.UTF_8).split('|')
                if (parts.size == 3 && parts[0] == "TS1" && parts[1] != deviceId()) {
                    val port = parts[2].toIntOrNull() ?: continue
                    onAnnounce(parts[1], InetSocketAddress(p.address, port))
                }
            } catch (e: Exception) {
                if (sock.isClosed) break
            }
        }
    }

    private fun announce() {
        val sock = udp ?: return
        val p = port
        if (p == 0) return
        val msg = "TS1|${deviceId()}|$p".toByteArray(Charsets.UTF_8)
        val targets = mutableSetOf<InetAddress>()
        try { targets += InetAddress.getByName("255.255.255.255") } catch (_: Exception) { }
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) ia.broadcast?.let { targets += it }
            }
        } catch (_: Exception) { }
        for (t in targets) try { sock.send(DatagramPacket(msg, msg.size, t, UDP_PORT)) } catch (_: Exception) { }
    }

    suspend fun send(addr: InetSocketAddress, pairId: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { s ->
                s.connect(addr, 2_500)
                s.soTimeout = 10_000
                val out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                out.writeUTF(pairId)
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()
            }
            true
        } catch (e: Exception) { false }
    }

    fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    } catch (e: Exception) { null }

    companion object { const val UDP_PORT = 47823 }
}
