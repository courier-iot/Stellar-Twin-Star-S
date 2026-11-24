@file:OptIn(ExperimentalFoundationApi::class)

package com.stellar.twinstar.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.StrikethroughS
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.fragment.app.FragmentActivity
import com.stellar.twinstar.Graph
import com.stellar.twinstar.data.Note
import com.stellar.twinstar.security.AppLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun NoteScreen(id: String, startEditing: Boolean, activity: FragmentActivity, onBack: () -> Unit) {
    var note by remember(id) { mutableStateOf<Note?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) { Graph.repo.observeNote(id).collect { note = it; loaded = true } }
    DisposableEffect(id) { onDispose { Graph.scope.launch { Graph.repo.discardIfEmpty(id) } } }

    val n = note
    if (!loaded) { Box(Modifier.fillMaxSize().background(Ts.Bg)); return }
    if (n == null || n.deleted) { LaunchedEffect(Unit) { onBack() }; return }

    val unlocked by AppLock.unlockedNotes.collectAsState()
    if (n.locked && id !in unlocked) {
        LockedGate(activity, id, onBack)
        return
    }
    var editing by remember(id) { mutableStateOf(startEditing) }
    if (editing) NoteEditor(n, activity, onDone = { editing = false })
    else BookReader(n, activity, onBack = onBack, onEdit = { editing = true })
}

@Composable
private fun LockedGate(activity: FragmentActivity, id: String, onBack: () -> Unit) {
    val acc = LocalTs.current
    var pin by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val prefs = Graph.prefs
    fun unlock() { AppLock.unlockedNotes.value = AppLock.unlockedNotes.value + id }
    fun ask() {
        if (prefs.biometric.value && AppLock.biometricAvailable(activity)) AppLock.promptBiometric(activity, "Unlock note") { unlock() }
        else pin = true
    }
    LaunchedEffect(Unit) { ask() }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize().background(Ts.Bg).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.Lock, null, tint = acc.accent, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(16.dp))
        Text("LOCKED NOTE", fontFamily = Ts.Mono, letterSpacing = 3.sp, fontSize = 13.sp, color = Ts.Text)
        if (error) Text("Wrong PIN", color = Color(0xFFFF8F8F), fontFamily = Ts.Mono, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
        Text("UNLOCK", color = acc.accent, fontFamily = Ts.Mono, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            modifier = Modifier.glow(acc.accent, acc.glow).border(1.dp, acc.accent).background(Ts.Bg).clickable { ask() }.padding(horizontal = 28.dp, vertical = 14.dp))
        Spacer(Modifier.height(12.dp))
        Text("USE PIN", color = Ts.Dim, fontFamily = Ts.Mono, fontSize = 12.sp, modifier = Modifier.clickable { pin = true }.padding(12.dp))
    }
    if (pin) PinDialog("Enter PIN", onDismiss = { pin = false }) {
        if (AppLock.checkPin(prefs, it)) { pin = false; unlock() } else error = true
    }
}

// ======================= BOOK READER =======================

