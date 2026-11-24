package com.stellar.twinstar.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.stellar.twinstar.BuildConfig
import com.stellar.twinstar.Graph
import com.stellar.twinstar.security.AppLock
import com.stellar.twinstar.service.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(activity: FragmentActivity, onTrash: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("LOOK", "SECURITY", "SYNC", "BOOKS", "BACKUP")
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CONFIG", fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, fontFamily = Ts.Mono, color = Ts.Text, modifier = Modifier.weight(1f))
            Text("v${BuildConfig.VERSION_NAME}", fontSize = 11.sp, fontFamily = Ts.Mono, color = Ts.Dim)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            items(tabs.size) { i -> SettingsTab(tabs[i], tab == i) { tab = i } }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF16161A)))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
            when (tab) {
                0 -> LookTab()
                1 -> SecurityTab(activity)
                2 -> SyncTab(activity)
                3 -> BooksTab()
                else -> BackupTab(onTrash)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsTab(label: String, active: Boolean, onClick: () -> Unit) {
    val acc = LocalTs.current.accent
    Column(Modifier.clickable(onClick = onClick).padding(vertical = 4.dp)) {
        Text(if (active) "[$label]" else label, fontSize = 12.sp, letterSpacing = 1.sp, fontFamily = Ts.Mono,
            color = if (active) acc else Ts.Dim, style = if (active) glowText(acc) else TextStyle.Default)
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (active) acc else Color.Transparent))
    }
}

@Composable
private fun tsSlider() = SliderDefaults.colors(
    thumbColor = LocalTs.current.accent, activeTrackColor = LocalTs.current.accent, inactiveTrackColor = Ts.Line2,
)

// ---------------- LOOK ----------------
@Composable
private fun LookTab() {
    val ts = LocalTs.current
    val prefs = Graph.prefs
    val accent by prefs.accent.state.collectAsState()
    val glowOn by prefs.glowEnabled.state.collectAsState()
    val strength by prefs.glowStrength.state.collectAsState()
    val hide by prefs.hideInRecents.state.collectAsState()
    var hue by remember { mutableStateOf(hueOf(accent)) }

    SectionLabel("ACCENT")
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Ts.Swatches.forEach { c ->
            val sel = c == accent
            Box(
                Modifier.weight(1f).aspectRatio(1f)
                    .then(if (sel) Modifier.glow(Color(c), ts.glow, 14.dp) else Modifier)
                    .background(Color(c)).border(if (sel) 2.dp else 1.dp, if (sel) Ts.Text else Ts.Line2)
                    .clickable { prefs.accent.set(c); hue = hueOf(c) },
            )
        }
    }
    Spacer(Modifier.height(14.dp))
    Text("CUSTOM HUE", fontSize = 10.sp, letterSpacing = 2.sp, fontFamily = Ts.Mono, color = Ts.Dim)
    Slider(
        value = hue, onValueChange = { hue = it; prefs.accent.set(Color.hsv(it, 0.42f, 1f).toArgb()) },
        valueRange = 0f..359f, colors = tsSlider(),
    )
    SettingRow("Accent glow", "Soft light around pinned tiles, buttons, active tabs") {
        TsSwitch(glowOn, { prefs.glowEnabled.set(it) })
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).border(1.dp, Color(0xFF1C1C20)).padding(12.dp)) {
        Row {
            Text("Glow strength", fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Ts.Mono, color = Ts.Text, modifier = Modifier.weight(1f))
            Text(if (glowOn) "$strength%" else "OFF", fontSize = 13.sp, fontFamily = Ts.Mono, color = ts.accent)
        }
        Slider(
            value = strength.toFloat(), onValueChange = { prefs.glowStrength.set(it.toInt()); if (!glowOn) prefs.glowEnabled.set(true) },
            valueRange = 0f..100f, colors = tsSlider(),
        )
    }
    Spacer(Modifier.height(12.dp))
    SectionLabel("PREVIEW")
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            Modifier.size(104.dp).glow(ts.accent, ts.glow, 16.dp).background(Ts.Card).border(1.dp, ts.accent).padding(9.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text("▲ PIN", fontSize = 9.sp, letterSpacing = 1.sp, fontFamily = Ts.Mono, color = ts.accent)
            Text("launch_list", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Ts.Mono, color = Ts.Text)
            Text("[ ] icon\n[x] relay", fontSize = 10.sp, fontFamily = Ts.Mono, color = Ts.Dim, lineHeight = 14.sp)
        }
        Box(Modifier.size(56.dp).glow(ts.accent, ts.glow).border(1.dp, ts.accent).background(Ts.Bg), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, null, tint = ts.accent)
        }
        Text("[ALL]", fontSize = 12.sp, fontFamily = Ts.Mono, letterSpacing = 1.sp, color = ts.accent, style = glowText(ts.accent))
    }
    Spacer(Modifier.height(16.dp))
    SettingRow("Hide in recents", "Blank preview in the app switcher; blocks screenshots") {
        TsSwitch(hide, { prefs.hideInRecents.set(it) })
    }
}

