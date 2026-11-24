package com.stellar.twinstar.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "notes", indices = [Index("folderId")])
data class NoteEntity(
    @PrimaryKey val id: String,
    val folderId: String?,
    val title: String,
    val body: String,
    val tags: String,
    val pinned: Boolean,
    val locked: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val rev: String,
    val revHistory: String,
    val deleted: Boolean,
    val deletedAt: Long,
    val purged: Boolean,
    val origin: String,
)

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val updatedAt: Long,
    val deleted: Boolean,
)

@Entity(tableName = "attachments", indices = [Index("noteId")])
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val noteId: String,
    val mime: String,
    val createdAt: Long,
    val encrypted: Boolean,
)

@Entity(tableName = "pairs")
data class PairEntity(
    @PrimaryKey val pairId: String,
    val peerDeviceId: String,
    val peerName: String,
    val keySealed: String,
    val createdAt: Long,
    val autoShareNew: Boolean,
    val lastSeen: Long,
)

@Entity(tableName = "note_shares", primaryKeys = ["noteId", "pairId"])
data class NoteShare(val noteId: String, val pairId: String)

@Entity(tableName = "folder_shares", primaryKeys = ["folderId", "pairId"])
data class FolderShare(val folderId: String, val pairId: String)

@Dao
interface TsDao {
    // notes
    @Query("SELECT * FROM notes WHERE purged = 0") fun observeNotes(): Flow<List<NoteEntity>>
    @Query("SELECT * FROM notes WHERE id = :id") fun observeNote(id: String): Flow<NoteEntity?>
    @Query("SELECT * FROM notes") suspend fun allNotes(): List<NoteEntity>
    @Query("SELECT * FROM notes WHERE id = :id") suspend fun note(id: String): NoteEntity?
    @Query("SELECT * FROM notes WHERE folderId = :folderId AND purged = 0") suspend fun notesInFolder(folderId: String): List<NoteEntity>
    @Upsert suspend fun upsertNote(n: NoteEntity)
    @Query("DELETE FROM notes WHERE id = :id") suspend fun deleteNote(id: String)
    @Query("SELECT * FROM notes WHERE deleted = 1 AND purged = 0 AND deletedAt < :before") suspend fun trashOlderThan(before: Long): List<NoteEntity>
    @Query("DELETE FROM notes WHERE purged = 1 AND updatedAt < :before") suspend fun dropTombstones(before: Long)

    // folders
    @Query("SELECT * FROM folders WHERE deleted = 0 ORDER BY name") fun observeFolders(): Flow<List<FolderEntity>>
    @Query("SELECT * FROM folders") suspend fun allFolders(): List<FolderEntity>
    @Query("SELECT * FROM folders WHERE id = :id") suspend fun folder(id: String): FolderEntity?
    @Upsert suspend fun upsertFolder(f: FolderEntity)

    // attachments
    @Query("SELECT * FROM attachments WHERE id = :id") suspend fun attachment(id: String): AttachmentEntity?
    @Query("SELECT * FROM attachments") suspend fun allAttachments(): List<AttachmentEntity>
    @Query("SELECT * FROM attachments WHERE noteId = :noteId") suspend fun attachmentsFor(noteId: String): List<AttachmentEntity>
    @Upsert suspend fun upsertAttachment(a: AttachmentEntity)
    @Query("DELETE FROM attachments WHERE id = :id") suspend fun deleteAttachment(id: String)

    // pairs
    @Query("SELECT * FROM pairs ORDER BY createdAt") fun observePairs(): Flow<List<PairEntity>>
    @Query("SELECT * FROM pairs") suspend fun allPairs(): List<PairEntity>
    @Query("SELECT * FROM pairs WHERE pairId = :id") suspend fun pair(id: String): PairEntity?
    @Upsert suspend fun upsertPair(p: PairEntity)
    @Query("DELETE FROM pairs WHERE pairId = :id") suspend fun deletePair(id: String)
    @Query("UPDATE pairs SET lastSeen = :t WHERE pairId = :id") suspend fun touchPair(id: String, t: Long)

    // shares
    @Query("SELECT * FROM note_shares") fun observeNoteShares(): Flow<List<NoteShare>>
    @Query("SELECT * FROM folder_shares") fun observeFolderShares(): Flow<List<FolderShare>>
    @Query("SELECT pairId FROM note_shares WHERE noteId = :noteId") suspend fun notePairs(noteId: String): List<String>
    @Query("SELECT pairId FROM folder_shares WHERE folderId = :folderId") suspend fun folderPairs(folderId: String): List<String>
    @Query("SELECT noteId FROM note_shares WHERE pairId = :pairId") suspend fun notesSharedWith(pairId: String): List<String>
    @Query("SELECT folderId FROM folder_shares WHERE pairId = :pairId") suspend fun foldersSharedWith(pairId: String): List<String>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addNoteShare(s: NoteShare)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addFolderShare(s: FolderShare)
    @Query("DELETE FROM note_shares WHERE noteId = :noteId AND pairId = :pairId") suspend fun removeNoteShare(noteId: String, pairId: String)
    @Query("DELETE FROM folder_shares WHERE folderId = :folderId AND pairId = :pairId") suspend fun removeFolderShare(folderId: String, pairId: String)
    @Query("DELETE FROM note_shares WHERE pairId = :pairId") suspend fun clearNoteShares(pairId: String)
    @Query("DELETE FROM folder_shares WHERE pairId = :pairId") suspend fun clearFolderShares(pairId: String)
    @Query("DELETE FROM note_shares WHERE noteId = :noteId") suspend fun clearSharesOfNote(noteId: String)
}

@Database(
    entities = [NoteEntity::class, FolderEntity::class, AttachmentEntity::class, PairEntity::class, NoteShare::class, FolderShare::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TsDao
}
