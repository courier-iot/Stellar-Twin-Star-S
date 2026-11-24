package com.stellar.twinstar

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.room.Room
import com.stellar.twinstar.data.AppDatabase
import com.stellar.twinstar.data.Prefs
import com.stellar.twinstar.data.Repo
import com.stellar.twinstar.security.AppLock
import com.stellar.twinstar.service.SyncService
import com.stellar.twinstar.sync.SyncEngine
import com.stellar.twinstar.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object Graph {
    lateinit var app: Application
    lateinit var prefs: Prefs
    lateinit var repo: Repo
    lateinit var sync: SyncEngine
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var foreground = false

    fun init(app: Application) {
        this.app = app
        prefs = Prefs(app)
        val db = Room.databaseBuilder(app, AppDatabase::class.java, "twinstar.db").build()
        repo = Repo(app, db.dao(), prefs)
        sync = SyncEngine(app, repo, prefs)
    }
}

class TwinStarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        createChannels()
        AppLock.init(Graph.prefs)

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                Graph.foreground = true
                AppLock.onForeground(Graph.prefs)
                Graph.sync.acquire("ui")
            }

            override fun onStop(owner: LifecycleOwner) {
                Graph.foreground = false
                AppLock.onBackground()
                Graph.sync.release("ui")
            }
        })

        Graph.scope.launch {
            if (System.currentTimeMillis() - Graph.prefs.lastTrashSweep > 6 * 3600 * 1000L) Graph.repo.sweepTrash()
        }
        Graph.scope.launch {
            Graph.repo.notes.collectLatest { delay(500); WidgetUpdater.push(this@TwinStarApp) }
        }
        SyncService.update(this)
    }

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_SYNC, "Live sync", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Sync prompts", NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        const val CH_SYNC = "sync"
        const val CH_ALERT = "alerts"
    }
}
