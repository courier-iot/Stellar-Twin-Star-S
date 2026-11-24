package com.stellar.twinstar.service

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.stellar.twinstar.Graph
import com.stellar.twinstar.R
import com.stellar.twinstar.TwinStarApp
import com.stellar.twinstar.ui.MainActivity

/** Keeps live sync running while the app is closed (only when the user turns it on). */
class SyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, TwinStarApp.CH_SYNC)
            .setSmallIcon(R.drawable.ic_stat_star)
            .setContentTitle("Twin Star S")
            .setContentText("Live sync with linked devices")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, 7, n, type)
        Graph.sync.acquire("service")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Graph.prefs.backgroundSync.value) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        Graph.sync.release("service")
        super.onDestroy()
    }

    companion object {
        fun update(ctx: Context) {
            val i = Intent(ctx, SyncService::class.java)
            if (Graph.prefs.backgroundSync.value) {
                try { ContextCompat.startForegroundService(ctx, i) } catch (_: Exception) { }
            } else ctx.stopService(i)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) SyncService.update(context)
    }
}