@Composable
private fun BookReader(n: Note, activity: FragmentActivity, onBack: () -> Unit, onEdit: () -> Unit) {
    val ts = LocalTs.current
    val acc = ts.accent
    val scope = rememberCoroutineScope()
    val fit by Graph.prefs.pageFit.state.collectAsState()
    val anim by Graph.prefs.pageAnim.state.collectAsState()
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BackHandler { onBack() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Ts.Bg)) {
        val padTop = 104.dp; val padBottom = 52.dp; val padH = 24.dp
        val wPx = with(density) { (maxWidth - padH * 2).roundToPx() }
        val hPx = with(density) { (maxHeight - padTop - padBottom).roundToPx() }
        val pages = remember(n.title, n.body, fit, wPx, hPx, acc) {
            paginate(parseMarkdown(n.title, n.body, acc), fit, measurer, wPx, hPx, density)
        }
        val pager = rememberPagerState(pageCount = { pages.size })

        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
            Box(
                Modifier.fillMaxSize()
                    .zIndex(if (offset >= 0f && offset < 1f) 1f else 0f)
                    .pageTurn(anim, offset)
                    .background(Color(0xFF050506))
                    .pointerInput(page, pages.size) {
                        detectTapGestures { pos ->
                            when {
                                pos.x < size.width * 0.25f -> scope.launch { if (page > 0) pager.animateScrollToPage(page - 1) }
                                pos.x > size.width * 0.75f -> scope.launch { if (page < pages.size - 1) pager.animateScrollToPage(page + 1) }
                                else -> onEdit()
                            }
                        }
                    }
                    .padding(start = padH, end = padH, top = padTop, bottom = padBottom),
            ) {
                Column(Modifier.fillMaxWidth().clipToBounds(), verticalArrangement = Arrangement.spacedBy(BookStyle.gap)) {
                    val blocks = pages[page]
                    if (blocks.isEmpty() && page == 0) {
                        Text("Empty note. Tap to start writing.", style = BookStyle.body.copy(color = Ts.Faint))
                    }
                    blocks.forEach { b ->
                        MdBlockView(b, acc) { line -> scope.launch { Graph.repo.setContent(n.id, n.title, toggleCheckLine(n.body, line)) } }
                    }
                }
                Text(
                    "${page + 1} / ${pages.size}", color = Ts.Faint, fontSize = 10.sp, letterSpacing = 2.sp, fontFamily = Ts.Mono,
                    modifier = Modifier.align(Alignment.BottomEnd).offset(y = 30.dp),
                )
            }
        }

        // progress line
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF16161A)).zIndex(5f)) {
            val frac = (pager.currentPage + 1f + pager.currentPageOffsetFraction) / pages.size.coerceAtLeast(1)
            Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).height(2.dp).glow(acc, ts.glow, 8.dp).background(acc))
        }
        // floating header
        ReaderHeader(n, activity, onBack, onEdit, Modifier.zIndex(6f))
    }
}

/** Page-turn transforms; [offset] is 0..1 for the page being turned away, -1..0 for the page beneath. */
private fun Modifier.pageTurn(anim: Int, offset: Float): Modifier = when (anim) {
    2 -> this
    1 -> this.graphicsLayer { translationX = offset * size.width }.drawWithContent {
        if (offset > 0f && offset < 1f) {
            val fold = size.width * (1f - offset)
            clipRect(right = fold) { this@drawWithContent.drawContent() }
            val flap = minOf(size.width - fold, fold)
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color(0xFF141417), Color(0xFF34343A), Color(0xFF1C1C20)), startX = fold - flap, endX = fold),
                topLeft = Offset(fold - flap, 0f), size = Size(flap, size.height),
            )
            val sw = 36.dp.toPx()
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent), startX = fold, endX = fold + sw),
                topLeft = Offset(fold, 0f), size = Size(sw, size.height),
            )
        } else {
            drawContent()
            if (offset < 0f && offset > -1f) drawRect(Color.Black.copy(alpha = 0.4f * -offset))
        }
    }
    else -> this.graphicsLayer {
        translationX = offset * size.width
        if (offset > 0f) {
            transformOrigin = TransformOrigin(0f, 0.5f)
            cameraDistance = 14f * density
            rotationY = -180f * offset.coerceAtMost(1f)
            alpha = if (offset > 0.5f) 0f else 1f
        }
    }.drawWithContent {
        drawContent()
        if (offset < 0f && offset > -1f) drawRect(Color.Black.copy(alpha = 0.45f * -offset))
    }
}

