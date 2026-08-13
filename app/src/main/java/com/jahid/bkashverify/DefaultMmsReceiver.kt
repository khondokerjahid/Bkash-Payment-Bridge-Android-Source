package com.jahid.bkashverify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Declared so the app qualifies for Android's SMS role.
 * bKash verification never processes MMS/WAP payloads.
 */
class DefaultMmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Intentionally ignored. Full History Access should only be kept active
        // briefly for scanning, then the user's normal SMS app should be restored.
    }
}
