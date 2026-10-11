package com.lanvault

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Placeholder status screen for the Android TV build. The real screen reports live server state
 * and is introduced with the storage server itself.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this)
        status.text = "LANVault"
        status.textSize = 24f
        setContentView(status)
    }
}
