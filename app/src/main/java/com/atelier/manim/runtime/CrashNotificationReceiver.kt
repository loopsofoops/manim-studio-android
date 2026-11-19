package com.atelier.manim.runtime

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import java.io.File

class CrashNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_COPY_ERROR) return
        val path = intent.getStringExtra(EXTRA_REPORT_PATH).orEmpty()
        val fallback = intent.getStringExtra(EXTRA_FALLBACK_TEXT).orEmpty()
        val text = runCatching {
            val f = File(path)
            if (path.isNotBlank() && f.exists()) {
                val raw = f.readText(Charsets.UTF_8)
                if (raw.startsWith("META|")) raw.substringAfter("\n", raw) else raw
            } else fallback
        }.getOrDefault(fallback)
        if (text.isNotBlank()) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("Atelier Error Report", text))
            Toast.makeText(context, "Atelier error log copied", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_COPY_ERROR = "com.atelier.manim.action.COPY_CRASH_ERROR"
        const val EXTRA_REPORT_PATH = "report_path"
        const val EXTRA_FALLBACK_TEXT = "fallback_text"
    }
}
