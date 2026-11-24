package com.stellar.twinstar.sync

import com.stellar.twinstar.crypto.Crypto
import com.stellar.twinstar.crypto.Schnorr
import com.stellar.twinstar.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Blind internet relay over public Nostr relays. Everything sent is already AES-GCM encrypted with
 * the pair key; relays only ever see ciphertext on an unguessable topic tag. Ephemeral event kind,
 * so relays forward live and do not store.
 */
class RelayClient(private val prefs: Prefs, private val onPayload: (topic: String, bytes: ByteArray) -> Unit, private val onUp: () -> Unit) {
    private val client = OkHttpClient.Builder().pingInterval(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val open = ConcurrentHashMap<String, WebSocket>()
    private val connecting = ConcurrentHashMap<String, WebSocket>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var jobs = mutableListOf<Job>()
    private val out = Channel<String>(Channel.UNLIMITED)
    private val waiting = ArrayDeque<Pair<Long, String>>()
    private val parts = ConcurrentHashMap<String, Assembly>()
    private val done = BoundedSet(4000)
    @Volatile private var topics: Set<String> = emptySet()
    @Volatile private var running = false
    val connected = MutableStateFlow(0)

    private val sk: ByteArray by lazy { prefs.nostrSecret() }
    private val pkHex: String by lazy { Crypto.hex(Schnorr.pubkey(sk)) }

    private class Assembly(val total: Int, val time: Long) { val chunks = arrayOfNulls<ByteArray>(total) }

    fun start() {
        if (running) return
        running = true
        jobs += scope.launch { sender() }
        jobs += scope.launch {
            while (isActive) {
                for (url in prefs.relayList()) if (!open.containsKey(url) && !connecting.containsKey(url)) connect(url)
                // drop relays removed from settings
                for (url in open.keys.toList()) if (url !in prefs.relayList()) open.remove(url)?.close(1000, null)
                val cutoff = System.currentTimeMillis() - 120_000
                parts.entries.removeIf { it.value.time < cutoff }
                delay(15_000)
            }
        }
    }

    fun stop() {
        running = false
        jobs.forEach { it.cancel() }; jobs.clear()
        open.values.forEach { it.close(1000, null) }; open.clear()
        connecting.values.forEach { it.cancel() }; connecting.clear()
        connected.value = 0
    }

    fun setTopics(t: Set<String>) {
        if (t == topics) return
        topics = t
        open.values.forEach { subscribe(it) }
    }

    private fun subscribe(ws: WebSocket) {
        val t = topics
        if (t.isEmpty()) { ws.send("""["CLOSE","ts"]"""); return }
        val filter = JSONObject().apply {
            put("kinds", JSONArray().put(KIND))
            put("#t", JSONArray(t.toList()))
            put("since", System.currentTimeMillis() / 1000 - 30)
        }
        ws.send(JSONArray().put("REQ").put("ts").put(filter).toString())
    }

    private fun connect(url: String) {
        val req = try { Request.Builder().url(url).build() } catch (e: Exception) { return }
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connecting.remove(url)
                if (!running) { webSocket.close(1000, null); return }
                open[url] = webSocket
                connected.value = open.size
                subscribe(webSocket)
                flushWaiting()
                onUp()
            }

            override fun onMessage(webSocket: WebSocket, text: String) { handle(text) }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { drop(url, webSocket) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { drop(url, webSocket) }
        })
        connecting[url] = ws
    }

    private fun drop(url: String, ws: WebSocket) {
        connecting.remove(url, ws)
        open.remove(url, ws)
        connected.value = open.size
    }

    private fun handle(text: String) {
        try {
            val arr = JSONArray(text)
            if (arr.optString(0) != "EVENT") return
            val ev = arr.getJSONObject(2)
            if (ev.optInt("kind") != KIND || ev.optString("pubkey") == pkHex) return
            val tags = ev.optJSONArray("tags") ?: return
            var topic: String? = null
            for (i in 0 until tags.length()) {
                val tg = tags.optJSONArray(i) ?: continue
                if (tg.optString(0) == "t") { topic = tg.optString(1); break }
            }
            val tp: String = topic ?: return
            if (tp !in topics) return
            val c = ev.optString("content").split(':', limit = 4)
            if (c.size != 4) return
            val mid = c[0]; val idx = c[1].toInt(); val total = c[2].toInt()
            if (total !in 1..4096 || idx !in 0 until total || done.contains(mid)) return
            val bytes = Crypto.unb64(c[3])
            if (total == 1) { done.add(mid); onPayload(tp, bytes); return }
            val a = parts.getOrPut(mid) { Assembly(total, System.currentTimeMillis()) }
            if (a.total != total) return
            synchronized(a) {
                a.chunks[idx] = bytes
                if (a.chunks.all { it != null }) {
                    parts.remove(mid); done.add(mid)
                    val all = java.io.ByteArrayOutputStream()
                    a.chunks.forEach { all.write(it!!) }
                    onPayload(tp, all.toByteArray())
                }
            }
        } catch (_: Exception) { }
    }

    fun publish(topic: String, bytes: ByteArray) {
        val mid = Crypto.randomHex(8)
        val total = (bytes.size + CHUNK - 1) / CHUNK
        for (i in 0 until maxOf(total, 1)) {
            val chunk = bytes.copyOfRange(i * CHUNK, minOf(bytes.size, (i + 1) * CHUNK))
            out.trySend(buildEvent(topic, "$mid:$i:${maxOf(total, 1)}:${Crypto.b64(chunk)}"))
        }
    }

    private fun buildEvent(topic: String, content: String): String {
        val created = System.currentTimeMillis() / 1000
        val tags = "[[\"t\",\"$topic\"]]"
        val c = esc(content)
        val serial = "[0,\"$pkHex\",$created,$KIND,$tags,\"$c\"]"
        val id = Crypto.sha256(serial.toByteArray(Charsets.UTF_8))
        val sig = Schnorr.sign(id, sk, Crypto.random(32))
        return "{\"id\":\"${Crypto.hex(id)}\",\"pubkey\":\"$pkHex\",\"created_at\":$created,\"kind\":$KIND,\"tags\":$tags,\"content\":\"$c\",\"sig\":\"${Crypto.hex(sig)}\"}"
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) when (ch) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t"); '\b' -> sb.append("\\b"); '\u000C' -> sb.append("\\f")
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    private suspend fun sender() {
        for (ev in out) {
            val sockets = open.values.toList()
            if (sockets.isEmpty()) {
                synchronized(waiting) {
                    waiting.addLast(System.currentTimeMillis() to ev)
                    while (waiting.size > 400) waiting.removeFirst()
                }
            } else {
                val msg = "[\"EVENT\",$ev]"
                sockets.forEach { it.send(msg) }
                delay(70)
            }
        }
    }

    private fun flushWaiting() {
        val cutoff = System.currentTimeMillis() - 180_000
        val list = synchronized(waiting) { val l = waiting.filter { it.first > cutoff }.map { it.second }; waiting.clear(); l }
        list.forEach { out.trySend(it) }
    }

    companion object {
        const val KIND = 20873
        const val CHUNK = 12_000
    }
}

class BoundedSet(private val max: Int) {
    private val set = LinkedHashSet<String>()
    @Synchronized fun add(s: String): Boolean {
        val added = set.add(s)
        if (set.size > max) { val it = set.iterator(); it.next(); it.remove() }
        return added
    }
    @Synchronized fun contains(s: String) = s in set
}
