@file:OptIn(ExperimentalFoundationApi::class)

package com.stellar.twinstar.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellar.twinstar.Graph
import com.stellar.twinstar.data.Note
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class Tab { NOTES, FOLDERS, LINKS, CONFIG }

const val UNFILED = "__unfiled"

@Composable
fun MainShell(tab: Tab, onTab: (Tab) -> Unit, onNew: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ts.Bg)) {
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
        BottomBar(tab, onTab, onNew)
    }
}

@Composable
private fun BottomBar(tab: Tab, onTab: (Tab) -> Unit, onNew: () -> Unit) {
    val ts = LocalTs.current
    Row(
        Modifier.fillMaxWidth().height(76.dp).background(Ts.Bg).border(1.dp, Color(0xFF1C1C20)).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically,
    ) {
        BarItem(Icons.Filled.GridView, "NOTES", tab == Tab.NOTES) { onTab(Tab.NOTES) }
        BarItem(Icons.Filled.Folder, "FOLDERS", tab == Tab.FOLDERS) { onTab(Tab.FOLDERS) }
        Box(
            Modifier.size(56.dp).glow(ts.accent, ts.glow).border(1.dp, ts.accent).background(Ts.Bg).clickable(onClick = onNew),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Add, "New note", tint = ts.accent, modifier = Modifier.size(26.dp)) }
        BarItem(Icons.Filled.Devices, "LINKS", tab == Tab.LINKS) { onTab(Tab.LINKS) }
        BarItem(Icons.Filled.Menu, "CONFIG", tab == Tab.CONFIG) { onTab(Tab.CONFIG) }
    }
}

@Composable
private fun BarItem(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val acc = LocalTs.current.accent
    val c = if (active) acc else Ts.Dim
    Column(
        Modifier.size(width = 60.dp, height = 56.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, tint = c, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 9.sp, letterSpacing = 1.sp, fontFamily = Ts.Mono, color = c, style = if (active) glowText(acc) else TextStyle.Default)
    }
}

