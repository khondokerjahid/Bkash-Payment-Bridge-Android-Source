package com.jahid.bkashverify

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Required component for Android SMS-role qualification. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
