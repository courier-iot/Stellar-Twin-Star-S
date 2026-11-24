package com.stellar.twinstar.sync

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.stellar.twinstar.Graph
import com.stellar.twinstar.R
import com.stellar.twinstar.TwinStarApp
import com.stellar.twinstar.crypto.Crypto
import com.stellar.twinstar.data.Prefs
import com.stellar.twinstar.data.Repo
import com.stellar.twinstar.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

data class SyncPrompt(val pairId: String, val peerName: String, val theyNeed: Int, val weNeed: Int)

class SyncEngine(private val ctx: Context, private val repo: Repo, private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handleLock = Mutex()
    private val holders = mutableSetOf<String>()
    private val jobs = mutableListOf<Job>()
    @Volatile private var running = false

    private class PeerState {
        @Volatile var lastHeard = 0L
        @Volatile var live = false
        @Volatile var lanAddr: InetSocketAddress? = null
        @Volatile var lanSeen = 0L
        @Volatile var lastPing = 0L
        var pendingPush: List<String> = emptyList()
        var pendingPull: List<String> = emptyList()
    }

    private class PendingPair(val key: ByteArray, val pairId: String, val topic: String, val expires: Long)

    private val states = ConcurrentHashMap<String, PeerState>()
    private val keys = ConcurrentHashMap<String, ByteArray>()
    private val topicToPair = ConcurrentHashMap<String, String>()
    private val names = ConcurrentHashMap<String, String>()
    private val peerDevice = ConcurrentHashMap<String, String>()
    private val seen = BoundedSet(5000)
    private val recentlySent = ConcurrentHashMap<String, Long>()
    @Volatile private var pending: PendingPair? = null

    val online = MutableStateFlow<Set<String>>(emptySet())
    val prompts = MutableStateFlow<Map<String, SyncPrompt>>(emptyMap())
    val paired = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val lastSyncAt = MutableStateFlow(0L)

    private val relay = RelayClient(prefs, { topic, bytes -> topicToPair[topic]?.let { onCipher(it, bytes) } }, { onRelayUp() })
    val relayCount get() = relay.connected
    private val lan = LanTransport(ctx, { prefs.deviceId }, { pairId, bytes -> onCipher(pairId, bytes) }, { dev, addr -> onLanAnnounce(dev, addr) })

    // ---------- lifecycle ----------
    @Synchronized fun acquire(tag: String) { holders += tag; if (!running) start() }
    @Synchronized fun release(tag: String) { holders -= tag; if (holders.isEmpty() && running) stop() }

    private fun start() {
        running = true
        jobs += scope.launch {
            reloadPairs()
            relay.start()
            if (prefs.lanEnabled.value) lan.start()
            helloAll()
        }
        jobs += scope.launch { repo.changes.collect { onLocalChange(it.kind, it.id) } }
        jobs += scope.launch { ticker() }
    }

    private fun stop() {
        running = false
        jobs.forEach { it.cancel() }; jobs.clear()
        relay.stop(); lan.stop()
        states.values.forEach { it.live = false; it.lastHeard = 0 }
        online.value = emptySet()
    }

    fun restartTransports() {
        scope.launch {
            if (!running) return@launch
            relay.stop(); lan.stop()
            relay.start()
            if (prefs.lanEnabled.value) lan.start()
        }
    }

    suspend fun reloadPairs() {
        val pairs = repo.dao.allPairs()
        val ids = pairs.map { it.pairId }.toSet()
        keys.keys.retainAll(ids); names.keys.retainAll(ids); peerDevice.keys.retainAll(ids); states.keys.retainAll(ids)
        topicToPair.clear()
        for (p in pairs) {
            val k = try { repo.pairKey(p) } catch (e: Exception) { continue }
            keys[p.pairId] = k
            names[p.pairId] = p.peerName
            peerDevice[p.pairId] = p.peerDeviceId
            states.getOrPut(p.pairId) { PeerState() }
            topicToPair[topicFor(k)] = p.pairId
        }
        pending?.let { if (it.expires > now()) topicToPair[it.topic] = it.pairId }
        relay.setTopics(topicToPair.keys.toSet())
        online.value = online.value.filter { it in ids }.toSet()
    }

    private fun now() = System.currentTimeMillis()
    private fun topicFor(key: ByteArray) = Crypto.hex(Crypto.sha256("twinstar-topic-v1".toByteArray(), key))
    private fun pairIdFor(key: ByteArray) = Crypto.hex(Crypto.sha256("twinstar-pair-v1".toByteArray(), key)).take(24)

    // ---------- sending ----------
    private suspend fun send(pairId: String, msg: JSONObject, lanOnly: InetSocketAddress? = null) {
        val key = keys[pairId] ?: pending?.takeIf { it.pairId == pairId }?.key ?: return
        msg.put("mid", Crypto.randomHex(8)); msg.put("from", prefs.deviceId); msg.put("ts", now())
        val ct = Crypto.gcmEncrypt(key, msg.toString().toByteArray(Charsets.UTF_8), pairId.toByteArray())
        if (lanOnly != null) { lan.send(lanOnly, pairId, ct); return }
        val st = states[pairId]
        val addr = st?.lanAddr
        if (prefs.lanEnabled.value && st != null && addr != null && now() - st.lanSeen < 20_000 && lan.send(addr, pairId, ct)) return
        relay.publish(topicFor(key), ct)
    }

    private fun msg(type: String) = JSONObject().put("type", type)

    private suspend fun sendHello(pairId: String, reply: Boolean) {
        val m = repo.manifest(pairId)
        val arr = JSONArray()
        for ((k, v) in m) arr.put("$k|${v.u}|${v.rev}")
        send(pairId, msg("hello").put("reply", reply).put("name", prefs.deviceName.value).put("m", arr))
    }

    private suspend fun helloAll() { for (p in keys.keys.toList()) sendHello(p, false) }

    private fun onRelayUp() { scope.launch { for ((p, st) in states) if (!isOnline(st)) sendHello(p, false) } }

    private fun isOnline(st: PeerState) = st.lastHeard > 0 && now() - st.lastHeard < 75_000

    private suspend fun ticker() {
        while (scope.isActive && running) {
            delay(5_000)
            val t = now()
            for ((p, st) in states) {
                if (t - st.lastPing > 25_000) { st.lastPing = t; send(p, msg("ping")) }
                if (st.lastHeard > 0 && !isOnline(st) && st.live) { st.live = false }
            }
            online.value = states.filter { isOnline(it.value) }.keys.toSet()
            pending?.let { if (it.expires < t) cancelPairing() }
            recentlySent.entries.removeIf { t - it.value > 120_000 }
            if (t - prefs.lastTrashSweep > 6 * 3600 * 1000L) repo.sweepTrash()
        }
    }

    // ---------- receiving ----------
    private fun onCipher(pairId: String, ct: ByteArray) {
        scope.launch {
            val key = keys[pairId] ?: pending?.takeIf { it.pairId == pairId }?.key ?: return@launch
            val plain = try { Crypto.gcmDecrypt(key, ct, pairId.toByteArray()) } catch (e: Exception) { return@launch }
            val m = try { JSONObject(String(plain, Charsets.UTF_8)) } catch (e: Exception) { return@launch }
            if (m.optString("from") == prefs.deviceId) return@launch
            if (!seen.add(m.optString("mid"))) return@launch
            handleLock.withLock { try { handle(pairId, m) } catch (_: Exception) { } }
        }
    }

    private fun onLanAnnounce(dev: String, addr: InetSocketAddress) {
        for ((p, d) in peerDevice) if (d == dev) {
            val st = states.getOrPut(p) { PeerState() }
            val first = st.lanAddr == null || now() - st.lanSeen > 30_000
            st.lanAddr = addr; st.lanSeen = now()
            if (first && !isOnline(st)) scope.launch { sendHello(p, false) }
        }
    }

    private suspend fun handle(pairId: String, m: JSONObject) {
        val type = m.optString("type")
        if (type == "pair_confirm") { onPairConfirm(pairId, m); return }
        if (!keys.containsKey(pairId)) return
        val st = states.getOrPut(pairId) { PeerState() }
        val wasOnline = isOnline(st)
        st.lastHeard = now()
        if (!wasOnline) {
            online.value = online.value + pairId
            repo.dao.touchPair(pairId, now())
            if (type != "hello") sendHello(pairId, false)
        }
        when (type) {
            "hello" -> onHello(pairId, st, m)
            "put" -> onPut(pairId, m)
            "req" -> onReq(pairId, m)
            "unshare" -> {
                repo.removeSharesFromPeer(pairId, m.optJSONArray("n").strings(), m.optJSONArray("f").strings())
            }
            "unpair" -> { repo.removePair(pairId); reloadPairs(); clearPrompt(pairId) }
        }
    }

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }

    private suspend fun onHello(pairId: String, st: PeerState, m: JSONObject) {
        m.optString("name").takeIf { it.isNotBlank() }?.let { names[pairId] = it }
        if (!m.optBoolean("reply")) sendHello(pairId, true)
        val theirs = HashMap<String, Repo.ManifestEntry>()
        val arr = m.optJSONArray("m") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val p = arr.getString(i).split('|')
            if (p.size >= 2) theirs[p[0]] = Repo.ManifestEntry(p[1].toLongOrNull() ?: 0, p.getOrElse(2) { "" })
        }
        val ours = repo.manifest(pairId)
        fun newer(a: Repo.ManifestEntry, b: Repo.ManifestEntry) = a.u > b.u || (a.u == b.u && a.rev > b.rev)
        val push = ours.filter { (k, v) -> theirs[k]?.let { newer(v, it) } ?: true }.keys.toList()
        val pull = theirs.filter { (k, v) -> ours[k]?.let { newer(v, it) } ?: true }.keys.toList() +
            repo.missingAttachmentsFor(pairId).map { "a:$it" }
        if (push.isEmpty() && pull.isEmpty()) {
            st.live = true; clearPrompt(pairId); lastSyncAt.value = now(); return
        }
        if (st.live || !prefs.askBeforeCatchup.value) {
            resolve(pairId, st, push, pull)
        } else {
            st.pendingPush = push; st.pendingPull = pull
            val pushNotes = push.count { it.startsWith("n:") }
            val pullNotes = pull.count { it.startsWith("n:") }
            prompts.value = prompts.value + (pairId to SyncPrompt(pairId, names[pairId] ?: "Linked device", pushNotes, pullNotes))
            if (!Graph.foreground) notifyPrompt(names[pairId] ?: "A linked device")
        }
    }

    fun acceptPrompt(pairId: String) {
        scope.launch {
            val st = states[pairId] ?: return@launch
            handleLock.withLock { resolve(pairId, st, st.pendingPush, st.pendingPull) }
            clearPrompt(pairId)
        }
    }

    fun deferPrompt(pairId: String) {
        states[pairId]?.let { it.pendingPush = emptyList(); it.pendingPull = emptyList() }
        clearPrompt(pairId)
    }

    private fun clearPrompt(pairId: String) {
        if (prompts.value.containsKey(pairId)) prompts.value = prompts.value - pairId
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_PROMPT)
    }

    private suspend fun resolve(pairId: String, st: PeerState, push: List<String>, pull: List<String>) {
        st.live = true
        st.pendingPush = emptyList(); st.pendingPull = emptyList()
        // folders first so notes land in the right place
        val folders = push.filter { it.startsWith("f:") }
        if (folders.isNotEmpty()) {
            val recs = JSONArray()
            for (k in folders) repo.dao.folder(k.substring(2))?.let { recs.put(repo.folderRecord(it, pairId)) }
            send(pairId, msg("put").put("recs", recs))
        }
        for (k in push.filter { it.startsWith("n:") }) {
            repo.dao.note(k.substring(2))?.let { sendNote(pairId, it, withFolder = false) }
        }
        if (pull.isNotEmpty()) pull.chunked(200).forEach { send(pairId, msg("req").put("keys", JSONArray(it))) }
        lastSyncAt.value = now()
    }

    private suspend fun sendNote(pairId: String, e: com.stellar.twinstar.data.NoteEntity, withFolder: Boolean = true) {
        val recs = JSONArray()
        if (withFolder && e.folderId != null) repo.dao.folder(e.folderId)?.let { recs.put(repo.folderRecord(it, pairId)) }
        recs.put(repo.noteRecord(e))
        recentlySent["$pairId|n:${e.id}|${e.rev}"] = now()
        send(pairId, msg("put").put("recs", recs))
    }

    private suspend fun onPut(pairId: String, m: JSONObject) {
        val recs = m.optJSONArray("recs") ?: return
        val wanted = ArrayList<String>()
        for (i in 0 until recs.length()) {
            val r = recs.getJSONObject(i)
            when (r.optString("t")) {
                "f" -> repo.applyRemoteFolder(pairId, r)
                "a" -> repo.applyRemoteAttachment(r)
                "n" -> {
                    val res = repo.applyRemoteNote(pairId, r)
                    val id = r.getString("id")
                    if (res.localNewer) repo.dao.note(id)?.let { sendNote(pairId, it) }
                    if (res.applied) forward(id, exclude = pairId)
                    wanted += res.missingAttachments.map { "a:$it" }
                }
            }
        }
        if (wanted.isNotEmpty()) send(pairId, msg("req").put("keys", JSONArray(wanted.distinct())))
        lastSyncAt.value = now()
    }

    private suspend fun onReq(pairId: String, m: JSONObject) {
        val ks = m.optJSONArray("keys").strings()
        val folderRecs = JSONArray()
        for (k in ks) {
            val id = k.substring(2)
            when {
                k.startsWith("f:") -> repo.dao.folder(id)?.let { folderRecs.put(repo.folderRecord(it, pairId)) }
                k.startsWith("n:") -> {
                    val e = repo.dao.note(id) ?: continue
                    if (!repo.isNoteSharedWith(id, pairId)) continue
                    if (recentlySent.containsKey("$pairId|n:$id|${e.rev}")) continue
                    sendNote(pairId, e, withFolder = false)
                }
                k.startsWith("a:") -> repo.attachmentRecord(id)?.let { send(pairId, msg("put").put("recs", JSONArray().put(it))) }
            }
        }
        if (folderRecs.length() > 0) send(pairId, msg("put").put("recs", folderRecs))
    }

    /** Pass a remote change on to other linked devices that share this note. */
    private suspend fun forward(noteId: String, exclude: String) {
        val e = repo.dao.note(noteId) ?: return
        for (p in repo.pairsForNote(e)) if (p != exclude && isLive(p)) sendNote(p, e)
    }

    private fun isLive(pairId: String) = states[pairId]?.let { it.live && isOnline(it) } == true

    // ---------- local changes ----------
    private suspend fun onLocalChange(kind: Char, id: String) {
        when (kind) {
            'n' -> {
                val e = repo.dao.note(id) ?: return
                for (p in repo.pairsForNote(e)) if (isLive(p)) sendNote(p, e)
            }
            'f' -> {
                val f = repo.dao.folder(id) ?: return
                val targets = repo.dao.folderPairs(id).toMutableSet()
                repo.dao.notesInFolder(id).forEach { targets += repo.dao.notePairs(it.id) }
                for (p in targets) if (isLive(p)) send(p, msg("put").put("recs", JSONArray().put(repo.folderRecord(f, p))))
            }
        }
    }

    fun shareNote(noteId: String, pairId: String, on: Boolean) {
        scope.launch {
            repo.setNoteShare(noteId, pairId, on)
            if (on) { if (isLive(pairId)) repo.dao.note(noteId)?.let { sendNote(pairId, it) } }
            else send(pairId, msg("unshare").put("n", JSONArray().put(noteId)).put("f", JSONArray()))
        }
    }

    fun shareFolder(folderId: String, pairId: String, on: Boolean) {
        scope.launch {
            repo.setFolderShare(folderId, pairId, on)
            if (on) {
                if (!isLive(pairId)) return@launch
                repo.dao.folder(folderId)?.let { send(pairId, msg("put").put("recs", JSONArray().put(repo.folderRecord(it, pairId)))) }
                repo.dao.notesInFolder(folderId).forEach { sendNote(pairId, it, withFolder = false) }
            } else send(pairId, msg("unshare").put("n", JSONArray()).put("f", JSONArray().put(folderId)))
        }
    }

    fun syncNow() { scope.launch { helloAll() } }

    // ---------- pairing ----------
    /** Starts showing a pairing code. Returns the QR payload. */
    fun beginPairing(): String {
        val key = Crypto.random(32)
        val p = PendingPair(key, pairIdFor(key), topicFor(key), now() + 5 * 60_000)
        pending = p
        topicToPair[p.topic] = p.pairId
        relay.setTopics(topicToPair.keys.toSet())
        return JSONObject().apply {
            put("app", "twinstar"); put("v", 1); put("id", prefs.deviceId); put("n", prefs.deviceName.value)
            put("k", Crypto.b64(key)); lan.localIp()?.let { put("ip", it) }; put("port", lan.port)
        }.toString()
    }

    fun cancelPairing() {
        val p = pending ?: return
        pending = null
        topicToPair.remove(p.topic)
        relay.setTopics(topicToPair.keys.toSet())
    }

    /** Called on the scanning device. Returns the peer's name, or null if the code is not valid. */
    suspend fun completePairing(qr: String): String? {
        val j = try { JSONObject(qr) } catch (e: Exception) { return null }
        if (j.optString("app") != "twinstar") return null
        val key = try { Crypto.unb64(j.getString("k")) } catch (e: Exception) { return null }
        if (key.size != 32) return null
        val peerId = j.optString("id"); val name = j.optString("n").ifBlank { "Linked device" }
        if (peerId.isBlank() || peerId == prefs.deviceId) return null
        val pairId = pairIdFor(key)
        if (repo.dao.pair(pairId) == null) repo.addPair(pairId, peerId, name, key)
        reloadPairs()
        val st = states.getOrPut(pairId) { PeerState() }
        val ip = j.optString("ip"); val port = j.optInt("port")
        val lanAddr = if (ip.isNotBlank() && port > 0) InetSocketAddress(ip, port) else null
        if (lanAddr != null) { st.lanAddr = lanAddr; st.lanSeen = now() }
        scope.launch {
            for (wait in listOf(0L, 4_000L, 12_000L)) {
                delay(wait)
                if (isOnline(st)) break
                val confirm = msg("pair_confirm").put("id", prefs.deviceId).put("n", prefs.deviceName.value)
                if (lanAddr != null) send(pairId, JSONObject(confirm.toString()), lanOnly = lanAddr)
                send(pairId, confirm)
            }
        }
        scope.launch { delay(1_500); sendHello(pairId, false) }
        return name
    }

    private suspend fun onPairConfirm(pairId: String, m: JSONObject) {
        val p = pending
        if (p != null && p.pairId == pairId && p.expires > now()) {
            val name = m.optString("n").ifBlank { "Linked device" }
            repo.addPair(pairId, m.optString("id"), name, p.key)
            pending = null
            reloadPairs()
            paired.tryEmit(name)
            sendHello(pairId, false)
        }
    }

    fun unpair(pairId: String) {
        scope.launch {
            send(pairId, msg("unpair"))
            repo.removePair(pairId)
            reloadPairs()
            clearPrompt(pairId)
        }
    }

    fun peerName(pairId: String) = names[pairId]

    private fun notifyPrompt(name: String) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val pi = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, TwinStarApp.CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_star)
            .setContentTitle("Out of sync with $name")
            .setContentText("Tap to review and sync")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_PROMPT, n)
    }

    companion object { const val NOTIF_PROMPT = 42 }
}