@Composable
private fun ReaderHeader(n: Note, activity: FragmentActivity, onBack: () -> Unit, onEdit: () -> Unit, modifier: Modifier) {
    val acc = LocalTs.current.accent
    val folders by Graph.repo.folders.collectAsState(initial = emptyList())
    val noteShares by Graph.repo.noteShares.collectAsState(initial = emptyList())
    val folderShares by Graph.repo.folderShares.collectAsState(initial = emptyList())
    val folderName = folders.firstOrNull { it.id == n.folderId }?.name?.uppercase()
    val shareCount = (noteShares.filter { it.noteId == n.id }.map { it.pairId } + folderShares.filter { it.folderId == n.folderId }.map { it.pairId }).toSet().size
    val sub = listOfNotNull(folderName, if (shareCount > 0) "⇄ $shareCount" else null, n.tags.takeIf { it.isNotEmpty() }?.joinToString(" ") { "#$it" }).joinToString(" · ")
    Row(
        modifier.fillMaxWidth()
            .background(Brush.verticalGradient(0f to Ts.Bg, 0.6f to Ts.Bg, 1f to Color.Transparent))
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = Ts.Text) }
        Column(Modifier.weight(1f)) {
            Text(n.title.ifBlank { "untitled" }, fontFamily = Ts.Mono, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, color = Ts.Text)
            if (sub.isNotEmpty()) Text(sub, fontFamily = Ts.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = Ts.Dim, maxLines = 1)
        }
        IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Edit", tint = acc) }
        NoteMenu(n, activity)
    }
}

/** Overflow menu shared by reader and editor. */
@Composable
fun NoteMenu(n: Note, activity: FragmentActivity) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More", tint = Ts.Text) }
        NoteActions(n, open) { open = false }
    }
}

/** Note actions dropdown + the dialogs it opens. Anchor it inside a Box next to its trigger. */
@Composable
fun NoteActions(n: Note, open: Boolean, onClose: () -> Unit) {
    var dialog by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val folders by Graph.repo.folders.collectAsState(initial = emptyList())
    val allNotes by Graph.repo.notes.collectAsState(initial = emptyList())
    val peers by Graph.repo.peers.collectAsState(initial = emptyList())
    val noteShares by Graph.repo.noteShares.collectAsState(initial = emptyList())
    val folderShares by Graph.repo.folderShares.collectAsState(initial = emptyList())
    val online by Graph.sync.online.collectAsState()

    run {
        DropdownMenu(expanded = open, onDismissRequest = onClose, modifier = Modifier.background(Color(0xFF0A0A0C)).border(1.dp, Ts.Line2)) {
            MenuItem(if (n.pinned) "Unpin" else "Pin to top") { onClose(); scope.launch { Graph.repo.setPinned(n.id, !n.pinned) } }
            MenuItem("Tags…") { onClose(); dialog = "tags" }
            MenuItem("Move to folder…") { onClose(); dialog = "folder" }
            MenuItem("Share with devices…") { onClose(); dialog = "share" }
            MenuItem(if (n.locked) "Remove note lock" else "Lock this note") {
                onClose()
                if (!n.locked && !AppLock.hasPin(Graph.prefs)) dialog = "setpin"
                else scope.launch { Graph.repo.setLocked(n.id, !n.locked) }
            }
            MenuItem("Move to trash") { onClose(); scope.launch { Graph.repo.trash(n.id) } }
        }
    }
    when (dialog) {
        "tags" -> TagsDialog(n.tags, allNotes.flatMap { it.tags }.distinct().sorted(), onDismiss = { dialog = "" }) {
            dialog = ""; scope.launch { Graph.repo.setTags(n.id, it) }
        }
        "folder" -> FolderPickerDialog(folders, n.folderId, onDismiss = { dialog = "" }, onPick = { f ->
            dialog = ""; scope.launch { Graph.repo.setFolder(n.id, f) }
        }, onNew = { dialog = "newfolder" })
        "newfolder" -> TextInputDialog("New folder", label = "Name", onDismiss = { dialog = "" }) { name ->
            dialog = ""
            if (name.isNotBlank()) scope.launch { val f = Graph.repo.createFolder(name); Graph.repo.setFolder(n.id, f) }
        }
        "share" -> ShareDialog(
            "Share note", peers,
            shared = noteShares.filter { it.noteId == n.id }.map { it.pairId }.toSet(),
            viaFolder = folderShares.filter { it.folderId == n.folderId }.map { it.pairId }.toSet(),
            online = online, onDismiss = { dialog = "" },
        ) { pid, on -> Graph.sync.shareNote(n.id, pid, on) }
        "setpin" -> PinDialog("Set a PIN for locked notes", onDismiss = { dialog = "" }) { pin ->
            AppLock.setPin(Graph.prefs, pin); dialog = ""
            AppLock.unlockedNotes.value = AppLock.unlockedNotes.value + n.id
            scope.launch { Graph.repo.setLocked(n.id, true) }
        }
    }
}