private fun hueOf(argb: Int): Float {
    val hsv = FloatArray(3); android.graphics.Color.colorToHSV(argb, hsv); return hsv[0]
}

// ---------------- SECURITY ----------------
@Composable
private fun SecurityTab(activity: FragmentActivity) {
    val prefs = Graph.prefs
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val enc by prefs.encryption.state.collectAsState()
    val lock by prefs.lockEnabled.state.collectAsState()
    val bio by prefs.biometric.state.collectAsState()
    val timeout by prefs.lockTimeoutMs.state.collectAsState()
    val pinHash by prefs.pinHash.state.collectAsState()
    var working by remember { mutableStateOf(false) }
    var pinDialog by remember { mutableStateOf<String?>(null) } // "enable" | "change"
    var confirmOff by remember { mutableStateOf(false) }

    SectionLabel("DATA")
    Spacer(Modifier.height(8.dp))
    SettingRow(
        "Encrypt notes on this device",
        if (working) "Re-encrypting…" else "Uses a key locked inside this phone's secure hardware",
    ) {
        TsSwitch(enc, { on ->
            if (!on) confirmOff = true else { working = true; scope.launch { Graph.repo.setEncryption(true); working = false } }
        }, enabled = !working)
    }
    Spacer(Modifier.height(14.dp))
    SectionLabel("APP LOCK")
    Spacer(Modifier.height(8.dp))
    SettingRow("Lock the app", if (pinHash.isEmpty()) "Set a PIN to turn on" else "PIN required to open") {
        TsSwitch(lock, { on ->
            if (on && pinHash.isEmpty()) pinDialog = "enable" else prefs.lockEnabled.set(on)
        })
    }
    if (AppLock.biometricAvailable(ctx)) SettingRow("Fingerprint / face unlock", "Used for the app and locked notes") {
        TsSwitch(bio, { prefs.biometric.set(it) })
    }
    SettingRow(if (pinHash.isEmpty()) "Set PIN" else "Change PIN", "Also unlocks individual locked notes", onClick = { pinDialog = "change" })
    Spacer(Modifier.height(10.dp))
    Text("AUTO-LOCK", fontSize = 10.sp, letterSpacing = 2.sp, fontFamily = Ts.Mono, color = Ts.Dim)
    Spacer(Modifier.height(8.dp))
    ChoiceRow(listOf("NOW" to 0, "1 MIN" to 60_000, "5 MIN" to 300_000, "15 MIN" to 900_000), timeout) { prefs.lockTimeoutMs.set(it) }

    if (pinDialog != null) PinDialog(if (pinHash.isEmpty()) "Set PIN" else "New PIN", onDismiss = { pinDialog = null }) { pin ->
        AppLock.setPin(prefs, pin)
        if (pinDialog == "enable") prefs.lockEnabled.set(true)
        pinDialog = null
        Toast.makeText(ctx, "PIN saved", Toast.LENGTH_SHORT).show()
    }
    if (confirmOff) ConfirmDialog(
        "Turn off encryption?", "Notes will be stored as plain text on this phone. Sync between devices stays encrypted.", "TURN OFF",
        onDismiss = { confirmOff = false },
    ) {
        confirmOff = false; working = true
        scope.launch { Graph.repo.setEncryption(false); working = false }
    }
}

