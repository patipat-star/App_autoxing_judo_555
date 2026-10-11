package com.example.pcoverlaycontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return

        // ยังไม่ได้สิทธิ์ Overlay ก็ยังเปิดปุ่มลอยไม่ได้ ให้ข้ามไป
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "ข้ามการเริ่มทำงาน: ยังไม่ได้รับสิทธิ์ Display over other apps")
            return
        }

        // FloatingService เช็กสิทธิ์ Overlay เองอีกครั้ง และทำงานได้แม้ยังไม่ได้ขอสิทธิ์เสริมอื่น
        try {
            ContextCompat.startForegroundService(context, Intent(context, FloatingService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "ไม่สามารถเปิด FloatingService ได้: ${e.message}")
        }
    }
}
