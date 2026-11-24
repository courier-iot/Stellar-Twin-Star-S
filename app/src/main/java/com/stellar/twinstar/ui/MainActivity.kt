package com.stellar.twinstar.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.stellar.twinstar.Graph
import com.stellar.twinstar.security.AppLock
import com.stellar.twinstar.widget.WidgetUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

sealed class Screen {
    data class Main(val tab: Tab) : Screen()
    data class NoteView(val id: String, val edit: Boolean) : Screen()
    object Trash : Screen()
}

class MainActivity : FragmentActivity() {
    val intents = MutableStateFlow<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intents.value = intent
        lifecycleScope.launch {
            Graph.prefs.hideInRecents.state.collect { hide ->
                if (hide) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        setContent { Root(this) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intents.value = intent
    }
}

@Composable
private fun Root(activity: MainActivity) {
    val accent by Graph.prefs.accent.state.collectAsState()
    val glowOn by Graph.prefs.glowEnabled.state.collectAsState()
    val strength by Graph.prefs.glowStrength.state.collectAsState()
    val locked by AppLock.locked.collectAsState()
    TwinStarTheme(Color(accent), if (glowOn) strength / 100f else 0f) {
        Box(Modifier.fillMaxSize().background(Ts.Bg)) {
            if (locked) LockScreen(activity) else App(activity)
        }
    }
}

@Composable
private fun App(activity: MainActivity) {
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf<Screen>(Screen.Main(Tab.NOTES)) }
    var folderFilter by remember { mutableStateOf<String?>(null) }
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun newNote() {
        scope.launch {
            val f = folderFilter?.takeIf { it != UNFILED }
            val id = Graph.repo.createNote(f)
            push(Screen.NoteView(id, edit = true))
        }
    }

    val pendingIntent by activity.intents.collectAsState()
    LaunchedEffect(pendingIntent) {
        val i = pendingIntent ?: return@LaunchedEffect
        activity.intents.value = null
        val noteId = i.getStringExtra(WidgetUpdater.EXTRA_NOTE)
        when {
            i.action == WidgetUpdater.ACTION_NEW -> newNote()
            noteId != null -> push(Screen.NoteView(noteId, edit = false))
        }
    }

    when (val s = stack.last()) {
        is Screen.Main -> {
            BackHandler(enabled = s.tab != Tab.NOTES || folderFilter != null) {
                if (s.tab != Tab.NOTES) stack[stack.lastIndex] = Screen.Main(Tab.NOTES) else folderFilter = null
            }
            MainShell(s.tab, onTab = { stack[stack.lastIndex] = Screen.Main(it) }, onNew = { newNote() }) {
                when (s.tab) {
                    Tab.NOTES -> HomeScreen(folderFilter, { folderFilter = it }) { push(Screen.NoteView(it, edit = false)) }
                    Tab.FOLDERS -> FoldersScreen(onOpenFolder = { folderFilter = it; stack[stack.lastIndex] = Screen.Main(Tab.NOTES) }, onTrash = { push(Screen.Trash) })
                    Tab.LINKS -> DevicesScreen()
                    Tab.CONFIG -> SettingsScreen(activity, onTrash = { push(Screen.Trash) })
                }
            }
        }
        is Screen.NoteView -> NoteScreen(s.id, s.edit, activity) { pop() }
        Screen.Trash -> TrashScreen { pop() }
    }

    SyncPromptHost()
}

@Composable
private fun SyncPromptHost() {
    val prompts by Graph.sync.prompts.collectAsState()
    val p = prompts.values.firstOrNull() ?: return
    val parts = listOfNotNull(
        if (p.weNeed > 0) "${p.weNeed} newer note${if (p.weNeed == 1) "" else "s"} on ${p.peerName}" else null,
        if (p.theyNeed > 0) "${p.theyNeed} newer here" else null,
    )
    TsDialog(
        "Out of sync", onDismiss = { Graph.sync.deferPrompt(p.pairId) },
        confirm = "SYNC NOW", onConfirm = { Graph.sync.acceptPrompt(p.pairId) }, dismiss = "LATER",
    ) {
        Text(
            "${p.peerName} was apart from this device. " + (if (parts.isEmpty()) "Folders or images differ." else parts.joinToString(", ") + ".") +
                " Newest edits win; any replaced version goes to the trash.",
            color = Ts.Dim, fontFamily = Ts.Mono, fontSize = 12.sp, lineHeight = 18.sp,
        )
    }
}

@Composable
private fun LockScreen(activity: FragmentActivity) {
    val ts = LocalTs.current
    val prefs = Graph.prefs
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val bio = prefs.biometric.value && AppLock.biometricAvailable(activity)
    fun tryBio() { if (bio) AppLock.promptBiometric(activity, "Unlock Twin Star S") { AppLock.locked.value = false } }
    LaunchedEffect(Unit) { tryBio() }
    fun press(d: String) {
        error = false
        if (pin.length < 12) pin += d
        if (pin.length >= 4 && AppLock.checkPin(prefs, pin)) { AppLock.locked.value = false; pin = "" }
    }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                append("TWIN"); pushStyle(androidx.compose.ui.text.SpanStyle(color = ts.accent)); append("✦"); pop(); append("STAR S")
            },
            fontFamily = Ts.Mono, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, fontSize = 18.sp, color = Ts.Text,
        )
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(maxOf(4, pin.length)) { i ->
                Box(Modifier.size(12.dp).border(1.dp, if (error) Color(0xFFFF8F8F) else ts.accent).background(if (i < pin.length) ts.accent else Color.Transparent))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(if (error) "WRONG PIN" else "ENTER PIN", color = if (error) Color(0xFFFF8F8F) else Ts.Dim, fontFamily = Ts.Mono, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(28.dp))
        val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("bio", "0", "del"))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.width(260.dp)) {
            rows.forEach { r ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    r.forEach { k ->
                        Box(
                            Modifier.weight(1f).aspectRatio(1.4f).border(1.dp, if (k == "bio" && !bio) Color.Transparent else Ts.Line2)
                                .clickable(enabled = k != "bio" || bio) {
                                    when (k) {
                                        "del" -> { pin = pin.dropLast(1); error = false }
                                        "bio" -> tryBio()
                                        else -> press(k)
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            when (k) {
                                "del" -> Icon(Icons.Filled.Backspace, "Delete", tint = Ts.Dim)
                                "bio" -> { if (bio) Icon(Icons.Filled.Fingerprint, "Biometric", tint = ts.accent) }
                                else -> Text(k, fontFamily = Ts.Mono, fontSize = 22.sp, color = Ts.Text)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "OK", color = ts.accent, fontFamily = Ts.Mono, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
            modifier = Modifier.fillMaxWidth().clickable { if (!AppLock.checkPin(prefs, pin)) { error = true; pin = "" } }.padding(12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