@Composable
fun HeaderBar(title: String) {
    val ts = LocalTs.current
    val online by Graph.sync.online.collectAsState()
    val peers by Graph.repo.peers.collectAsState(initial = emptyList())
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildTitle(title, ts.accent), fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp,
            fontFamily = Ts.Mono, color = Ts.Text, modifier = Modifier.weight(1f),
        )
        val live = online.isNotEmpty()
        val label = when { peers.isEmpty() -> "SOLO"; live -> "LIVE"; else -> "OFFLINE" }
        Text(
            label, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontFamily = Ts.Mono,
            color = if (live) Color.Black else Ts.Dim,
            modifier = (if (live) Modifier.glow(ts.accent, ts.glow, 12.dp).background(ts.accent) else Modifier.border(1.dp, Ts.Line2))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun buildTitle(t: String, acc: Color) = androidx.compose.ui.text.buildAnnotatedString {
    val i = t.indexOf('✦')
    if (i < 0) append(t) else {
        append(t.substring(0, i))
        pushStyle(androidx.compose.ui.text.SpanStyle(color = acc)); append("✦"); pop()
        append(t.substring(i + 1))
    }
}

@Composable
fun StatusStrip() {
    val acc = LocalTs.current.accent
    val online by Graph.sync.online.collectAsState()
    val peers by Graph.repo.peers.collectAsState(initial = emptyList())
    val last by Graph.sync.lastSyncAt.collectAsState()
    val relays by Graph.sync.relayCount.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
    val left = when {
        peers.isEmpty() -> "NO LINKS · PAIR IN LINKS"
        online.isEmpty() -> "LINKS ○ ${peers.size} OFFLINE"
        else -> "LINK ● " + peers.filter { it.pairId in online }.joinToString(", ") { it.name.uppercase() }
    }
    val right = when {
        last == 0L -> if (relays > 0) "NET ✓" else "NET …"
        else -> { val s = (now - last) / 1000; "SYNCED %02d:%02d".format(s / 60 % 100, s % 60) }
    }
    Row(
        Modifier.padding(horizontal = 18.dp).fillMaxWidth().border(1.dp, Color(0xFF1C1C20)).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(left, fontSize = 11.sp, letterSpacing = 1.sp, fontFamily = Ts.Mono, color = if (online.isNotEmpty()) acc else Ts.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(right, fontSize = 11.sp, letterSpacing = 1.sp, fontFamily = Ts.Mono, color = Ts.Dim)
    }
}

@Composable
fun HomeScreen(folderFilter: String?, onFolderFilter: (String?) -> Unit, onOpen: (String) -> Unit) {
    val ts = LocalTs.current
    val notes by Graph.repo.notes.collectAsState(initial = emptyList())
    val folders by Graph.repo.folders.collectAsState(initial = emptyList())
    val noteShares by Graph.repo.noteShares.collectAsState(initial = emptyList())
    val folderShares by Graph.repo.folderShares.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var tagFilter by remember { mutableStateOf<String?>(null) }
    var showTags by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) }

    val live = notes.filter { !it.deleted }
    val folderIds = folders.map { it.id }.toSet()
    val sharedNotes = noteShares.map { it.noteId }.toSet()
    val sharedFolders = folderShares.map { it.folderId }.toSet()
    val q = query.trim().lowercase()
    val visible = live
        .filter {
            when (folderFilter) {
                null -> true
                UNFILED -> it.folderId == null || it.folderId !in folderIds
                else -> it.folderId == folderFilter
            }
        }
        .filter { tagFilter == null || tagFilter in it.tags }
        .filter { q.isEmpty() || (!it.locked && (it.title.lowercase().contains(q) || it.body.lowercase().contains(q) || it.tags.any { t -> t.lowercase().contains(q) })) }
        .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })
    val allTags = live.flatMap { it.tags }.distinct().sorted()

    Column(Modifier.fillMaxSize()) {
        HeaderBar("TWIN✦STAR S")
        StatusStrip()
        // search
        Row(
            Modifier.padding(start = 18.dp, end = 18.dp, top = 12.dp).fillMaxWidth().height(44.dp).border(1.dp, Color(0xFF232327)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, null, tint = Ts.Dim, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                query, { query = it }, singleLine = true, cursorBrush = SolidColor(ts.accent),
                textStyle = TextStyle(fontFamily = Ts.Mono, fontSize = 13.sp, color = Ts.Text),
                modifier = Modifier.weight(1f),
                decorationBox = { inner -> Box { if (query.isEmpty()) Text("search notes", fontFamily = Ts.Mono, fontSize = 13.sp, color = Ts.Dim); inner() } },
            )
            if (query.isNotEmpty()) Icon(Icons.Filled.Close, "Clear", tint = Ts.Dim, modifier = Modifier.size(18.dp).clickable { query = "" })
        }
        // folder tabs
        LazyRow(
            contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(top = 12.dp, bottom = 10.dp),
        ) {
            item { TabLabel("ALL ${live.size}", folderFilter == null && tagFilter == null) { onFolderFilter(null); tagFilter = null } }
            items(folders, key = { it.id }) { f ->
                TabLabel("${f.name.uppercase()} ${live.count { it.folderId == f.id }}", folderFilter == f.id) { onFolderFilter(f.id) }
            }
            if (live.any { it.folderId == null }) item { TabLabel("UNFILED", folderFilter == UNFILED) { onFolderFilter(UNFILED) } }
            item { TabLabel(tagFilter?.let { "#${it.uppercase()}" } ?: "#TAGS", tagFilter != null) { showTags = true } }
        }
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (live.isEmpty()) "NO NOTES YET\n\nTAP + TO WRITE ONE" else "NOTHING HERE",
                    fontFamily = Ts.Mono, fontSize = 12.sp, letterSpacing = 2.sp, color = Ts.Dim, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(visible, key = { it.id }) { n ->
                val folderName = folders.firstOrNull { it.id == n.folderId }?.name
                val shared = n.id in sharedNotes || (n.folderId != null && n.folderId in sharedFolders)
                Box {
                    NoteTile(n, folderName, shared, onClick = { onOpen(n.id) }, onLong = { menuFor = n.id })
                    if (menuFor == n.id) NoteActions(n, true) { menuFor = null }
                }
            }
        }
    }

    if (showTags) {
        TsDialog("Filter by tag", { showTags = false }, confirm = null, dismiss = "CLOSE") {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item { TagRow("All notes", tagFilter == null) { tagFilter = null; showTags = false } }
                items(allTags) { t -> TagRow("#$t", tagFilter == t) { tagFilter = t; showTags = false } }
                if (allTags.isEmpty()) item { Text("No tags yet. Add them from a note's menu.", color = Ts.Dim, fontFamily = Ts.Mono, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun TagRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val acc = LocalTs.current.accent
    Text(
        if (selected) "[$label]" else label, color = if (selected) acc else Ts.Text, fontFamily = Ts.Mono, fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
    )
}

@Composable
private fun TabLabel(text: String, active: Boolean, onClick: () -> Unit) {
    val acc = LocalTs.current.accent
    Column(Modifier.clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Text(
            if (active) "[$text]" else text, fontSize = 12.sp, letterSpacing = 1.sp, fontFamily = Ts.Mono,
            color = if (active) acc else Ts.Dim, style = if (active) glowText(acc) else TextStyle.Default,
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (active) acc else Color.Transparent))
    }
}

@Composable
fun NoteTile(n: Note, folderName: String?, shared: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val ts = LocalTs.current
    val label = when {
        n.pinned -> "▲ PIN"
        n.locked -> "LOCKED"
        shared -> "⇄ SHARED"
        folderName != null -> folderName.uppercase()
        n.tags.isNotEmpty() -> "#" + n.tags.first().uppercase()
        else -> "NOTE"
    }
    val border = if (n.pinned) ts.accent else if (n.locked) Color(0xFF3A3A40) else Ts.Line
    Column(
        Modifier.aspectRatio(1f)
            .then(if (n.pinned) Modifier.glow(ts.accent, ts.glow, 16.dp) else Modifier)
            .background(Ts.Card).border(1.dp, border)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(label, fontSize = 9.sp, letterSpacing = 1.sp, fontFamily = Ts.Mono, color = if (n.pinned) ts.accent else Ts.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            n.title.ifBlank { "untitled" }, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Ts.Mono,
            color = Ts.Text, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp,
        )
        if (n.locked) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Lock, "Locked", tint = Ts.Faint, modifier = Modifier.size(18.dp))
            }
        } else {
            val img = firstImage(n.body)
            val preview = previewText(n.body)
            if (preview.startsWith("▨") && img != null) {
                Box(Modifier.fillMaxWidth().weight(1f).border(1.dp, Ts.Line)) { AttachmentThumb(img) }
            } else {
                Text(preview, fontSize = 10.sp, lineHeight = 14.sp, fontFamily = Ts.Mono, color = Ts.Dim, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AttachmentThumb(id: String) {
    var bmp by remember(id) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    androidx.compose.runtime.LaunchedEffect(id) {
        bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Graph.repo.loadAttachment(id)?.let { bytes ->
                val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)?.asImageBitmap()
            }
        }
    }
    bmp?.let { androidx.compose.foundation.Image(it, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
}

// ======================= FOLDERS =======================

@Composable
fun FoldersScreen(onOpenFolder: (String?) -> Unit, onTrash: () -> Unit) {
    val ts = LocalTs.current
    val scope = rememberCoroutineScope()
    val notes by Graph.repo.notes.collectAsState(initial = emptyList())
    val folders by Graph.repo.folders.collectAsState(initial = emptyList())
    val peers by Graph.repo.peers.collectAsState(initial = emptyList())
    val folderShares by Graph.repo.folderShares.collectAsState(initial = emptyList())
    val online by Graph.sync.online.collectAsState()
    var newFolder by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf<String?>(null) }
    var rename by remember { mutableStateOf<String?>(null) }
    var share by remember { mutableStateOf<String?>(null) }
    var delete by remember { mutableStateOf<String?>(null) }
    val live = notes.filter { !it.deleted }

    Column(Modifier.fillMaxSize()) {
        HeaderBar("FOLDERS")
        LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(folders, key = { it.id }) { f ->
                val count = live.count { it.folderId == f.id }
                val sharedWith = folderShares.filter { it.folderId == f.id }.mapNotNull { s -> peers.firstOrNull { it.pairId == s.pairId }?.name }
                Box {
                    FolderRow(f.name, "$count NOTES" + if (sharedWith.isNotEmpty()) " · ⇄ " + sharedWith.joinToString(", ") else "", onClick = { onOpenFolder(f.id) }, onLong = { manage = f.id })
                    androidx.compose.material3.DropdownMenu(expanded = manage == f.id, onDismissRequest = { manage = null }, modifier = Modifier.background(Color(0xFF0A0A0C))) {
                        MenuItem("Rename") { manage = null; rename = f.id }
                        MenuItem("Share folder…") { manage = null; share = f.id }
                        MenuItem("Delete folder") { manage = null; delete = f.id }
                    }
                }
            }
            item {
                Text(
                    "+ NEW FOLDER", color = ts.accent, fontFamily = Ts.Mono, fontSize = 12.sp, letterSpacing = 2.sp,
                    modifier = Modifier.fillMaxWidth().border(1.dp, Ts.Line2).clickable { newFolder = true }.padding(16.dp),
                )
            }
            item { Spacer(Modifier.height(10.dp)) }
            item {
                val trashCount = notes.count { it.deleted }
                FolderRow("Trash", "$trashCount NOTES · EMPTIES AFTER 30 DAYS", icon = Icons.Filled.Delete, onClick = onTrash, onLong = onTrash)
            }
            if (folders.isNotEmpty()) item {
                Text("Long-press a folder to rename, share or delete it.", color = Ts.Faint, fontFamily = Ts.Mono, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    if (newFolder) TextInputDialog("New folder", label = "Name", onDismiss = { newFolder = false }) {
        newFolder = false; if (it.isNotBlank()) scope.launch { Graph.repo.createFolder(it) }
    }
    rename?.let { id ->
        TextInputDialog("Rename folder", initial = folders.firstOrNull { it.id == id }?.name ?: "", onDismiss = { rename = null }) {
            rename = null; if (it.isNotBlank()) scope.launch { Graph.repo.renameFolder(id, it) }
        }
    }
    share?.let { id ->
        ShareDialog(
            "Share folder", peers, shared = folderShares.filter { it.folderId == id }.map { it.pairId }.toSet(),
            viaFolder = emptySet(), online = online, onDismiss = { share = null },
        ) { pid, on -> Graph.sync.shareFolder(id, pid, on) }
    }
    delete?.let { id ->
        ConfirmDialog("Delete folder", "The folder is removed. Its notes stay and move to UNFILED.", "DELETE", onDismiss = { delete = null }) {
            delete = null; scope.launch { Graph.repo.deleteFolder(id) }
        }
    }
}

@Composable
private fun FolderRow(name: String, sub: String, icon: ImageVector = Icons.Filled.Folder, onClick: () -> Unit, onLong: () -> Unit) {
    val acc = LocalTs.current.accent
    Row(
        Modifier.fillMaxWidth().background(Ts.Card).border(1.dp, Ts.Line).combinedClickable(onClick = onClick, onLongClick = onLong).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = acc, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name.uppercase(), fontFamily = Ts.Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp, color = Ts.Text)
            Text(sub, fontFamily = Ts.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Ts.Dim)
        }
    }
}

// ======================= TRASH =======================

@Composable
fun TrashScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val notes by Graph.repo.notes.collectAsState(initial = emptyList())
    val trash = notes.filter { it.deleted }.sortedByDescending { it.deletedAt }
    var confirmEmpty by remember { mutableStateOf(false) }
    var forever by remember { mutableStateOf<String?>(null) }
    androidx.activity.compose.BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(Ts.Bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.IconButton(onClick = onBack) { Icon(androidx.compose.material.icons.Icons.Filled.Close, "Close", tint = Ts.Text) }
            Text("TRASH", fontFamily = Ts.Mono, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, fontSize = 17.sp, color = Ts.Text, modifier = Modifier.weight(1f))
            if (trash.isNotEmpty()) Text("EMPTY", color = Color(0xFFFF8F8F), fontFamily = Ts.Mono, fontSize = 12.sp, letterSpacing = 2.sp,
                modifier = Modifier.border(1.dp, Color(0xFF5A2A2A)).clickable { confirmEmpty = true }.padding(horizontal = 12.dp, vertical = 8.dp))
        }
        Text("Notes here are removed for good after 30 days. Older versions replaced by a newer edit on another device land here too.",
            color = Ts.Dim, fontFamily = Ts.Mono, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
        LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(trash, key = { it.id }) { n ->
                val days = ((System.currentTimeMillis() - n.deletedAt) / 86_400_000L).toInt()
                Column(Modifier.fillMaxWidth().background(Ts.Card).border(1.dp, Ts.Line).padding(12.dp)) {
                    Text(n.title.ifBlank { "untitled" }, fontFamily = Ts.Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Ts.Text, maxLines = 1)
                    Text("deleted ${if (days == 0) "today" else "$days d ago"} · ${maxOf(0, 30 - days)} d left", fontFamily = Ts.Mono, fontSize = 10.sp, color = Ts.Dim)
                    if (!n.locked) Text(previewText(n.body, 2), fontFamily = Ts.Mono, fontSize = 11.sp, color = Ts.Faint, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("RESTORE", color = LocalTs.current.accent, fontFamily = Ts.Mono, fontSize = 11.sp, letterSpacing = 1.sp,
                            modifier = Modifier.border(1.dp, LocalTs.current.accent).clickable { scope.launch { Graph.repo.restore(n.id) } }.padding(horizontal = 12.dp, vertical = 8.dp))
                        Text("DELETE FOREVER", color = Ts.Dim, fontFamily = Ts.Mono, fontSize = 11.sp, letterSpacing = 1.sp,
                            modifier = Modifier.border(1.dp, Ts.Line2).clickable { forever = n.id }.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
            if (trash.isEmpty()) item { Text("TRASH IS EMPTY", color = Ts.Dim, fontFamily = Ts.Mono, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.padding(top = 40.dp)) }
        }
    }
    if (confirmEmpty) ConfirmDialog("Empty trash", "Everything in the trash is deleted for good, on linked devices too.", "EMPTY", { confirmEmpty = false }) {
        confirmEmpty = false; scope.launch { Graph.repo.emptyTrash() }
    }
    forever?.let { id ->
        ConfirmDialog("Delete forever", "This can't be undone.", "DELETE", { forever = null }) { forever = null; scope.launch { Graph.repo.purge(id) } }
    }
}