// ======================= EDITOR =======================

@Composable
private fun NoteEditor(n: Note, activity: FragmentActivity, onDone: () -> Unit) {
    val ts = LocalTs.current
    val acc = ts.accent
    val scope = rememberCoroutineScope()
    var title by remember(n.id) { mutableStateOf(n.title) }
    var body by remember(n.id) { mutableStateOf(TextFieldValue(n.body, TextRange(n.body.length))) }
    var dirty by remember { mutableStateOf(false) }

    fun save() { val t = title; val b = body.text; Graph.scope.launch { Graph.repo.setContent(n.id, t, b) }; dirty = false }

    LaunchedEffect(title, body.text) { if (dirty) { delay(700); save() } }
    DisposableEffect(n.id) { onDispose { if (dirty) save() } }
    BackHandler { save(); onDone() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val att = Graph.repo.addImage(uri, n.id)
            if (att != null) { body = insertBlock(body, "![image](att:$att)"); dirty = true }
        }
    }

    Column(Modifier.fillMaxSize().background(Ts.Bg)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { save(); onDone() }) { Icon(Icons.Filled.ArrowBack, "Done", tint = Ts.Text) }
            Text("EDITING", fontFamily = Ts.Mono, fontSize = 11.sp, letterSpacing = 3.sp, color = Ts.Dim, modifier = Modifier.weight(1f))
            IconButton(onClick = { save(); onDone() }) { Icon(Icons.Filled.Check, "Done", tint = acc) }
            NoteMenu(n, activity)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            BasicTextField(
                value = title, onValueChange = { title = it.replace("\n", ""); dirty = true },
                textStyle = BookStyle.title, cursorBrush = SolidColor(acc),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp),
                decorationBox = { inner -> Box { if (title.isEmpty()) Text("Title", style = BookStyle.title.copy(color = Ts.Faint)); inner() } },
            )
            BasicTextField(
                value = body, onValueChange = { nv -> body = continueList(body, nv); dirty = true },
                textStyle = BookStyle.body.copy(color = Ts.Text), cursorBrush = SolidColor(acc),
                modifier = Modifier.fillMaxWidth().padding(bottom = 120.dp),
                decorationBox = { inner -> Box { if (body.text.isEmpty()) Text("Start writing…", style = BookStyle.body.copy(color = Ts.Faint)); inner() } },
            )
        }
        // formatting toolbar (sits above the keyboard)
        Row(
            Modifier.fillMaxWidth().background(Ts.Bg).border(1.dp, Color(0xFF16161A)).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(Icons.Filled.FormatBold, "Bold") { body = wrap(body, "**"); dirty = true }
            ToolButton(Icons.Filled.FormatItalic, "Italic") { body = wrap(body, "*"); dirty = true }
            ToolButton(Icons.Filled.StrikethroughS, "Strikethrough") { body = wrap(body, "~~"); dirty = true }
            ToolButton(Icons.Filled.Title, "Heading") { body = togglePrefix(body, "# "); dirty = true }
            ToolButton(Icons.Filled.FormatListBulleted, "Bullet list") { body = togglePrefix(body, "- "); dirty = true }
            ToolButton(Icons.Filled.FormatListNumbered, "Numbered list") { body = togglePrefix(body, "1. "); dirty = true }
            ToolButton(Icons.Filled.SortByAlpha, "Lettered list") { body = togglePrefix(body, "a. "); dirty = true }
            ToolButton(Icons.Filled.Checklist, "Checklist") { body = togglePrefix(body, "- [ ] "); dirty = true }
            ToolButton(Icons.Filled.FormatQuote, "Quote") { body = togglePrefix(body, "> "); dirty = true }
            ToolButton(Icons.Filled.Code, "Code") { body = wrap(body, "`"); dirty = true }
            ToolButton(Icons.Filled.HorizontalRule, "Divider") { body = insertBlock(body, "---"); dirty = true }
            ToolButton(Icons.Filled.Image, "Image") { picker.launch("image/*"); dirty = true }
            ToolButton(Icons.Filled.NoteAdd, "New page") { body = insertBlock(body, PAGE_BREAK); dirty = true }
        }
    }
}

