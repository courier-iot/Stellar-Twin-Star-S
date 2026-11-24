package com.stellar.twinstar.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.stellar.twinstar.Graph
import com.stellar.twinstar.data.Peer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun DevicesScreen() {
    val ts = LocalTs.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val peers by Graph.repo.peers.collectAsState(initial = emptyList())
    val online by Graph.sync.online.collectAsState()
    var showQr by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<Peer?>(null) }
    var rename by remember { mutableStateOf<Peer?>(null) }
    var unpair by remember { mutableStateOf<Peer?>(null) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@rememberLauncherForActivityResult
        scope.launch {
            val name = Graph.sync.completePairing(text)
            Toast.makeText(ctx, if (name != null) "Linked with $name" else "That isn't a Twin Star pairing code", Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        HeaderBar("LINKS")
        Text(
            "Linked devices sync shared notes within seconds. Each link has its own secret key that only the two devices hold.",
            color = Ts.Dim, fontFamily = Ts.Mono, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )
        Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("SHOW MY CODE", Modifier.weight(1f), filled = true) { showQr = true }
            BigButton("SCAN A CODE", Modifier.weight(1f)) {
                scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the code on the other device").setBeepEnabled(false).setOrientationLocked(false))
            }
        }
        SectionLabel("LINKED · ${peers.size}", Modifier.padding(horizontal = 18.dp))
        LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(peers, key = { it.pairId }) { p ->
                val on = p.pairId in online
                Box {
                    Column(
                        Modifier.fillMaxWidth().background(Ts.Card).border(1.dp, if (on) ts.accent else Ts.Line)
                            .clickable { menu = p }.padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).then(if (on) Modifier.glow(ts.accent, ts.glow, 8.dp) else Modifier).background(if (on) ts.accent else Color(0xFF3A3A40)))
                            Spacer(Modifier.width(10.dp))
                            Text(p.name.uppercase(), fontFamily = Ts.Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.sp, color = Ts.Text, modifier = Modifier.weight(1f))
                            Text(if (on) "ONLINE" else lastSeen(p.lastSeen), fontFamily = Ts.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = if (on) ts.accent else Ts.Dim)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Auto-share new notes", fontFamily = Ts.Mono, fontSize = 12.sp, color = Ts.Dim, modifier = Modifier.weight(1f))
                            TsSwitch(p.autoShareNew, { v -> scope.launch { Graph.repo.setAutoShare(p.pairId, v) } })
                        }
                    }
                    androidx.compose.material3.DropdownMenu(expanded = menu?.pairId == p.pairId, onDismissRequest = { menu = null }, modifier = Modifier.background(Color(0xFF0A0A0C))) {
                        MenuItem("Rename") { menu = null; rename = p }
                        MenuItem("Sync now") { menu = null; Graph.sync.syncNow() }
                        MenuItem("Unlink device") { menu = null; unpair = p }
                    }
                }
            }
            if (peers.isEmpty()) item {
                Text(
                    "NO LINKED DEVICES\n\nOn the other phone open LINKS → SCAN A CODE,\nthen tap SHOW MY CODE here.",
                    color = Ts.Dim, fontFamily = Ts.Mono, fontSize = 11.sp, letterSpacing = 1.sp, lineHeight = 17.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                )
            }
        }
    }

    if (showQr) PairingDialog { showQr = false }
    rename?.let { p ->
        TextInputDialog("Rename device", initial = p.name, onDismiss = { rename = null }) { scope.launch { Graph.repo.renamePeer(p.pairId, it); Graph.sync.reloadPairs() }; rename = null }
    }
    unpair?.let { p ->
        ConfirmDialog("Unlink ${p.name}?", "Both devices keep their copies of notes, but stop syncing. You can link again with a new code.", "UNLINK", { unpair = null }) {
            Graph.sync.unpair(p.pairId); unpair = null
        }
    }
}

private fun lastSeen(t: Long): String {
    if (t == 0L) return "NEVER"
    val m = (System.currentTimeMillis() - t) / 60_000
    return when { m < 1 -> "JUST NOW"; m < 60 -> "${m}M AGO"; m < 1440 -> "${m / 60}H AGO"; else -> "${m / 1440}D AGO" }
}

@Composable
private fun BigButton(label: String, modifier: Modifier, filled: Boolean = false, onClick: () -> Unit) {
    val ts = LocalTs.current
    Box(
        modifier.height(52.dp).then(if (filled) Modifier.glow(ts.accent, ts.glow) else Modifier)
            .background(Ts.Bg).border(1.dp, if (filled) ts.accent else Ts.Line2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (filled) ts.accent else Ts.Text, fontFamily = Ts.Mono, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp) }
}

@Composable
private fun PairingDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val payload = remember { Graph.sync.beginPairing() }
    val bmp = remember(payload) { qrBitmap(payload, 640) }
    var left by remember { mutableStateOf(300) }
    LaunchedEffect(Unit) { while (left > 0) { delay(1_000); left-- }; Graph.sync.cancelPairing(); onClose() }
    LaunchedEffect(Unit) {
        Graph.sync.paired.collect { name ->
            Toast.makeText(ctx, "Linked with $name", Toast.LENGTH_LONG).show()
            onClose()
        }
    }
    TsDialog("Pairing code", onDismiss = { Graph.sync.cancelPairing(); onClose() }, confirm = null, dismiss = "CANCEL") {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Image(bmp.asImageBitmap(), "Pairing QR code", modifier = Modifier.size(240.dp).background(Color.White).padding(8.dp))
            Spacer(Modifier.height(12.dp))
            Text("Scan with Twin Star S on the other device", color = Ts.Dim, fontFamily = Ts.Mono, fontSize = 11.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text("EXPIRES IN %d:%02d".format(left / 60, left % 60), color = LocalTs.current.accent, fontFamily = Ts.Mono, fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(4.dp))
            Text("Only show this to your own device. Anyone who scans it is linked.", color = Ts.Faint, fontFamily = Ts.Mono, fontSize = 10.sp, textAlign = TextAlign.Center)
        }
    }
}

fun qrBitmap(text: String, size: Int): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) px[y * size + x] = if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}
