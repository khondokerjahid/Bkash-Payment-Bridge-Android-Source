package com.jahid.bkashverify

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Required component for Android SMS-role qualification.
 * This bridge is not intended to replace the user's normal messaging UI.
 */
class SmsSendActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Toast.makeText(
            this,
            "bKash Bridge uses the SMS role only for full phone-history scanning. Restore your normal SMS app after scanning.",
            Toast.LENGTH_LONG
        ).show()

        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (_: Exception) {
        }

        finish()
    }
}
