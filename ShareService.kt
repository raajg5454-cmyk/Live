package com.livehelp

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Foreground service required by Android for screen capture. Persistent notification with a Stop action. */
class ShareService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == "STOP") { Session.end(); stopSelf(); return START_NOT_STICKY }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("share", "Screen sharing", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, ShareService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 1, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "share")
            .setContentTitle("LiveHelp").setContentText("Your screen is being shared. Tap Stop to end.")
            .setSmallIcon(android.R.drawable.ic_menu_view).setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(0, "Stop", stop).build()).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        Session.onServiceReady()
        return START_NOT_STICKY
    }
}
