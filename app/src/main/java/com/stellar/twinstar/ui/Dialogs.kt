package com.stellar.twinstar.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellar.twinstar.data.Folder
import com.stellar.twinstar.data.Peer

@Composable
fun TsDialog(
    title: String,
    onDismiss: () -> Unit,
    confirm: String? = "OK",
    onConfirm: () -> Unit = onDismiss,
    dismiss: String? = "CANCEL",
    content: @Composable () -> Unit,
) {
    val acc = LocalTs.current.accent
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RectangleShape,
        containerColor = Color(0xFF0A0A0C),
        modifier = Modifier.border(1.dp, Ts.Line2),
        title = { Text(title.uppercase(), fontFamily = Ts.Mono, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, color = Ts.Text) },
        text = { content() },
        confirmButton = { if (confirm != null) TextButton(onClick = onConfirm) { Text(confirm, color = acc, fontFamily = Ts.Mono, fontWeight = FontWeight.Bold) } },
        dismissButton = { if (dismiss != null) TextButton(onClick = onDismiss) { Text(dismiss, color = Ts.Dim, fontFamily = Ts.Mono) } },
    )
}

@Composable
fun tsFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LocalTs.current.accent, unfocusedBorderColor = Ts.Line2, cursorColor = LocalTs.current.accent,
    focusedTextColor = Ts.Text, unfocusedTextColor = Ts.Text, focusedLabelColor = LocalTs.current.accent, unfocusedLabelColor = Ts.Dim,
)

@Composable
fun TextInputDialog(title: String, initial: String = "", label: String = "", password: Boolean = false, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    TsDialog(title, onDismiss, onConfirm = { onDone(v) }) {
        OutlinedTextField(
            v, { v = it }, singleLine = true, shape = RectangleShape, colors = tsFieldColors(),
            label = label.takeIf { it.isNotEmpty() }?.let { l -> @Composable { Text(l, fontFamily = Ts.Mono) } },
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Ts.Mono, color = Ts.Text),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun PinDialog(title: String, onDismiss: () -> Unit, onPin: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    TsDialog(title, onDismiss, onConfirm = { if (pin.length >= 4) onPin(pin) }) {
        Column {
            Text("At least 4 digits", color = Ts.Dim, fontSize = 11.sp, fontFamily = Ts.Mono)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                pin, { s -> pin = s.filter { it.isDigit() }.take(12) }, singleLine = true, shape = RectangleShape,
                colors = tsFieldColors(), visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Ts.Mono, color = Ts.Text, fontSize = 20.sp, letterSpacing = 6.sp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagsDialog(current: List<String>, allTags: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    var tags by remember { mutableStateOf(current) }
    var input by remember { mutableStateOf("") }
    val acc = LocalTs.current.accent
    fun commit() {
        val add = input.split(',', ' ').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }
        tags = (tags + add).distinct(); input = ""
    }
    TsDialog("Tags", onDismiss, confirm = "SAVE", onConfirm = { commit(); onDone(tags) }) {
        Column {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { t ->
                    Text("#$t  ✕", color = acc, fontFamily = Ts.Mono, fontSize = 12.sp,
                        modifier = Modifier.border(1.dp, acc).clickable { tags = tags - t }.padding(horizontal = 8.dp, vertical = 6.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                input, { input = it }, singleLine = true, shape = RectangleShape, colors = tsFieldColors(),
                label = { Text("Add tag", fontFamily = Ts.Mono) },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Ts.Mono, color = Ts.Text),
                trailingIcon = { TextButton(onClick = { commit() }) { Text("ADD", color = acc, fontFamily = Ts.Mono) } },
                modifier = Modifier.fillMaxWidth(),
            )
            val suggestions = allTags.filter { it !in tags }.take(12)
            if (suggestions.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { t ->
                        Text("#$t", color = Ts.Dim, fontFamily = Ts.Mono, fontSize = 12.sp,
                            modifier = Modifier.border(1.dp, Ts.Line2).clickable { tags = tags + t }.padding(horizontal = 8.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun FolderPickerDialog(folders: List<Folder>, current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit, onNew: () -> Unit) {
    val acc = LocalTs.current.accent
    TsDialog("Move to folder", onDismiss, confirm = null, dismiss = "CLOSE") {
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
            item { PickRow("No folder", current == null, acc) { onPick(null) } }
            items(folders, key = { it.id }) { f -> PickRow(f.name, current == f.id, acc) { onPick(f.id) } }
            item { PickRow("+ New folder", false, acc, dim = true) { onNew() } }
        }
    }
}

@Composable
private fun PickRow(label: String, selected: Boolean, acc: Color, dim: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (selected) "[$label]" else label, color = if (selected) acc else if (dim) Ts.Dim else Ts.Text, fontFamily = Ts.Mono, fontSize = 14.sp)
    }
}

/** Per-device share toggles. [viaFolder] marks devices that already get this through the folder. */
@Composable
fun ShareDialog(title: String, peers: List<Peer>, shared: Set<String>, viaFolder: Set<String>, online: Set<String>, onDismiss: () -> Unit, onToggle: (String, Boolean) -> Unit) {
    val acc = LocalTs.current.accent
    TsDialog(title, onDismiss, confirm = "DONE", dismiss = null) {
        if (peers.isEmpty()) {
            Text("No linked devices yet. Link one from LINKS.", color = Ts.Dim, fontFamily = Ts.Mono, fontSize = 12.sp)
        } else Column {
            peers.forEach { p ->
                val folderOn = p.pairId in viaFolder
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(if (p.pairId in online) acc else Color(0xFF3A3A40)))
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(p.name, fontFamily = Ts.Mono, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ts.Text)
                        if (folderOn) Text("shared via folder", fontFamily = Ts.Mono, fontSize = 10.sp, color = Ts.Dim)
                    }
                    TsSwitch(checked = folderOn || p.pairId in shared, onChange = { onToggle(p.pairId, it) }, enabled = !folderOn)
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    TsDialog(title, onDismiss, confirm = confirm, onConfirm = onConfirm) {
        Text(text, color = Ts.Dim, fontFamily = Ts.Mono, fontSize = 13.sp, lineHeight = 19.sp)
    }
}
