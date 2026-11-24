package com.stellar.twinstar.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.stellar.twinstar.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val PAGE_BREAK = "=== page ==="

sealed class Md {
    data class Title(val text: AnnotatedString) : Md()
    data class Heading(val level: Int, val text: AnnotatedString) : Md()
    data class Para(val text: AnnotatedString) : Md()
    data class Item(val indent: Int, val marker: String, val text: AnnotatedString) : Md()
    data class Check(val indent: Int, val checked: Boolean, val text: AnnotatedString, val line: Int) : Md()
    data class Quote(val text: AnnotatedString) : Md()
    data class Code(val text: AnnotatedString) : Md()
    data class Img(val id: String) : Md()
    object Rule : Md()
    object Blank : Md()
    object Break : Md()
}

private val reHeading = Regex("^(#{1,3})\\s+(.*)$")
private val reCheck = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s?(.*)$")
private val reBullet = Regex("^(\\s*)[-*+]\\s+(.*)$")
private val reNum = Regex("^(\\s*)(\\d{1,4})[.)]\\s+(.*)$")
private val reLetter = Regex("^(\\s*)([a-zA-Z])[.)]\\s+(.*)$")
private val reImg = Regex("^!\\[[^\\]]*]\\(att:([A-Za-z0-9]+)\\)\\s*$")
private val reInline = Regex("(\\*\\*[^*]+\\*\\*)|(~~[^~]+~~)|(`[^`]+`)|(\\*[^*\\s][^*]*\\*)|(_[^_\\s][^_]*_)|(\\[[^\\]]+]\\([^)]+\\))")

fun inline(src: String, accent: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    for (m in reInline.findAll(src)) {
        if (m.range.first > i) append(src.substring(i, m.range.first))
        val t = m.value
        when {
            t.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(t.substring(2, t.length - 2)) }
            t.startsWith("~~") -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = Ts.Dim)) { append(t.substring(2, t.length - 2)) }
            t.startsWith("`") -> withStyle(SpanStyle(background = Color(0xFF16161A), color = accent)) { append(t.substring(1, t.length - 1)) }
            t.startsWith("[") -> {
                val label = t.substring(1, t.indexOf("]("))
                withStyle(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)) { append(label) }
            }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(t.substring(1, t.length - 1)) }
        }
        i = m.range.last + 1
    }
    if (i < src.length) append(src.substring(i))
}

fun parseMarkdown(title: String, body: String, accent: Color): List<Md> {
    val out = ArrayList<Md>()
    if (title.isNotBlank()) out += Md.Title(AnnotatedString(title))
    val lines = body.split('\n')
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()
        when {
            line.trim() == PAGE_BREAK -> out += Md.Break
            line.trimStart().startsWith("```") -> {
                val sb = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { if (sb.isNotEmpty()) sb.append('\n'); sb.append(lines[i]); i++ }
                out += Md.Code(AnnotatedString(sb.toString()))
            }
            line.isBlank() -> if (out.lastOrNull() != Md.Blank) out += Md.Blank
            line.trim() == "---" || line.trim() == "***" -> out += Md.Rule
            reImg.matches(line.trim()) -> out += Md.Img(reImg.find(line.trim())!!.groupValues[1])
            reHeading.matches(line) -> {
                val m = reHeading.find(line)!!
                out += Md.Heading(m.groupValues[1].length, inline(m.groupValues[2], accent))
            }
            reCheck.matches(line) -> {
                val m = reCheck.find(line)!!
                out += Md.Check(m.groupValues[1].length / 2, m.groupValues[2] != " ", inline(m.groupValues[3], accent), i)
            }
            reBullet.matches(line) -> {
                val m = reBullet.find(line)!!
                out += Md.Item(m.groupValues[1].length / 2, "•", inline(m.groupValues[2], accent))
            }
            reNum.matches(line) -> {
                val m = reNum.find(line)!!
                out += Md.Item(m.groupValues[1].length / 2, m.groupValues[2] + ".", inline(m.groupValues[3], accent))
            }
            reLetter.matches(line) -> {
                val m = reLetter.find(line)!!
                out += Md.Item(m.groupValues[1].length / 2, m.groupValues[2] + ".", inline(m.groupValues[3], accent))
            }
            line.startsWith(">") -> out += Md.Quote(inline(line.removePrefix(">").trimStart(), accent))
            else -> out += Md.Para(inline(line, accent))
        }
        i++
    }
    while (out.lastOrNull() == Md.Blank) out.removeAt(out.lastIndex)
    return out
}

/** Toggles "[ ]" / "[x]" on a body line. */
fun toggleCheckLine(body: String, line: Int): String {
    val lines = body.split('\n').toMutableList()
    if (line !in lines.indices) return body
    val l = lines[line]
    lines[line] = when {
        l.contains("[ ]") -> l.replaceFirst("[ ]", "[x]")
        l.contains("[x]") -> l.replaceFirst("[x]", "[ ]")
        l.contains("[X]") -> l.replaceFirst("[X]", "[ ]")
        else -> l
    }
    return lines.joinToString("\n")
}