@Composable
private fun <T> ChoiceRow(options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) {
    val ts = LocalTs.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { (label, v) ->
            val sel = v == selected
            Box(
                Modifier.weight(1f).height(44.dp)
                    .then(if (sel) Modifier.glow(ts.accent, ts.glow * 0.6f, 12.dp) else Modifier)
                    .background(Ts.Bg).border(1.dp, if (sel) ts.accent else Ts.Line2).clickable { onPick(v) },
                contentAlignment = Alignment.Center,
            ) { Text(label, fontSize = 11.sp, letterSpacing = 1.sp, fontFamily = Ts.Mono, color = if (sel) ts.accent else Ts.Dim) }
        }
    }
}

// ---------------- SYNC ----------------
@Composable
private fun SyncTab(activity: FragmentActivity) {
    val prefs = Graph.prefs
    val ctx = LocalContext.current
    val bg by prefs.backgroundSync.state.collectAsState()
    val ask by prefs.askBeforeCatchup.state.collectAsState()
    val lan by prefs.lanEnabled.state.collectAsState()
    val name by prefs.deviceName.state.collectAsState()
    val relays by prefs.relays.state.collectAsState()
    val relayCount by Graph.sync.relayCount.collectAsState()
    var editName by remember { mutableStateOf(false) }
    var editRelays by remember { mutableStateOf(false) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    SectionLabel("THIS DEVICE")
    Spacer(Modifier.height(8.dp))
    SettingRow("Device name", name, onClick = { editName = true })
    Spacer(Modifier.height(14.dp))
    SectionLabel("LIVE SYNC")
    Spacer(Modifier.height(8.dp))
    SettingRow("Keep syncing when closed", "Shows a quiet notification so Android keeps the link alive") {
        TsSwitch(bg, { on ->
            prefs.backgroundSync.set(on)
            if (on && Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            SyncService.update(ctx)
        })
    }
    SettingRow("Ask before catching up", "When devices were apart, confirm before syncing the differences") {
        TsSwitch(ask, { prefs.askBeforeCatchup.set(it) })
    }
    SettingRow("Same Wi-Fi direct sync", "Talk straight to linked devices on your network") {
        TsSwitch(lan, { prefs.lanEnabled.set(it); Graph.sync.restartTransports() })
    }
    SettingRow("Sync now", "Check every linked device for differences", onClick = { Graph.sync.syncNow(); Toast.makeText(ctx, "Checking linked devices…", Toast.LENGTH_SHORT).show() })
    Spacer(Modifier.height(14.dp))
    SectionLabel("INTERNET RELAYS")
    Spacer(Modifier.height(8.dp))
    SettingRow("Relays · $relayCount connected", prefs.relayList().joinToString("\n") { it.removePrefix("wss://") } +
        "\nRelays only pass along encrypted data they can't read.", onClick = { editRelays = true })

    if (editName) TextInputDialog("Device name", initial = name, onDismiss = { editName = false }) {
        editName = false; if (it.isNotBlank()) prefs.deviceName.set(it.trim())
    }
    if (editRelays) {
        var text by remember { mutableStateOf(relays) }
        TsDialog("Relays", { editRelays = false }, confirm = "SAVE", onConfirm = {
            prefs.relays.set(text.trim().ifEmpty { com.stellar.twinstar.data.Prefs.DEFAULT_RELAYS.joinToString("\n") })
            editRelays = false; Graph.sync.restartTransports()
        }) {
            Column {
                Text("One wss:// address per line. Every linked device should share at least one.", color = Ts.Dim, fontFamily = Ts.Mono, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(text, { text = it }, shape = RectangleShape, colors = tsFieldColors(), minLines = 4,
                    textStyle = TextStyle(fontFamily = Ts.Mono, color = Ts.Text, fontSize = 12.sp), modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------------- BOOKS ----------------
@Composable
private fun BooksTab() {
    val prefs = Graph.prefs
    val fit by prefs.pageFit.state.collectAsState()
    val anim by prefs.pageAnim.state.collectAsState()
    SectionLabel("PAGES")
    Spacer(Modifier.height(8.dp))
    SettingRow(
        "Page limit",
        if (fit) "On: each page fills the screen and text flows onto a new page" else "Off: pages are any length; add pages with the New page button",
    ) { TsSwitch(fit, { prefs.pageFit.set(it) }) }
    Spacer(Modifier.height(14.dp))
    SectionLabel("PAGE TURN")
    Spacer(Modifier.height(8.dp))
    ChoiceRow(listOf("FLIP" to 0, "CURL" to 1, "SLIDE" to 2), anim) { prefs.pageAnim.set(it) }
    Spacer(Modifier.height(10.dp))
    Text(
        when (anim) { 0 -> "3D flip from the left edge, like a real page."; 1 -> "The page peels back from the right edge."; else -> "Pages slide flat sideways." },
        color = Ts.Dim, fontFamily = Ts.Mono, fontSize = 11.sp,
    )
    Spacer(Modifier.height(10.dp))
    Text("Swipe or tap the left / right edge of a page to turn it. Tap the middle to edit.", color = Ts.Faint, fontFamily = Ts.Mono, fontSize = 11.sp, lineHeight = 16.sp)
}

// ---------------- BACKUP ----------------
@Composable
private fun BackupTab(onTrash: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var askExport by remember { mutableStateOf(false) }
    var exportPw by remember { mutableStateOf("") }
    var importUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var status by remember { mutableStateOf("") }

    val create = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val pw = exportPw; exportPw = ""
        status = "Encrypting backup…"
        scope.launch {
            status = try {
                val bytes = Graph.repo.exportBackup(pw)
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }
                "Backup saved (${bytes.size / 1024} KB)."
            } catch (e: Exception) { "Backup failed: ${e.message}" }
        }
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> importUri = uri }

    SectionLabel("ENCRYPTED BACKUP")
    Spacer(Modifier.height(8.dp))
    SettingRow("Export backup", "All notes, folders and images in one file, locked with a password you choose", onClick = { askExport = true })
    SettingRow("Import backup", "Merges a backup into this device; newer edits win", onClick = { open.launch(arrayOf("*/*")) })
    if (status.isNotEmpty()) Text(status, color = LocalTs.current.accent, fontFamily = Ts.Mono, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
    Spacer(Modifier.height(14.dp))
    SectionLabel("TRASH")
    Spacer(Modifier.height(8.dp))
    SettingRow("Open trash", "Deleted notes stay 30 days, then are removed for good", onClick = onTrash)
    Spacer(Modifier.height(20.dp))
    Text("Twin Star S ${BuildConfig.VERSION_NAME} · notes live on your devices only", color = Ts.Faint, fontFamily = Ts.Mono, fontSize = 10.sp)

    if (askExport) TextInputDialog("Backup password", label = "Password (min 6)", password = true, onDismiss = { askExport = false }) { pw ->
        if (pw.length >= 6) {
            askExport = false; exportPw = pw
            create.launch("twinstar-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".tsbak")
        } else Toast.makeText(ctx, "Use at least 6 characters", Toast.LENGTH_SHORT).show()
    }
    importUri?.let { uri ->
        TextInputDialog("Backup password", label = "Password", password = true, onDismiss = { importUri = null }) { pw ->
            importUri = null; status = "Importing…"
            scope.launch {
                val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
                val n = if (bytes == null) -1 else Graph.repo.importBackup(bytes, pw)
                status = if (n < 0) "Wrong password or not a Twin Star backup." else "Imported $n notes."
            }
        }
    }
}