private fun wrap(v: TextFieldValue, token: String): TextFieldValue {
    val s = v.selection.min; val e = v.selection.max
    val t = v.text
    return if (s == e) TextFieldValue(t.substring(0, s) + token + token + t.substring(s), TextRange(s + token.length))
    else TextFieldValue(t.substring(0, s) + token + t.substring(s, e) + token + t.substring(e), TextRange(e + token.length * 2))
}

private fun togglePrefix(v: TextFieldValue, prefix: String): TextFieldValue {
    val t = v.text
    val c = v.selection.min.coerceIn(0, t.length)
    val ls = if (c == 0) 0 else t.lastIndexOf('\n', c - 1) + 1
    return if (t.startsWith(prefix, ls)) {
        TextFieldValue(t.removeRange(ls, ls + prefix.length), TextRange((c - prefix.length).coerceAtLeast(ls)))
    } else {
        TextFieldValue(t.substring(0, ls) + prefix + t.substring(ls), TextRange(c + prefix.length))
    }
}

private fun insertBlock(v: TextFieldValue, block: String): TextFieldValue {
    val t = v.text
    val c = v.selection.max.coerceIn(0, t.length)
    val before = if (c == 0 || t[c - 1] == '\n') "" else "\n"
    val after = if (c < t.length && t[c] == '\n') "" else "\n"
    val ins = before + block + after
    return TextFieldValue(t.substring(0, c) + ins + t.substring(c), TextRange(c + ins.length))
}

private val reContCheck = Regex("^(\\s*)[-*+] \\[[ xX]] (.*)$")
private val reContBullet = Regex("^(\\s*)([-*+]) (.*)$")
private val reContNum = Regex("^(\\s*)(\\d{1,4})([.)]) (.*)$")
private val reContLetter = Regex("^(\\s*)([a-zA-Z])([.)]) (.*)$")
private val reContQuote = Regex("^> (.*)$")

/** Pressing Enter on a list line continues the list; Enter on an empty item ends it. */
private fun continueList(old: TextFieldValue, nv: TextFieldValue): TextFieldValue {
    if (nv.text.length != old.text.length + 1 || !nv.selection.collapsed) return nv
    val c = nv.selection.start
    if (c <= 0 || nv.text[c - 1] != '\n') return nv
    val t = nv.text
    val ps = if (c - 2 < 0) 0 else t.lastIndexOf('\n', c - 2) + 1
    val prev = t.substring(ps, c - 1)
    val (marker, content) = reContCheck.find(prev)?.let { it.groupValues[1] + "- [ ] " to it.groupValues[2] }
        ?: reContBullet.find(prev)?.let { it.groupValues[1] + it.groupValues[2] + " " to it.groupValues[3] }
        ?: reContNum.find(prev)?.let { it.groupValues[1] + ((it.groupValues[2].toIntOrNull() ?: 0) + 1) + it.groupValues[3] + " " to it.groupValues[4] }
        ?: reContLetter.find(prev)?.let {
            val ch = it.groupValues[2][0]
            val next = if (ch == 'z' || ch == 'Z') ch else ch + 1
            it.groupValues[1] + next + it.groupValues[3] + " " to it.groupValues[4]
        }
        ?: reContQuote.find(prev)?.let { "> " to it.groupValues[1] }
        ?: return nv
    if (content.isBlank()) {
        val nt = t.substring(0, ps) + t.substring(c)
        return TextFieldValue(nt, TextRange(ps))
    }
    return TextFieldValue(t.substring(0, c) + marker + t.substring(c), TextRange(c + marker.length))
}

@Composable
fun MenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label, fontFamily = Ts.Mono, fontSize = 13.sp, color = Ts.Text) }, onClick = onClick)
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = Ts.Text) }
}