// ---------- styles (shared by layout and measurement so pagination matches) ----------
object BookStyle {
    val body = TextStyle(fontFamily = Ts.Mono, fontSize = 15.sp, lineHeight = 26.sp, color = Color(0xFFD2D2D6))
    val title = TextStyle(fontFamily = Ts.Mono, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, color = Ts.Text, letterSpacing = (-0.5).sp)
    fun heading(level: Int) = TextStyle(
        fontFamily = Ts.Mono, fontWeight = FontWeight.Bold, color = Ts.Text,
        fontSize = when (level) { 1 -> 22.sp; 2 -> 19.sp; else -> 16.sp },
        lineHeight = when (level) { 1 -> 28.sp; 2 -> 25.sp; else -> 22.sp },
    )
    val code = TextStyle(fontFamily = Ts.Mono, fontSize = 13.sp, lineHeight = 20.sp, color = Color(0xFFC9C9CE))
    val quote = body.copy(color = Ts.Dim, fontStyle = FontStyle.Italic)
    val gap = 8.dp
    fun markerWidth(indent: Int) = (26 + indent * 18).dp
    val codePad = 10.dp
    val ruleH = 24.dp
    val blankH = 10.dp
}

private fun textOf(b: Md): AnnotatedString? = when (b) {
    is Md.Title -> b.text; is Md.Heading -> b.text; is Md.Para -> b.text; is Md.Item -> b.text
    is Md.Check -> b.text; is Md.Quote -> b.text; is Md.Code -> b.text; else -> null
}

private fun withText(b: Md, t: AnnotatedString, continuation: Boolean): Md = when (b) {
    is Md.Title -> Md.Title(t); is Md.Heading -> Md.Heading(b.level, t); is Md.Para -> Md.Para(t)
    is Md.Item -> Md.Item(b.indent, if (continuation) "" else b.marker, t)
    is Md.Check -> if (continuation) Md.Item(b.indent, "", t) else b.copy(text = t)
    is Md.Quote -> Md.Quote(t); is Md.Code -> Md.Code(t); else -> b
}

/**
 * Splits blocks into pages. With [fit] the page fills the screen and text flows onto the next page;
 * otherwise only manual page breaks start new pages.
 */
fun paginate(blocks: List<Md>, fit: Boolean, measurer: TextMeasurer, widthPx: Int, heightPx: Int, density: Density): List<List<Md>> {
    val pages = ArrayList<List<Md>>()
    var cur = ArrayList<Md>()
    if (!fit) {
        for (b in blocks) if (b == Md.Break) { pages += cur; cur = ArrayList() } else cur += b
        pages += cur
        return pages.map { p -> p.dropWhile { it == Md.Blank } }.ifEmpty { listOf(emptyList()) }
    }
    val limit = heightPx * 0.97f
    val gapPx = with(density) { BookStyle.gap.toPx() }
    var used = 0f

    fun styleOf(b: Md): TextStyle = when (b) {
        is Md.Title -> BookStyle.title; is Md.Heading -> BookStyle.heading(b.level); is Md.Code -> BookStyle.code
        is Md.Quote -> BookStyle.quote; else -> BookStyle.body
    }
    fun textWidth(b: Md): Int = with(density) {
        when (b) {
            is Md.Item -> widthPx - BookStyle.markerWidth(b.indent).roundToPx()
            is Md.Check -> widthPx - BookStyle.markerWidth(b.indent).roundToPx()
            is Md.Quote -> widthPx - 18.dp.roundToPx()
            is Md.Code -> widthPx - (BookStyle.codePad * 2).roundToPx()
            else -> widthPx
        }
    }.coerceAtLeast(50)
    fun extra(b: Md): Float = with(density) { if (b is Md.Code) (BookStyle.codePad * 2).toPx() else 0f }
    fun newPage() { pages += cur; cur = ArrayList(); used = 0f }

    val queue = ArrayDeque(blocks)
    var continuation = false
    while (queue.isNotEmpty()) {
        val b = queue.removeFirst()
        if (b == Md.Break) { if (cur.isNotEmpty()) newPage(); continuation = false; continue }
        if (b == Md.Blank && cur.isEmpty()) continue
        val g = if (cur.isEmpty()) 0f else gapPx
        val text = textOf(b)
        if (text == null) {
            val h = with(density) {
                when (b) {
                    is Md.Img -> minOf(heightPx * 0.45f, widthPx * 0.75f)
                    Md.Rule -> BookStyle.ruleH.toPx()
                    else -> BookStyle.blankH.toPx()
                }
            }
            if (used + g + h > limit && cur.isNotEmpty()) { newPage(); if (b == Md.Blank) continue; cur += b; used = h }
            else { cur += b; used += g + h }
            continue
        }
        val layout = measurer.measure(text, styleOf(b), constraints = Constraints(maxWidth = textWidth(b)), density = density)
        val h = layout.size.height + extra(b)
        if (used + g + h <= limit) { cur += withText(b, text, continuation); used += g + h; continuation = false; continue }
        // Doesn't fit: split by lines where possible.
        val room = limit - used - g - extra(b)
        var fitLines = 0
        for (ln in 0 until layout.lineCount) if (layout.getLineBottom(ln) <= room) fitLines = ln + 1 else break
        val splittable = b !is Md.Title && b !is Md.Heading && layout.lineCount > 1
        if (splittable && fitLines >= 1) {
            val end = layout.getLineEnd(fitLines - 1, visibleEnd = false).coerceIn(1, text.length)
            cur += withText(b, text.subSequence(0, end), continuation)
            newPage()
            val rest = text.subSequence(end, text.length)
            val restTrim = if (rest.text.startsWith(" ")) rest.subSequence(1, rest.length) else rest
            queue.addFirst(withText(b, restTrim, false))
            continuation = true
        } else if (cur.isNotEmpty()) {
            newPage(); queue.addFirst(b)
        } else {
            // A single unsplittable block taller than a page: place it alone.
            cur += withText(b, text, continuation); newPage(); continuation = false
        }
    }
    if (cur.isNotEmpty() || pages.isEmpty()) pages += cur
    return pages
}

