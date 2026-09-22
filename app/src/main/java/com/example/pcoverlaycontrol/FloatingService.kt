package com.example.pcoverlaycontrol

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.abs

class FloatingService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var zoomCloseOverlayView: View? = null

    // 📌 URL สำหรับเครื่องดนตรี และ เว็บเกม 3D ในโฟลเดอร์ assets
    private val pianoWebUrl = "file:///android_asset/piano.html"
    private val drumWebUrl = "file:///android_asset/drum.html"
    private val gameWebUrl = "file:///android_asset/index.html"

    // 📌 IP Address และ Port ของ Raspberry Pi 5 / PC ประมวลผล AI
    private val pcIpAddress = "192.168.100.245" // IP ของ Raspberry Pi 5
    private val pcPort = 5000
    private val wsPort = 8765

    // 📌 IP Address ของ Web Dashboard AI จาก Raspberry Pi 5
    private val piDashboardUrl = "http://$pcIpAddress:$pcPort"

    // 📌 IP Address ของ ESP32
    private val esp32IpAddress = "192.168.100.20"
    private var isEsp32LedOn = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // 📌 WebSocket & HTTP Client
    private val okHttpClient = OkHttpClient()
    private var webSocket: WebSocket? = null
    private var tvFingerCount: TextView? = null

    // 📌 ตัวแปรสำหรับระบบตรวจจับการเปิดแอป Zoom ค้างไว้
    private val zoomPackageName = "us.zoom.videomeetings"
    private val zoomCheckHandler = Handler(Looper.getMainLooper())
    private var zoomCheckRunnable: Runnable? = null
    private var isZoomMonitoring = false

    // 📌 จำชื่อแอป/หน้าจอที่เปิดอยู่ "ก่อน" กดปุ่ม Zoom
    private var previousForegroundPackage: String? = null

    // 📌 จำว่าตอนนี้กำลังแสดงสตรีมประเภทไหนอยู่ (ใช้ตอนย่อ/ปิดหน้าจอ เพื่อรู้ว่าต้องส่งสัญญาณปิดกล้องที่ Pi 5 หรือไม่)
    private var activeStreamType: String? = null

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, "overlay_channel")
            .setContentTitle("PC Overlay Control")
            .setContentText("ปุ่มลอยกำลังทำงาน...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        mainHandler.post {
            initFloatingView()
        }

        connectWebSocket("ws://$pcIpAddress:$wsPort")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mainHandler.post {
            if (floatingView == null) {
                initFloatingView()
            } else {
                floatingView?.visibility = View.VISIBLE
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "overlay_channel",
                "Overlay Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    @Synchronized
    private fun initFloatingView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            showToast("กรุณาเปิดสิทธิ์ Display over other apps")
            return
        }

        if (floatingView != null) {
            floatingView?.visibility = View.VISIBLE
            return
        }

        try {
            val view = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
            floatingView = view

            val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutParamsType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 100
            }

            // แสดงผล Window
            windowManager.addView(view, params)

            val btnMain = view.findViewById<ImageButton>(R.id.btnMain)
            val controlButtonsGroup = view.findViewById<View>(R.id.controlButtonsGroup)
            val mainMenuContainer = view.findViewById<View>(R.id.mainMenuContainer)
            val btn1 = view.findViewById<ImageButton>(R.id.btn1)
            val btn2 = view.findViewById<ImageButton>(R.id.btn2)
            val btn3 = view.findViewById<ImageButton>(R.id.btn3)
            val btn4 = view.findViewById<Button>(R.id.btn4)
            val btn5 = view.findViewById<ImageButton>(R.id.btn5)
            val btnCloseStream = view.findViewById<Button>(R.id.btnCloseStream)
            val streamWebView = view.findViewById<WebView>(R.id.streamWebView)
            val streamContainer = view.findViewById<View?>(R.id.streamContainer)

            tvFingerCount = view.findViewById(R.id.tvFingerCount)

            // บังคับแสดงผล Container หลักไว้เสมอ
            mainMenuContainer?.visibility = View.VISIBLE

            // ตั้งค่าระบบลากและคลิกปุ่มหลัก
            setupDraggableWindow(view, btnMain, params) {
                controlButtonsGroup.visibility =
                    if (controlButtonsGroup.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }

            fun minimizeToFloatingButton() {
                // 📌 ถ้ากำลังดูสตรีมกล้อง (ปุ่ม 4) อยู่ ให้สั่งปิดกล้องที่ Pi 5 ก่อนย่อหน้าจอ
                if (activeStreamType == "camera") {
                    val stopUrl = "http://$pcIpAddress:$pcPort/api/stop_button4"
                    val stopRequest = Request.Builder()
                        .url(stopUrl)
                        .post(RequestBody.create(null, ByteArray(0)))
                        .build()
                    okHttpClient.newCall(stopRequest).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            showToast("ส่งคำสั่งปิดกล้องไม่สำเร็จ: ${e.message}")
                        }

                        override fun onResponse(call: Call, response: Response) {
                            showToast("ปิดกล้องที่ Pi 5 เรียบร้อย")
                        }
                    })
                }
                activeStreamType = null

                try {
                    streamWebView?.apply {
                        onPause()
                        stopLoading()
                        loadUrl("about:blank")
                        clearHistory()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                params.width = WindowManager.LayoutParams.WRAP_CONTENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                windowManager.updateViewLayout(view, params)

                streamContainer?.visibility = View.GONE
                streamWebView?.visibility = View.GONE
                btnCloseStream?.visibility = View.GONE
                mainMenuContainer?.visibility = View.VISIBLE

                showToast("ย่อหน้าจอกลับสู่ปุ่มลอย")
            }

            // Piano
            btn1?.setOnClickListener {
                val isPianoShowing = (streamWebView?.visibility == View.VISIBLE) ||
                        (streamContainer?.visibility == View.VISIBLE)

                if (isPianoShowing) {
                    minimizeToFloatingButton()
                } else {
                    params.width = WindowManager.LayoutParams.MATCH_PARENT
                    params.height = WindowManager.LayoutParams.MATCH_PARENT
                    params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

                    windowManager.updateViewLayout(view, params)

                    mainMenuContainer?.visibility = View.GONE
                    streamContainer?.visibility = View.VISIBLE
                    streamWebView?.visibility = View.VISIBLE
                    btnCloseStream?.apply {
                        visibility = View.VISIBLE
                        bringToFront()
                        elevation = 100f
                    }

                    streamWebView?.settings?.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mediaPlaybackRequiresUserGesture = false
                        cacheMode = WebSettings.LOAD_NO_CACHE
                    }

                    streamWebView?.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            return false
                        }
                    }

                    streamWebView?.onResume()
                    streamWebView?.loadUrl(pianoWebUrl)
                    showToast("กำลังเปิดเปียโนจำลอง...")
                }
            }

            // Game 3D
            btn2?.setOnClickListener {
                val isGameShowing = (streamWebView?.visibility == View.VISIBLE) ||
                        (streamContainer?.visibility == View.VISIBLE)

                if (isGameShowing) {
                    minimizeToFloatingButton()
                } else {
                    params.width = WindowManager.LayoutParams.MATCH_PARENT
                    params.height = WindowManager.LayoutParams.MATCH_PARENT
                    params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

                    windowManager.updateViewLayout(view, params)

                    mainMenuContainer?.visibility = View.GONE
                    streamContainer?.visibility = View.VISIBLE
                    streamWebView?.visibility = View.VISIBLE
                    btnCloseStream?.apply {
                        visibility = View.VISIBLE
                        bringToFront()
                        elevation = 100f
                    }

                    streamWebView?.settings?.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mediaPlaybackRequiresUserGesture = false
                        cacheMode = WebSettings.LOAD_NO_CACHE
                    }

                    streamWebView?.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            return false
                        }
                    }

                    streamWebView?.onResume()
                    streamWebView?.loadUrl(gameWebUrl)
                    showToast("กำลังเปิดเกม 3D...")
                }
            }

            // Zoom
            btn3?.setOnClickListener {
                try {
                    // Zoom เป็นแอปแยกจากแอปนี้ จึงให้ AccessibilityService เป็นตัวช่วย
                    // เปิดกล้อง/ไมค์ในหน้าประชุมเมื่อมีปุ่ม "เปิด" ให้กด
                    ZoomAccessibilityService.autoEnableMediaRequested.set(true)
                    ZoomAccessibilityService.forceLeaveRequested.set(false)

                    previousForegroundPackage = getForegroundPackageName()

                    val launchIntent = packageManager.getLaunchIntentForPackage(zoomPackageName)
                    if (launchIntent != null) {
                        launchIntent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                        )
                        startActivity(launchIntent)
                        showToast("กำลังเปิดหน้าหลัก Zoom...")

                        showCloseZoomButton()
                        startZoomMonitoring()
                    } else {
                        showToast("ไม่พบแอป Zoom ในเครื่อง")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    showToast("ไม่สามารถเปิด Zoom ได้")
                }
            }

            // 📌 [ปุ่มที่ 4] สตรีมภาพวิดีโอจาก Pi 5 บังคับดึงยืดเต็มจอ 100% พอดี ไม่เบี้ยว
            btn4?.setOnClickListener {
                val isCameraShowing = (streamWebView?.visibility == View.VISIBLE) ||
                        (streamContainer?.visibility == View.VISIBLE)

                if (isCameraShowing) {
                    minimizeToFloatingButton()
                } else {
                    params.width = WindowManager.LayoutParams.MATCH_PARENT
                    params.height = WindowManager.LayoutParams.MATCH_PARENT
                    params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

                    windowManager.updateViewLayout(view, params)

                    mainMenuContainer?.visibility = View.GONE
                    streamContainer?.visibility = View.VISIBLE
                    streamWebView?.visibility = View.VISIBLE

                    // นำปุ่มปิดขึ้นเลเยอร์บนสุด ป้องกันวิดีโอบัง
                    btnCloseStream?.apply {
                        visibility = View.VISIBLE
                        bringToFront()
                        elevation = 100f
                    }

                    streamWebView?.settings?.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mediaPlaybackRequiresUserGesture = false
                        cacheMode = WebSettings.LOAD_NO_CACHE
                    }

                    streamWebView?.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            return false
                        }
                    }

                    // 📌 [แก้ปัญหาภาพไม่เต็มจอ/ขึ้นเป็นไอคอนแตก]
                    // ห้าม loadUrl ไปที่สตรีม MJPEG ตรงๆ เพราะ WebView ไม่รองรับการเปิด
                    // multipart/x-mixed-replace เป็นหน้าเว็บหลัก (top-level navigation) โดยตรง
                    // ต้องห่อด้วย <img> ในหน้า HTML ของเราเองแล้วสั่ง render ผ่าน loadDataWithBaseURL แทน
                    val videoFeedUrl = "http://$pcIpAddress:$pcPort/video_feed"
                    val streamHtml = """
                        <!DOCTYPE html>
                        <html>
                        <head>
                            <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
                            <style>
                                html, body {
                                    margin: 0; padding: 0;
                                    width: 100%; height: 100%;
                                    background-color: #000;
                                    overflow: hidden;
                                }
                                img {
                                    width: 100vw;
                                    height: 100vh;
                                    object-fit: cover; /* เต็มจอโดยไม่เบี้ยว ถ้าต้องการยืดเต็มแบบไม่รักษาสัดส่วนให้เปลี่ยนเป็น fill */
                                    display: block;
                                }
                            </style>
                        </head>
                        <body>
                            <img src="$videoFeedUrl">
                        </body>
                        </html>
                    """.trimIndent()

                    activeStreamType = "camera"
                    streamWebView?.onResume()
                    streamWebView?.loadDataWithBaseURL(
                        "http://$pcIpAddress:$pcPort/",
                        streamHtml,
                        "text/html",
                        "UTF-8",
                        null
                    )

                    // ส่ง HTTP Request ไปที่ Raspberry Pi 5 สลับโหมดติดตามคน (เปิดกล้อง)
                    val triggerUrl = "http://$pcIpAddress:$pcPort/api/trigger_button4"
                    val request = Request.Builder()
                        .url(triggerUrl)
                        .post(RequestBody.create(null, ByteArray(0)))
                        .build()

                    okHttpClient.newCall(request).enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            showToast("เชื่อมต่อ Pi 5 ไม่สำเร็จ: ${e.message}")
                        }

                        override fun onResponse(call: Call, response: Response) {
                            showToast("เปิดโหมดติดตามคน (PERSON) บน Pi 5 เรียบร้อย")
                        }
                    })

                    showToast("กำลังเปิดสตรีมภาพแบบเต็มจอ...")
                }
            }

            // Drum
            btn5?.setOnClickListener {
                val isDrumShowing = (streamWebView?.visibility == View.VISIBLE) ||
                        (streamContainer?.visibility == View.VISIBLE)

                if (isDrumShowing) {
                    minimizeToFloatingButton()
                } else {
                    params.width = WindowManager.LayoutParams.MATCH_PARENT
                    params.height = WindowManager.LayoutParams.MATCH_PARENT
                    params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

                    windowManager.updateViewLayout(view, params)

                    mainMenuContainer?.visibility = View.GONE
                    streamContainer?.visibility = View.VISIBLE
                    streamWebView?.visibility = View.VISIBLE
                    btnCloseStream?.apply {
                        visibility = View.VISIBLE
                        bringToFront()
                        elevation = 100f
                    }

                    streamWebView?.settings?.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        mediaPlaybackRequiresUserGesture = false
                        cacheMode = WebSettings.LOAD_NO_CACHE
                    }

                    streamWebView?.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            return false
                        }
                    }

                    streamWebView?.onResume()
                    streamWebView?.loadUrl(drumWebUrl)
                    showToast("กำลังเปิดกลองชุดจำลอง...")
                }
            }

            btnCloseStream?.setOnClickListener {
                minimizeToFloatingButton()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            showToast("สร้างปุ่มลอยล้มเหลว: ${e.message}")
        }
    }

    private fun toggleEsp32Led() {
        isEsp32LedOn = !isEsp32LedOn
        val endpoint = if (isEsp32LedOn) "on" else "off"
        val statusText = if (isEsp32LedOn) "เปิดไฟ (HIGH)" else "ปิดไฟ (LOW)"
        val url = "http://$esp32IpAddress/led/$endpoint"

        showToast("กำลังสั่ง $statusText ไปที่ ESP32...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder().url(url).build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        showToast("ESP32: $statusText สำเร็จ ✅")
                    } else {
                        isEsp32LedOn = !isEsp32LedOn
                        showToast("ESP32 ตอบกลับผิดพลาด ❌: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                isEsp32LedOn = !isEsp32LedOn
                showToast("เชื่อมต่อ ESP32 ล้มเหลว ❌")
            }
        }
    }

    private fun connectWebSocket(serverUrl: String) {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val count = json.optInt("count", 0)

                    mainHandler.post {
                        tvFingerCount?.text = count.toString()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                mainHandler.postDelayed({ connectWebSocket(serverUrl) }, 3000)
            }
        })
    }

    private fun getForegroundPackageName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager?
            val time = System.currentTimeMillis()
            val stats = usageStatsManager?.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                time - 1000 * 10,
                time
            )
            if (!stats.isNullOrEmpty()) {
                var currentApp: String? = null
                var lastTimeUsed = 0L
                for (usageStats in stats) {
                    if (usageStats.lastTimeUsed > lastTimeUsed) {
                        lastTimeUsed = usageStats.lastTimeUsed
                        currentApp = usageStats.packageName
                    }
                }
                if (currentApp != null) return currentApp
            }
        }

        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        val tasks = activityManager.getRunningTasks(1)
        return if (tasks.isNotEmpty()) tasks[0].topActivity?.packageName else null
    }

    private fun startZoomMonitoring() {
        stopZoomMonitoring()
        isZoomMonitoring = true

        zoomCheckRunnable = object : Runnable {
            override fun run() {
                if (!isZoomMonitoring) return

                if (!isAppInForeground(zoomPackageName)) {
                    stopZoomMonitoring()
                    removeCloseZoomButton()
                } else {
                    zoomCheckHandler.postDelayed(this, 1000)
                }
            }
        }
        zoomCheckHandler.postDelayed(zoomCheckRunnable!!, 3000)
    }

    private fun stopZoomMonitoring() {
        isZoomMonitoring = false
        zoomCheckRunnable?.let { zoomCheckHandler.removeCallbacks(it) }
    }

    private fun isAppInForeground(packageName: String): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager?
            val time = System.currentTimeMillis()
            val stats = usageStatsManager?.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                time - 1000 * 10,
                time
            )
            if (!stats.isNullOrEmpty()) {
                var currentApp = ""
                var lastTimeUsed = 0L
                for (usageStats in stats) {
                    if (usageStats.lastTimeUsed > lastTimeUsed) {
                        lastTimeUsed = usageStats.lastTimeUsed
                        currentApp = usageStats.packageName
                    }
                }
                return currentApp == packageName
            }
        }

        @Suppress("DEPRECATION")
        val tasks = activityManager.getRunningTasks(1)
        if (tasks.isNotEmpty()) {
            val topActivity = tasks[0].topActivity
            return topActivity?.packageName == packageName
        }
        return false
    }

    private fun showCloseZoomButton() {
        if (zoomCloseOverlayView != null) return

        val closeButton = Button(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 20f
            setBackgroundResource(R.drawable.circle_button_bg)
        }

        val overlayParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val closeParams = WindowManager.LayoutParams(
            150,
            150,
            overlayParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 50
            y = 150
        }

        closeButton.setOnClickListener {
            stopZoomMonitoring()
            removeCloseZoomButton()

            // ขอให้ AccessibilityService กด Leave/End ใน Zoom ก่อน
            // เพื่อให้ Zoom ปิดกล้องและไมค์ของตัวเองอย่างถูกต้อง
            ZoomAccessibilityService.autoEnableMediaRequested.set(false)
            ZoomAccessibilityService.forceLeaveRequested.set(true)

            // ถ้า AccessibilityService เปิดใช้งานอยู่ จะเป็นผู้พากลับหน้าก่อนหน้า
            // หลังยืนยัน Leave/End แล้ว ส่วนนี้เป็น fallback กรณีไม่มี service
            mainHandler.postDelayed({
                if (ZoomAccessibilityService.forceLeaveRequested.get()) {
                    ZoomAccessibilityService.forceLeaveRequested.set(false)

                    val targetPackage = previousForegroundPackage
                    var launchedBack = false

                    if (!targetPackage.isNullOrEmpty() &&
                        targetPackage != zoomPackageName &&
                        targetPackage != packageName
                    ) {
                        val backIntent = packageManager.getLaunchIntentForPackage(targetPackage)
                        if (backIntent != null) {
                            backIntent.addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            )
                            startActivity(backIntent)
                            launchedBack = true
                        }
                    }

                    if (!launchedBack) {
                        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_HOME)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(homeIntent)
                    }
                }
            }, 5500L)

            showToast("กำลังออกจาก Zoom และปิดกล้อง/ไมค์...")
        }

        try {
            windowManager.addView(closeButton, closeParams)
            zoomCloseOverlayView = closeButton
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeCloseZoomButton() {
        zoomCloseOverlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        zoomCloseOverlayView = null
    }

    private fun setupDraggableWindow(
        rootView: View,
        handleView: View?,
        params: WindowManager.LayoutParams,
        onClickAction: () -> Unit
    ) {
        handleView?.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager.updateViewLayout(rootView, params)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = abs(event.rawX - initialTouchX)
                        val diffY = abs(event.rawY - initialTouchY)

                        if (diffX < 10 && diffY < 10) {
                            onClickAction()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    override fun onDestroy() {
        // ถ้า Service ถูกปิดขณะอยู่ในโหมดกล้อง ให้สั่ง Pi 5 หยุดกล้องก่อน
        if (activeStreamType == "camera") {
            try {
                val stopUrl = "http://$pcIpAddress:$pcPort/api/stop_button4"
                val request = Request.Builder()
                    .url(stopUrl)
                    .post(RequestBody.create(null, ByteArray(0)))
                    .build()
                okHttpClient.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {}
                    override fun onResponse(call: Call, response: Response) {
                        response.close()
                    }
                })
            } catch (_: Exception) {
            }
            activeStreamType = null
        }

        ZoomAccessibilityService.autoEnableMediaRequested.set(false)
        ZoomAccessibilityService.forceLeaveRequested.set(true)

        super.onDestroy()

        floatingView?.findViewById<WebView>(R.id.streamWebView)?.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }

        stopZoomMonitoring()
        webSocket?.close(1000, "Service Destroyed")
        removeCloseZoomButton()
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        floatingView = null
    }
}