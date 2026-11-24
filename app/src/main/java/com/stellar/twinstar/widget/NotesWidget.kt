package com.stellar.twinstar.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.stellar.twinstar.Graph
import com.stellar.twinstar.R
import com.stellar.twinstar.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class NotesWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetUpdater.push(context)
    }
}

object WidgetUpdater {
    const val ACTION_NEW = "com.stellar.twinstar.NEW_NOTE"
    const val EXTRA_NOTE = "note_id"

    fun push(context: Context) {
        val ctx = context.applicationContext
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, NotesWidget::class.java))
        if (ids.isEmpty()) return
        Graph.scope.launch {
            val notes = Graph.repo.notes.first()
                .filter { !it.deleted }
                .sortedWith(compareByDescending<com.stellar.twinstar.data.Note> { it.pinned }.thenByDescending { it.updatedAt })
                .take(4)
            val hide = Graph.prefs.lockEnabled.value
            val accent = Graph.prefs.accent.value
            val views = RemoteViews(ctx.packageName, R.layout.widget_notes)
            views.setTextColor(R.id.w_new, accent)
            val open = PendingIntent.getActivity(ctx, 100, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.w_root, open)
            views.setOnClickPendingIntent(R.id.w_title, open)
            val newIntent = Intent(ctx, MainActivity::class.java).setAction(ACTION_NEW)
            views.setOnClickPendingIntent(R.id.w_new, PendingIntent.getActivity(ctx, 101, newIntent, PendingIntent.FLAG_IMMUTABLE))
            val rows = listOf(R.id.w_n1, R.id.w_n2, R.id.w_n3, R.id.w_n4)
            rows.forEachIndexed { i, row ->
                val n = notes.getOrNull(i)
                if (n == null || (hide && i > 0)) {
                    views.setViewVisibility(row, View.GONE)
                } else {
                    views.setViewVisibility(row, View.VISIBLE)
                    val label = when {
                        hide -> "Locked · tap to open"
                        n.locked -> "▣ locked note"
                        else -> (if (n.pinned) "▲ " else "· ") + n.title.ifBlank { n.body.lineSequence().firstOrNull { it.isNotBlank() } ?: "Untitled" }
                    }
                    views.setTextViewText(row, label.take(60))
                    val intent = Intent(ctx, MainActivity::class.java).putExtra(EXTRA_NOTE, n.id).setAction("open_$i")
                    views.setOnClickPendingIntent(row, PendingIntent.getActivity(ctx, 110 + i, if (hide) Intent(ctx, MainActivity::class.java) else intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                }
            }
            mgr.updateAppWidget(ids, views)
        }
    }
}