@Composable
fun MdBlockView(b: Md, accent: Color, onToggle: (Int) -> Unit) {
    when (b) {
        is Md.Title -> Text(b.text, style = BookStyle.title, modifier = Modifier.fillMaxWidth())
        is Md.Heading -> Text(b.text, style = BookStyle.heading(b.level), modifier = Modifier.fillMaxWidth())
        is Md.Para -> Text(b.text, style = BookStyle.body, modifier = Modifier.fillMaxWidth())
        is Md.Quote -> Row(Modifier.fillMaxWidth()) {
            Text("›", style = BookStyle.body.copy(color = accent), modifier = Modifier.width(18.dp))
            Text(b.text, style = BookStyle.quote, modifier = Modifier.weight(1f))
        }
        is Md.Item -> Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(BookStyle.markerWidth(b.indent)).padding(start = (b.indent * 18).dp)) {
                Text(b.marker, style = BookStyle.body.copy(color = accent))
            }
            Text(b.text, style = BookStyle.body, modifier = Modifier.weight(1f))
        }
        is Md.Check -> Row(Modifier.fillMaxWidth()) {
            Box(
                Modifier.width(BookStyle.markerWidth(b.indent)).padding(start = (b.indent * 18).dp)
                    .clickable { onToggle(b.line) },
            ) {
                Box(
                    Modifier.padding(top = 5.dp).size(15.dp).border(1.dp, if (b.checked) accent else Ts.Faint)
                        .background(if (b.checked) accent else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) { if (b.checked) Text("✓", fontSize = 10.sp, color = Color.Black, lineHeight = 10.sp) }
            }
            Text(
                b.text,
                style = if (b.checked) BookStyle.body.copy(color = Ts.Faint, textDecoration = TextDecoration.LineThrough) else BookStyle.body,
                modifier = Modifier.weight(1f),
            )
        }
        is Md.Code -> Box(Modifier.fillMaxWidth().background(Color(0xFF0E0E11)).border(1.dp, Ts.Line).padding(BookStyle.codePad)) {
            Text(b.text, style = BookStyle.code)
        }
        is Md.Img -> AttachmentImage(b.id)
        Md.Rule -> Box(Modifier.fillMaxWidth().height(BookStyle.ruleH), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ts.Line2))
        }
        Md.Blank -> Box(Modifier.height(BookStyle.blankH))
        Md.Break -> Unit
    }
}

@Composable
fun AttachmentImage(id: String) {
    var bmp by remember(id) { mutableStateOf<ImageBitmap?>(null) }
    var tried by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        bmp = withContext(Dispatchers.IO) {
            Graph.repo.loadAttachment(id)?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
        }
        tried = true
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
        val h = minOf(maxWidth * 0.75f, 320.dp)
        val b = bmp
        if (b != null) {
            Image(b, contentDescription = "Image", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(h))
        } else {
            Box(Modifier.fillMaxWidth().height(h).border(1.dp, Ts.Line).background(Color(0xFF0B0B0E)), contentAlignment = Alignment.Center) {
                Text(if (tried) "IMAGE SYNCING…" else "", color = Ts.Dim, fontSize = 10.sp, letterSpacing = 2.sp, fontFamily = Ts.Mono)
            }
        }
    }
}

/** Short plain-text preview for grid tiles. */
fun previewText(body: String, maxLines: Int = 4): String =
    body.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != PAGE_BREAK && !it.startsWith("```") }
        .map {
            it.replace(Regex("^#{1,3}\\s+"), "").replace(Regex("^[-*+]\\s+\\[[xX]]\\s?"), "[x] ")
                .replace(Regex("^[-*+]\\s+\\[ ]\\s?"), "[ ] ").replace(Regex("^[-*+]\\s+"), "· ")
                .replace(Regex("!\\[[^\\]]*]\\(att:[A-Za-z0-9]+\\)"), "▨ image").replace("**", "").replace("~~", "").replace("`", "")
                .removePrefix("> ")
        }
        .take(maxLines).joinToString("\n")

fun firstImage(body: String): String? = com.stellar.twinstar.data.Repo.attachmentRefs(body).firstOrNull()
