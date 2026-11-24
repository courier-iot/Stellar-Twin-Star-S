package com.stellar.twinstar.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.stellar.twinstar.crypto.Crypto
import com.stellar.twinstar.crypto.KeystoreBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

data class Note(
    val id: String,
    val folderId: String?,
    val title: String,
    val body: String,
    val tags: List<String>,
    val pinned: Boolean,
    val locked: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean,
    val deletedAt: Long,
)

data class Folder(val id: String, val name: String)
data class Peer(val pairId: String, val deviceId: String, val name: String, val autoShareNew: Boolean, val lastSeen: Long)
data class Change(val kind: Char, val id: String)
data class ApplyResult(val applied: Boolean, val localNewer: Boolean, val missingAttachments: List<String>)

class Repo(private val ctx: Context, val dao: TsDao, private val prefs: Prefs) {
    private val mutex = Mutex()
    private val attDir = File(ctx.filesDir, "att").apply { mkdirs() }
    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 128)
    val changes: SharedFlow<Change> get() = _changes

    // ---------- encryption helpers ----------
    private fun enc(s: String): String = if (prefs.encryption.value && s.isNotEmpty()) KeystoreBox.sealString(s) else s
    private fun dec(s: String): String = try { KeystoreBox.openString(s) } catch (e: Exception) { "" }

    private fun joinTags(t: List<String>) = t.joinToString("\u001F")
    private fun splitTags(s: String) = if (s.isEmpty()) emptyList() else s.split("\u001F").filter { it.isNotBlank() }

    fun toModel(e: NoteEntity) = Note(
        e.id, e.folderId, dec(e.title), dec(e.body), splitTags(dec(e.tags)), e.pinned, e.locked,
        e.createdAt, e.updatedAt, e.deleted, e.deletedAt,
    )

    // ---------- flows ----------
    val notes: Flow<List<Note>> = dao.observeNotes().map { list -> list.map(::toModel) }.flowOn(Dispatchers.Default)
    val folders: Flow<List<Folder>> = dao.observeFolders().map { l -> l.map { Folder(it.id, dec(it.name)) }.sortedBy { it.name.lowercase() } }.flowOn(Dispatchers.Default)
    val peers: Flow<List<Peer>> = dao.observePairs().map { l -> l.map { Peer(it.pairId, it.peerDeviceId, it.peerName, it.autoShareNew, it.lastSeen) } }
    val noteShares: Flow<List<NoteShare>> = dao.observeNoteShares()
    val folderShares: Flow<List<FolderShare>> = dao.observeFolderShares()
    fun observeNote(id: String): Flow<Note?> = dao.observeNote(id).map { e -> e?.takeIf { !it.purged }?.let(::toModel) }.flowOn(Dispatchers.Default)

    private fun newRev() = Crypto.randomHex(6)
    private fun now() = System.currentTimeMillis()
    private fun appendHist(hist: String, rev: String): String =
        (hist.split(',').filter { it.isNotEmpty() } + rev).takeLast(32).joinToString(",")

    private fun emit(kind: Char, id: String) { _changes.tryEmit(Change(kind, id)) }

    // ---------- local note edits ----------
    suspend fun createNote(folderId: String?, body: String = ""): String = mutex.withLock {
        val id = UUID.randomUUID().toString()
        val t = now()
        dao.upsertNote(
            NoteEntity(id, folderId, "", enc(body), "", false, false, t, t, newRev(), "", false, 0, false, prefs.deviceId)
        )
        dao.allPairs().filter { it.autoShareNew }.forEach { dao.addNoteShare(NoteShare(id, it.pairId)) }
        emit('n', id)
        id
    }

    /** Applies [change] to the decrypted note; writes a new revision only when something actually changed. */
    suspend fun updateNote(id: String, change: (Note) -> Note): Unit = mutex.withLock {
        val cur = dao.note(id) ?: return@withLock
        if (cur.purged) return@withLock
        val old = toModel(cur)
        val nw = change(old)
        if (nw == old) return@withLock
        val t = maxOf(now(), cur.updatedAt + 1)
        dao.upsertNote(
            cur.copy(
                folderId = nw.folderId, title = enc(nw.title), body = enc(nw.body), tags = enc(joinTags(nw.tags)),
                pinned = nw.pinned, locked = nw.locked, deleted = nw.deleted, deletedAt = nw.deletedAt,
                updatedAt = t, rev = newRev(), revHistory = appendHist(cur.revHistory, cur.rev), origin = prefs.deviceId,
            )
        )
        emit('n', id)
    }

    suspend fun setContent(id: String, title: String, body: String) = updateNote(id) { it.copy(title = title, body = body) }
    suspend fun setPinned(id: String, v: Boolean) = updateNote(id) { it.copy(pinned = v) }
    suspend fun setLocked(id: String, v: Boolean) = updateNote(id) { it.copy(locked = v) }
    suspend fun setTags(id: String, tags: List<String>) = updateNote(id) { it.copy(tags = tags.map { t -> t.trim().removePrefix("#") }.filter { t -> t.isNotEmpty() }.distinct()) }
    suspend fun setFolder(id: String, folderId: String?) = updateNote(id) { it.copy(folderId = folderId) }
    suspend fun trash(id: String) = updateNote(id) { it.copy(deleted = true, deletedAt = now(), pinned = false) }
    suspend fun restore(id: String) = updateNote(id) { it.copy(deleted = false, deletedAt = 0) }

    /** Deletes forever; leaves a small tombstone so linked devices learn about it. */
    suspend fun purge(id: String): Unit = mutex.withLock { purgeLocked(id) }

    private suspend fun purgeLocked(id: String) {
        val cur = dao.note(id) ?: return
        if (cur.purged) return
        val shared = dao.notePairs(id).isNotEmpty() || (cur.folderId != null && dao.folderPairs(cur.folderId).isNotEmpty())
        if (!shared) {
            dao.deleteNote(id); dao.clearSharesOfNote(id)
        } else {
            dao.upsertNote(
                cur.copy(
                    title = "", body = "", tags = "", pinned = false, locked = false, deleted = true, purged = true,
                    updatedAt = maxOf(now(), cur.updatedAt + 1), rev = newRev(), revHistory = appendHist(cur.revHistory, cur.rev),
                )
            )
            emit('n', id)
        }
        cleanAttachments(id)
    }

    suspend fun emptyTrash() = mutex.withLock {
        dao.allNotes().filter { it.deleted && !it.purged }.forEach { purgeLocked(it.id) }
    }

    /** Trash auto-empties after 30 days; tombstones are forgotten after 90. */
    suspend fun sweepTrash() = mutex.withLock {
        val cutoff = now() - 30L * 24 * 3600 * 1000
        dao.trashOlderThan(cutoff).forEach { purgeLocked(it.id) }
        dao.dropTombstones(now() - 90L * 24 * 3600 * 1000)
        prefs.lastTrashSweep = now()
    }

    /** Removes a brand-new note that was left empty. */
    suspend fun discardIfEmpty(id: String) {
        val n = dao.note(id)?.let(::toModel) ?: return
        if (n.title.isBlank() && n.body.isBlank() && n.tags.isEmpty()) purge(id)
    }

    // ---------- folders ----------
    suspend fun createFolder(name: String): String {
        val id = UUID.randomUUID().toString()
        dao.upsertFolder(FolderEntity(id, enc(name.trim()), now(), false))
        emit('f', id)
        return id
    }

    suspend fun renameFolder(id: String, name: String) {
        val f = dao.folder(id) ?: return
        dao.upsertFolder(f.copy(name = enc(name.trim()), updatedAt = maxOf(now(), f.updatedAt + 1)))
        emit('f', id)
    }

    suspend fun deleteFolder(id: String) {
        val f = dao.folder(id) ?: return
        dao.upsertFolder(f.copy(deleted = true, updatedAt = maxOf(now(), f.updatedAt + 1)))
        emit('f', id)
    }

    // ---------- attachments ----------
    private fun attFile(id: String) = File(attDir, id)

    suspend fun addImage(uri: Uri, noteId: String): String? = withContext(Dispatchers.IO) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
            val raw = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@withContext null
            val maxSide = 1600f
            val scale = minOf(1f, maxSide / maxOf(raw.width, raw.height))
            val bmp = if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true) else raw
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            val id = UUID.randomUUID().toString().replace("-", "")
            storeAttachment(id, noteId, "image/jpeg", out.toByteArray())
            id
        } catch (e: Exception) { null }
    }

    private suspend fun storeAttachment(id: String, noteId: String, mime: String, bytes: ByteArray) {
        val encrypt = prefs.encryption.value
        attFile(id).writeBytes(if (encrypt) KeystoreBox.seal(bytes) else bytes)
        dao.upsertAttachment(AttachmentEntity(id, noteId, mime, now(), encrypt))
    }

    suspend fun loadAttachment(id: String): ByteArray? = withContext(Dispatchers.IO) {
        val a = dao.attachment(id) ?: return@withContext null
        val f = attFile(id)
        if (!f.exists()) return@withContext null
        try { if (a.encrypted) KeystoreBox.open(f.readBytes()) else f.readBytes() } catch (e: Exception) { null }
    }

    suspend fun hasAttachment(id: String) = dao.attachment(id) != null && attFile(id).exists()

    private suspend fun cleanAttachments(noteId: String) {
        val referenced = dao.allNotes().filter { !it.purged }.flatMap { attachmentRefs(dec(it.body)) }.toSet()
        dao.attachmentsFor(noteId).filter { it.id !in referenced }.forEach { attFile(it.id).delete(); dao.deleteAttachment(it.id) }
    }

    // ---------- shares ----------
    suspend fun setNoteShare(noteId: String, pairId: String, on: Boolean) {
        if (on) dao.addNoteShare(NoteShare(noteId, pairId)) else dao.removeNoteShare(noteId, pairId)
    }

    suspend fun setFolderShare(folderId: String, pairId: String, on: Boolean) {
        if (on) dao.addFolderShare(FolderShare(folderId, pairId)) else {
            dao.removeFolderShare(folderId, pairId)
            dao.notesInFolder(folderId).forEach { dao.removeNoteShare(it.id, pairId) }
        }
    }

    suspend fun setAutoShare(pairId: String, on: Boolean) {
        dao.pair(pairId)?.let { dao.upsertPair(it.copy(autoShareNew = on)) }
    }

    suspend fun pairsForNote(e: NoteEntity): Set<String> {
        val s = dao.notePairs(e.id).toMutableSet()
        if (e.folderId != null) s += dao.folderPairs(e.folderId)
        return s
    }

    data class ManifestEntry(val u: Long, val rev: String)

    /** Everything this device shares with [pairId]: "n:<id>" and "f:<id>" keys. */
    suspend fun manifest(pairId: String): Map<String, ManifestEntry> {
        val noteIds = dao.notesSharedWith(pairId).toSet()
        val folderIds = dao.foldersSharedWith(pairId).toSet()
        val out = HashMap<String, ManifestEntry>()
        val usedFolders = HashSet<String>(folderIds)
        for (n in dao.allNotes()) {
            if (n.id in noteIds || (n.folderId != null && n.folderId in folderIds)) {
                out["n:${n.id}"] = ManifestEntry(n.updatedAt, n.rev)
                n.folderId?.let { usedFolders += it }
            }
        }
        for (f in usedFolders) dao.folder(f)?.let { out["f:${it.id}"] = ManifestEntry(it.updatedAt, "") }
        return out
    }

    suspend fun missingAttachmentsFor(pairId: String): List<String> {
        val m = manifest(pairId)
        val ids = m.keys.filter { it.startsWith("n:") }.map { it.substring(2) }
        val out = ArrayList<String>()
        for (id in ids) {
            val e = dao.note(id) ?: continue
            if (e.purged) continue
            for (a in attachmentRefs(dec(e.body))) if (!hasAttachment(a)) out += a
        }
        return out.distinct()
    }

    suspend fun isNoteSharedWith(noteId: String, pairId: String): Boolean {
        val e = dao.note(noteId) ?: return false
        return pairId in pairsForNote(e)
    }

    // ---------- sync records ----------
    fun noteRecord(e: NoteEntity): JSONObject {
        val m = toModel(e)
        return JSONObject().apply {
            put("t", "n"); put("id", e.id); put("f", e.folderId ?: JSONObject.NULL)
            put("ti", m.title); put("b", m.body); put("tg", JSONArray(m.tags))
            put("p", e.pinned); put("l", e.locked); put("c", e.createdAt); put("u", e.updatedAt)
            put("r", e.rev); put("h", e.revHistory); put("d", e.deleted); put("da", e.deletedAt); put("x", e.purged)
            put("o", e.origin)
        }
    }

    suspend fun folderRecord(f: FolderEntity, pairId: String?): JSONObject = JSONObject().apply {
        put("t", "f"); put("id", f.id); put("nm", dec(f.name)); put("u", f.updatedAt); put("d", f.deleted)
        put("s", pairId != null && pairId in dao.folderPairs(f.id))
    }

    suspend fun attachmentRecord(id: String): JSONObject? {
        val a = dao.attachment(id) ?: return null
        val bytes = loadAttachment(id) ?: return null
        return JSONObject().apply { put("t", "a"); put("id", id); put("nid", a.noteId); put("mt", a.mime); put("data", Crypto.b64(bytes)) }
    }

    suspend fun applyRemoteNote(pairId: String?, j: JSONObject): ApplyResult = mutex.withLock {
        val id = j.getString("id")
        val folderId = if (j.isNull("f")) null else j.optString("f").ifEmpty { null }
        val title = j.optString("ti"); val body = j.optString("b")
        val tagsArr = j.optJSONArray("tg") ?: JSONArray()
        val tags = (0 until tagsArr.length()).map { tagsArr.getString(it) }
        val u = j.getLong("u"); val rev = j.getString("r"); val hist = j.optString("h")
        val incoming = NoteEntity(
            id, folderId, enc(title), enc(body), enc(joinTags(tags)), j.optBoolean("p"), j.optBoolean("l"),
            j.optLong("c", u), u, rev, hist, j.optBoolean("d"), j.optLong("da"), j.optBoolean("x"), j.optString("o"),
        )
        val local = dao.note(id)
        suspend fun share() {
            if (pairId == null) return
            val viaFolder = folderId != null && pairId in dao.folderPairs(folderId)
            if (!viaFolder) dao.addNoteShare(NoteShare(id, pairId))
        }
        fun missing(): List<String> = if (incoming.purged) emptyList() else attachmentRefs(body)
        if (local == null) {
            dao.upsertNote(incoming); share(); emit('r', id)
            return@withLock ApplyResult(true, false, missing().filter { !hasAttachment(it) })
        }
        if (local.rev == rev) { share(); return@withLock ApplyResult(false, false, missing().filter { !hasAttachment(it) }) }
        val newer = u > local.updatedAt || (u == local.updatedAt && rev > local.rev)
        if (!newer) { share(); return@withLock ApplyResult(false, true, emptyList()) }
        val lm = toModel(local)
        val fastForward = hist.split(',').contains(local.rev)
        if (!fastForward && !local.purged && (lm.title != title || lm.body != body) && (lm.title.isNotBlank() || lm.body.isNotBlank())) {
            // Newest edit wins, but the replaced version is kept quietly in the trash.
            val t = now()
            val label = lm.title.ifBlank { "Untitled" } + " (replaced version)"
            dao.upsertNote(
                local.copy(
                    id = UUID.randomUUID().toString(), title = enc(label), pinned = false, deleted = true, deletedAt = t,
                    updatedAt = t, rev = newRev(), revHistory = "", origin = prefs.deviceId,
                )
            )
        }
        dao.upsertNote(incoming); share()
        if (incoming.purged) cleanAttachments(id)
        emit('r', id)
        ApplyResult(true, false, missing().filter { !hasAttachment(it) })
    }

    suspend fun applyRemoteFolder(pairId: String?, j: JSONObject) {
        val id = j.getString("id"); val u = j.getLong("u")
        val local = dao.folder(id)
        if (local == null || u > local.updatedAt) dao.upsertFolder(FolderEntity(id, enc(j.optString("nm")), u, j.optBoolean("d")))
        if (pairId != null && j.optBoolean("s")) dao.addFolderShare(FolderShare(id, pairId))
    }

    suspend fun applyRemoteAttachment(j: JSONObject) {
        val id = j.getString("id")
        if (hasAttachment(id)) return
        storeAttachment(id, j.getString("nid"), j.optString("mt", "image/jpeg"), Crypto.unb64(j.getString("data")))
        emit('a', id)
    }

    // ---------- pairs ----------
    fun pairKey(p: PairEntity): ByteArray = Crypto.unb64(KeystoreBox.openString(p.keySealed))

    suspend fun addPair(pairId: String, peerDeviceId: String, peerName: String, key: ByteArray) {
        dao.upsertPair(PairEntity(pairId, peerDeviceId, peerName, KeystoreBox.sealString(Crypto.b64(key)), now(), false, now()))
    }

    suspend fun renamePeer(pairId: String, name: String) {
        dao.pair(pairId)?.let { dao.upsertPair(it.copy(peerName = name.trim().ifEmpty { it.peerName })) }
    }

    suspend fun removePair(pairId: String) {
        dao.deletePair(pairId); dao.clearNoteShares(pairId); dao.clearFolderShares(pairId)
    }

    suspend fun removeSharesFromPeer(pairId: String, notes: List<String>, folders: List<String>) {
        notes.forEach { dao.removeNoteShare(it, pairId) }
        folders.forEach { setFolderShare(it, pairId, false) }
    }

    // ---------- encryption toggle ----------
    suspend fun setEncryption(on: Boolean) = mutex.withLock {
        prefs.encryption.set(on)
        for (n in dao.allNotes()) {
            val m = toModel(n)
            dao.upsertNote(n.copy(title = enc(m.title), body = enc(m.body), tags = enc(joinTags(m.tags))))
        }
        for (f in dao.allFolders()) dao.upsertFolder(f.copy(name = enc(dec(f.name))))
        for (a in dao.allAttachments()) {
            if (a.encrypted == on) continue
            val file = attFile(a.id)
            if (!file.exists()) continue
            val plain = try { if (a.encrypted) KeystoreBox.open(file.readBytes()) else file.readBytes() } catch (e: Exception) { continue }
            file.writeBytes(if (on) KeystoreBox.seal(plain) else plain)
            dao.upsertAttachment(a.copy(encrypted = on))
        }
    }

    // ---------- backup ----------
    suspend fun exportBackup(password: String): ByteArray = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("v", 1); root.put("app", "twinstar"); root.put("at", now())
        root.put("notes", JSONArray().apply { dao.allNotes().filter { !it.purged }.forEach { put(noteRecord(it)) } })
        root.put("folders", JSONArray().apply { dao.allFolders().forEach { put(folderRecord(it, null)) } })
        root.put("attachments", JSONArray().apply { dao.allAttachments().forEach { a -> attachmentRecord(a.id)?.let { put(it) } } })
        val salt = Crypto.random(16)
        val key = Crypto.pbkdf2(password.toCharArray(), salt, 150_000)
        MAGIC + salt + Crypto.gcmEncrypt(key, root.toString().toByteArray(Charsets.UTF_8), MAGIC)
    }

    /** Returns how many notes were merged in, or -1 for a wrong password / bad file. */
    suspend fun importBackup(data: ByteArray, password: String): Int = withContext(Dispatchers.IO) {
        if (data.size < MAGIC.size + 16 + 28 || !data.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return@withContext -1
        val salt = data.copyOfRange(MAGIC.size, MAGIC.size + 16)
        val key = Crypto.pbkdf2(password.toCharArray(), salt, 150_000)
        val plain = try { Crypto.gcmDecrypt(key, data.copyOfRange(MAGIC.size + 16, data.size), MAGIC) } catch (e: Exception) { return@withContext -1 }
        val root = JSONObject(String(plain, Charsets.UTF_8))
        root.optJSONArray("folders")?.let { a -> for (i in 0 until a.length()) applyRemoteFolder(null, a.getJSONObject(i)) }
        root.optJSONArray("attachments")?.let { a -> for (i in 0 until a.length()) applyRemoteAttachment(a.getJSONObject(i)) }
        var count = 0
        root.optJSONArray("notes")?.let { a -> for (i in 0 until a.length()) if (applyRemoteNote(null, a.getJSONObject(i)).applied) count++ }
        count
    }

    companion object {
        private val MAGIC = "TSBK1".toByteArray()
        private val ATT_RE = Regex("""!\[[^\]]*]\(att:([A-Za-z0-9]+)\)""")
        fun attachmentRefs(body: String): List<String> = ATT_RE.findAll(body).map { it.groupValues[1] }.toList()
    }
}
