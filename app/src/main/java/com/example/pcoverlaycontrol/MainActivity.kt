package com.example.pcoverlaycontrol

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var isPermissionRequesting = false

    // 📌 1. ตัวรับผลลัพธ์การขอสิทธิ์ Overlay (Display over other apps)
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isPermissionRequesting = false
        checkNextPermission()
    }

    // 📌 2. ตัวรับผลลัพธ์การขอสิทธิ์ Usage Access (เข้าถึงข้อมูลการใช้งานเพื่อตรวจจับ Zoom)
    private val usageStatsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isPermissionRequesting = false
        checkNextPermission()
    }

    // 📌 3. ตัวรับผลลัพธ์การขอสิทธิ์ Runtime (กล้อง, ไมค์, แจ้งเตือน)
    private val requestRuntimePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsMap ->
        isPermissionRequesting = false
        val allGranted = permissionsMap.values.all { it }
        if (allGranted) {
            checkNextPermission()
        } else {
            Toast.makeText(
                this,
                "กรุณายินยอมสิทธิ์ทั้งหมดเพื่อเปิดใช้งานปุ่มลอย",
                Toast.LENGTH_LONG
            ).show()
        }
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
        setContentView(R.layout.activity_main)

        // 📌 เริ่มต้นเช็กสิทธิ์ทันทีเมื่อเปิด Activity
        checkNextPermission()
    }

    // 📌 [สำคัญมาก] รองรับการเปิดแอปซ้ำเมื่อ Activity เปิดค้างใน Background
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        isPermissionRequesting = false
        checkNextPermission()
    }

    override fun onResume() {
        super.onResume()
        // 📌 เมื่อกลับมาจากหน้าตั้งค่า ให้ปลดล็อก Flag และตรวจสอบสิทธิ์ขั้นต่อไปทันที
        isPermissionRequesting = false
        checkNextPermission()
    }

    // 📌 ระบบลำดับการเช็กสิทธิ์ทีละขั้นตอน (Step-by-Step Permission Check)
    private fun checkNextPermission() {
        if (isPermissionRequesting) return

        // Step 1: เช็กสิทธิ์ Overlay
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        // Step 2: เช็กสิทธิ์ Usage Stats
        if (!hasUsageStatsPermission()) {
            requestUsageStatsPermission()
            return
        }

        // Step 3: เช็กสิทธิ์ Runtime (Camera, Audio, Notification)
        if (!hasAllRuntimePermissions()) {
            checkAndRequestRuntimePermissions()
            return
        }

        // Step 4: เช็กสิทธิ์ยกเว้น Battery Optimization
        if (!hasIgnoreBatteryOptimization()) {
            requestIgnoreBatteryOptimization()
            return
        }

        // สิทธิ์ครบทุกอย่างแล้ว ให้เปิด Service
        startFloatingService()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            isPermissionRequesting = true
            Toast.makeText(this, "กรุณาเปิดสิทธิ์ 'แสดงทับแอปอื่น'", Toast.LENGTH_SHORT).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun requestUsageStatsPermission() {
        isPermissionRequesting = true
        Toast.makeText(this, "กรุณาค้นหาแอปแล้วเปิดสิทธิ์ 'การเข้าถึงข้อมูลการใช้งาน'", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                data = Uri.parse("package:$packageName")
            }
        }
        try {
            usageStatsPermissionLauncher.launch(intent)
        } catch (e: Exception) {
            val fallbackIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            usageStatsPermissionLauncher.launch(fallbackIntent)
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasAllRuntimePermissions(): Boolean {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissionsToRequest.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkAndRequestRuntimePermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val listPermissionsNeeded = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (listPermissionsNeeded.isNotEmpty()) {
            isPermissionRequesting = true
            requestRuntimePermissionsLauncher.launch(listPermissionsNeeded.toTypedArray())
        } else {
            checkNextPermission()
        }
    }

    private fun hasIgnoreBatteryOptimization(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            return powerManager.isIgnoringBatteryOptimizations(packageName)
        }
        return true
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            isPermissionRequesting = true
            Toast.makeText(
                this,
                "กรุณากด 'อนุญาต' เพื่อไม่ให้ระบบปิดปุ่มลอยเอง",
                Toast.LENGTH_LONG
            ).show()
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            try {
                batteryOptimizationLauncher.launch(intent)
            } catch (e: Exception) {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                batteryOptimizationLauncher.launch(fallbackIntent)
            }
        }
    }

    private fun startFloatingService() {
        val serviceIntent = Intent(this, FloatingService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "เปิดใช้งานปุ่มลอยเรียบร้อย!", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "ไม่สามารถเริ่มบริการปุ่มลอยได้: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }
}