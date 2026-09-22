package com.example.pcoverlaycontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("BootReceiver", "ดักจับสัญญาณ Boot Action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        ) {
            // 📌 1. ป้องกัน App Crash: ถ้ายังไม่ได้สิทธิ์ Overlay ห้ามทำงาน
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Log.w("BootReceiver", "ข้ามการเริ่มทำงานเนื่องจากยังไม่ได้รับสิทธิ์ Display over other apps")
                return
            }

            // 📌 2. สั่งเปิดหน้าแอปหลัก (MainActivity) ให้เด้งขึ้นมาทันทีที่เปิดเครื่อง
            try {
                val activityIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(activityIntent)
                Log.d("BootReceiver", "สั่งเปิดหน้า MainActivity ตอนบูตเครื่องสำเร็จ")
            } catch (e: Exception) {
                Log.e("BootReceiver", "ไม่สามารถเปิดหน้า MainActivity ได้: ${e.message}")
            }

            // 📌 3. สั่งรัน FloatingService ควบคู่ไปด้วย
            try {
                val serviceIntent = Intent(context, FloatingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d("BootReceiver", "สั่งเริ่ม FloatingService ตอนบูตเครื่องสำเร็จ")
            } catch (e: Exception) {
                Log.e("BootReceiver", "ไม่สามารถเปิด FloatingService ตอนบูตเครื่องได้: ${e.message}")
            }
        }
    }
}