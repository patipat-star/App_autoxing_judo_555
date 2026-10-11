package com.example.pcoverlaycontrol

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {

    private companion object {
        const val TAG = "MainActivity"
    }

    private var isPermissionRequesting = false

    // สิทธิ์เสริมจะถูกขอแค่ครั้งเดียวต่อการเปิดแอป ถ้าผู้ใช้ปฏิเสธก็ไม่วนขอซ้ำ
    private var askedUsageStats = false
    private var askedNotification = false
    private var askedBattery = false

    // 📌 1. ตัวรับผลลัพธ์การขอสิทธิ์ Overlay (Display over other apps)
    // เป็นสิทธิ์เดียวที่จำเป็นจริงๆ ถ้าไม่ให้ก็เปิดปุ่มลอยไม่ได้
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isPermissionRequesting = false
        if (Settings.canDrawOverlays(this)) {
            checkNextPermission()
        } else {
            Toast.makeText(this, getString(R.string.toast_overlay_required), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // 📌 2. ตัวรับผลลัพธ์การขอสิทธิ์ Usage Access (เข้าถึงข้อมูลการใช้งานเพื่อตรวจจับ Zoom)
    private val usageStatsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isPermissionRequesting = false
        checkNextPermission()
    }

    // 📌 3. ตัวรับผลลัพธ์การขอสิทธิ์แจ้งเตือน
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        // Foreground Service ทำงานได้แม้ไม่ได้สิทธิ์แจ้งเตือน (แค่ไม่เห็น notification)
        isPermissionRequesting = false
        checkNextPermission()
    }

    // 📌 4. ตัวรับผลลัพธ์การขอ "ยกเว้น" Battery Optimization
    private val batteryOptimizationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isPermissionRequesting = false
        checkNextPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Activity นี้ไม่มี UI: ทำหน้าที่ขอสิทธิ์ทีละขั้นแล้วเปิด FloatingService
        checkNextPermission()
    }

    // 📌 รองรับการเปิดแอปซ้ำเมื่อ Activity เปิดค้างใน Background
    // (ถ้ากำลังขอสิทธิ์อยู่ checkNextPermission จะไม่ขอซ้ำ)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkNextPermission()
    }

    // 📌 ระบบลำดับการเช็กสิทธิ์ทีละขั้นตอน (Step-by-Step Permission Check)
    private fun checkNextPermission() {
        if (isPermissionRequesting) return

        // Step 1: เช็กสิทธิ์ Overlay
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        // Step 2-4: สิทธิ์เสริม ขอครั้งเดียวต่อการเปิดแอป
        if (!askedUsageStats && !hasUsageStatsPermission()) {
            askedUsageStats = true
            requestUsageStatsPermission()
            return
        }

        if (!askedNotification && !hasNotificationPermission()) {
            askedNotification = true
            requestNotificationPermission()
            return
        }

        if (!askedBattery && !hasIgnoreBatteryOptimization()) {
            askedBattery = true
            requestIgnoreBatteryOptimization()
            return
        }

        // ผ่านขั้นตอนขอสิทธิ์แล้ว ให้เปิด Service
        startFloatingService()
    }

    private fun requestOverlayPermission() {
        isPermissionRequesting = true
        Toast.makeText(this, getString(R.string.toast_ask_overlay), Toast.LENGTH_SHORT).show()
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri()
        )
        try {
            overlayPermissionLauncher.launch(intent)
        } catch (e: Exception) {
            // ROM บางรุ่น (เช่นของหุ่นยนต์) ไม่มีหน้าตั้งค่านี้: ไม่ให้แอปล้ม
            Log.w(TAG, e)
            isPermissionRequesting = false
            Toast.makeText(this, getString(R.string.toast_overlay_screen_missing), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun requestUsageStatsPermission() {
        isPermissionRequesting = true
        Toast.makeText(this, getString(R.string.toast_ask_usage), Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData("package:$packageName".toUri())
        launchOptional(usageStatsPermissionLauncher, intent, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
    }

    @Suppress("DEPRECATION")
    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        } else {
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // Android 13+ ต้องขอสิทธิ์แจ้งเตือนสำหรับ Foreground Service
    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestNotificationPermission() {
        isPermissionRequesting = true
        try {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: Exception) {
            Log.w(TAG, e)
            isPermissionRequesting = false
            checkNextPermission()
        }
    }

    // สิทธิ์เสริม: ถ้าเปิดหน้าตั้งค่าไม่ได้ (ลอง intent สำรองแล้ว) ให้ข้ามไป ไม่ให้แอปล้ม
    private fun launchOptional(
        launcher: ActivityResultLauncher<Intent>,
        primary: Intent,
        fallback: Intent
    ) {
        for (intent in listOf(primary, fallback)) {
            try {
                launcher.launch(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, e)
            }
        }
        isPermissionRequesting = false
        checkNextPermission()
    }

    private fun hasIgnoreBatteryOptimization(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        isPermissionRequesting = true
        Toast.makeText(this, getString(R.string.toast_ask_battery), Toast.LENGTH_LONG).show()
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:$packageName".toUri()
        )
        launchOptional(batteryOptimizationLauncher, intent, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun startFloatingService() {
        val serviceIntent = Intent(this, FloatingService::class.java)
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            Toast.makeText(this, getString(R.string.toast_overlay_started), Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Log.e(TAG, "เริ่ม FloatingService ไม่สำเร็จ", e)
            Toast.makeText(this, getString(R.string.toast_service_start_failed, e.localizedMessage), Toast.LENGTH_LONG).show()
        }
    }
}