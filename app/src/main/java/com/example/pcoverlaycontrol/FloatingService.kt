package com.example.pcoverlaycontrol

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import kotlin.math.abs

class FloatingService : Service() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "overlay_channel"
        private const val NOTIFICATION_ID = 1
        private const val ZOOM_CHECK_INTERVAL_MS = 2000L
        private const val ZOOM_MAX_NOT_FOUND = 10 // ~20 วินาที
    }

    // 📌 IP Address และ Port ของ Raspberry Pi 5
    private val pcIpAddress = "192.168.100.245"
    private val pcPort = 5000
    private val wsPort = 8765
    private val piBaseUrl = "http://$pcIpAddress:$pcPort"

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val okHttpClient = OkHttpClient()
    private var isDestroyed = false

    // 📌 หน้าต่างปุ่มลอย
    private var floatingView: View? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var savedX = 0
    private var savedY = 0

    // 📌 สตรีมกล้อง (ปุ่ม 4)
    private var streamWebView: WebView? = null
    private var tvFingerCount: TextView? = null
    private var webSocket: WebSocket? = null
    private var isCameraStreamActive = false

    // 📌 Zoom (ปุ่ม 3)
    private var zoomCloseOverlayView: View? = null
    private var isZoomMonitoring = false
    private var zoomNotFoundCount = 0
    private var previousForegroundPackage: String? = null

    private val zoomStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ZoomAccessibilityService.ACTION_ZOOM_OPENED -> {
                    showCloseZoomButton()
                    startZoomMonitoring()
                }
                ZoomAccessibilityService.ACTION_ZOOM_LEAVE_FINISHED -> returnToPreviousApp()
            }
        }
    }

    private val zoomCheckRunnable = object : Runnable {
        override fun run() {
            if (!isZoomMonitoring) return

            val isZoomActive = ZoomAccessibilityService.isZoomInForeground ||
                    getForegroundPackageName() == ZoomAccessibilityService.ZOOM_PACKAGE

            if (isZoomActive) {
                zoomNotFoundCount = 0
                showCloseZoomButton()
            } else if (++zoomNotFoundCount >= ZOOM_MAX_NOT_FOUND) {
                stopZoomMonitoring()
                removeCloseZoomButton()
                return
            }
            mainHandler.postDelayed(this, ZOOM_CHECK_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val filter = IntentFilter().apply {
            addAction(ZoomAccessibilityService.ACTION_ZOOM_OPENED)
            addAction(ZoomAccessibilityService.ACTION_ZOOM_LEAVE_FINISHED)
        }
        ContextCompat.registerReceiver(this, zoomStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        initFloatingView()
        return START_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL_ID, "Overlay Channel", NotificationManager.IMPORTANCE_LOW)
        )

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("PC Overlay Control")
            .setContentText("ปุ่มลอยกำลังทำงาน...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun overlayWindowType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    // ===========================================================================
    // 📌 ปุ่มลอยหลัก
    // ===========================================================================
    private fun initFloatingView() {
        if (!Settings.canDrawOverlays(this)) {
            showToast("กรุณาเปิดสิทธิ์ Display over other apps")
            return
        }

        floatingView?.let {
            it.visibility = View.VISIBLE
            return
        }

        try {
            val view = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayWindowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 100
            }

            windowManager.addView(view, params)
            floatingView = view
            floatingParams = params

            val controlButtonsGroup = view.findViewById<View>(R.id.controlButtonsGroup)
            setupDraggableWindow(view, view.findViewById(R.id.btnMain), params) {
                controlButtonsGroup.visibility =
                    if (controlButtonsGroup.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            }

            tvFingerCount = view.findViewById(R.id.tvFingerCount)
            streamWebView = view.findViewById<WebView>(R.id.streamWebView).apply {
                settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                webViewClient = WebViewClient()
            }

            view.findViewById<View>(R.id.btn3).setOnClickListener { openZoom() }
            view.findViewById<View>(R.id.btn4).setOnClickListener { openCameraStream() }
            view.findViewById<View>(R.id.btnCloseStream).setOnClickListener { closeCameraStream() }
        } catch (e: Exception) {
            e.printStackTrace()
            floatingView = null
            floatingParams = null
            showToast("สร้างปุ่มลอยล้มเหลว: ${e.message}")
        }
    }

    private fun setupDraggableWindow(
        rootView: View,
        handleView: View,
        params: WindowManager.LayoutParams,
        onClickAction: () -> Unit
    ) {
        handleView.setOnTouchListener(object : View.OnTouchListener {
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
                        if (abs(event.rawX - initialTouchX) < 10 && abs(event.rawY - initialTouchY) < 10) {
                            onClickAction()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    // ===========================================================================
    // 📌 [ปุ่มที่ 4] สตรีมภาพวิดีโอจาก Pi 5
    // ===========================================================================
    private fun openCameraStream() {
        val view = floatingView ?: return
        val params = floatingParams ?: return
        if (isCameraStreamActive) return
        isCameraStreamActive = true

        // ขยายเต็มจอ (จำตำแหน่งปุ่มลอยไว้เพื่อคืนค่าตอนปิด)
        savedX = params.x
        savedY = params.y
        params.x = 0
        params.y = 0
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = WindowManager.LayoutParams.MATCH_PARENT
        params.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        windowManager.updateViewLayout(view, params)

        view.findViewById<View>(R.id.mainMenuContainer).visibility = View.GONE
        view.findViewById<View>(R.id.streamContainer).visibility = View.VISIBLE
        tvFingerCount?.text = "0"

        val streamHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
                <style>
                    html, body { margin: 0; padding: 0; width: 100%; height: 100%; background-color: #000; overflow: hidden; }
                    img { width: 100vw; height: 100vh; object-fit: cover; display: block; }
                </style>
            </head>
            <body>
                <img src="$piBaseUrl/video_feed">
            </body>
            </html>
        """.trimIndent()

        streamWebView?.apply {
            onResume()
            loadDataWithBaseURL("$piBaseUrl/", streamHtml, "text/html", "UTF-8", null)
        }

        postToPi(
            "/api/trigger_button4",
            successMessage = "เปิดโหมดติดตามคน (PERSON) บน Pi 5 เรียบร้อย",
            failMessage = "เชื่อมต่อ Pi 5 ไม่สำเร็จ"
        )
        connectWebSocket()
        showToast("กำลังเปิดสตรีมภาพแบบเต็มจอ...")
    }

    private fun closeCameraStream() {
        if (!isCameraStreamActive) return
        isCameraStreamActive = false

        postToPi(
            "/api/stop_button4",
            successMessage = "ปิดกล้องที่ Pi 5 เรียบร้อย",
            failMessage = "ส่งคำสั่งปิดกล้องไม่สำเร็จ"
        )
        disconnectWebSocket()

        streamWebView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            onPause()
        }

        val view = floatingView ?: return
        val params = floatingParams ?: return
        params.x = savedX
        params.y = savedY
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        windowManager.updateViewLayout(view, params)

        view.findViewById<View>(R.id.streamContainer).visibility = View.GONE
        view.findViewById<View>(R.id.mainMenuContainer).visibility = View.VISIBLE
    }

    private fun postToPi(path: String, successMessage: String, failMessage: String) {
        val request = Request.Builder()
            .url("$piBaseUrl$path")
            .post(ByteArray(0).toRequestBody())
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                showToast("$failMessage: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    showToast(if (it.isSuccessful) successMessage else "$failMessage (HTTP ${it.code})")
                }
            }
        })
    }

    // 📌 รับจำนวนนิ้วมือจาก Pi 5 ผ่าน WebSocket (เชื่อมต่อเฉพาะตอนเปิดสตรีม)
    private fun connectWebSocket() {
        if (isDestroyed || !isCameraStreamActive || webSocket != null) return

        val request = Request.Builder().url("ws://$pcIpAddress:$wsPort").build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val count = try {
                    JSONObject(text).optInt("count", 0)
                } catch (e: Exception) {
                    return
                }
                mainHandler.post { tvFingerCount?.text = count.toString() }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect(webSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                scheduleReconnect(webSocket)
            }
        })
    }

    private fun scheduleReconnect(closedSocket: WebSocket) {
        mainHandler.post {
            if (webSocket !== closedSocket) return@post
            webSocket = null
            mainHandler.postDelayed({ connectWebSocket() }, 3000L)
        }
    }

    private fun disconnectWebSocket() {
        webSocket?.close(1000, "Stream closed")
        webSocket = null
    }

    // ===========================================================================
    // 📌 [ปุ่มที่ 3] Zoom
    // ===========================================================================
    private fun openZoom() {
        try {
            previousForegroundPackage = getForegroundPackageName()

            val launchIntent = packageManager.getLaunchIntentForPackage(ZoomAccessibilityService.ZOOM_PACKAGE)
            if (launchIntent == null) {
                showToast("ไม่พบแอป Zoom ในเครื่อง")
                return
            }
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            startActivity(launchIntent)
            showToast("กำลังเปิดหน้าหลัก Zoom...")

            showCloseZoomButton()
            startZoomMonitoring()
        } catch (e: Exception) {
            e.printStackTrace()
            showToast("ไม่สามารถเปิด Zoom ได้")
        }
    }

    private fun startZoomMonitoring() {
        if (isZoomMonitoring) return
        isZoomMonitoring = true
        zoomNotFoundCount = 0
        mainHandler.postDelayed(zoomCheckRunnable, 1000L)
    }

    private fun stopZoomMonitoring() {
        isZoomMonitoring = false
        mainHandler.removeCallbacks(zoomCheckRunnable)
    }

    private fun getForegroundPackageName(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        return usageStatsManager
            .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 10_000L, now)
            ?.maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }

    // 📌 แสดงปุ่มกากบาทสีแดงบนหน้าจอ Zoom
    private fun showCloseZoomButton() {
        if (isDestroyed || zoomCloseOverlayView?.isAttachedToWindow == true) return

        val density = resources.displayMetrics.density
        val buttonSizePx = (60 * density).toInt()

        val closeButton = Button(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#FF1744"))
                setStroke((3 * density).toInt(), Color.WHITE)
            }
            setPadding(0, 0, 0, 0)
            gravity = Gravity.CENTER
            includeFontPadding = false
            elevation = 999f
            setOnClickListener { leaveZoom() }
        }

        val closeParams = WindowManager.LayoutParams(
            buttonSizePx,
            buttonSizePx,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (20 * density).toInt()
            y = (40 * density).toInt()
        }

        try {
            windowManager.addView(closeButton, closeParams)
            zoomCloseOverlayView = closeButton
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeCloseZoomButton() {
        zoomCloseOverlayView?.let { view ->
            try {
                if (view.isAttachedToWindow) windowManager.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        zoomCloseOverlayView = null
    }

    private fun leaveZoom() {
        stopZoomMonitoring()
        removeCloseZoomButton()

        // ให้ AccessibilityService กด Leave ให้ แล้วรอสัญญาณ ACTION_ZOOM_LEAVE_FINISHED ค่อยกลับหน้าเดิม
        if (ZoomAccessibilityService.requestForceLeave()) {
            showToast("กำลังออกจาก Zoom...")
        } else {
            showToast("ยังไม่ได้เปิด Accessibility Service จึงกด Leave อัตโนมัติไม่ได้")
            returnToPreviousApp()
        }
    }

    private fun returnToPreviousApp() {
        val target = previousForegroundPackage
        previousForegroundPackage = null

        val backIntent = target
            ?.takeIf { it != ZoomAccessibilityService.ZOOM_PACKAGE && it != packageName }
            ?.let { packageManager.getLaunchIntentForPackage(it) }
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            ?: Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            startActivity(backIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)

        try {
            unregisterReceiver(zoomStateReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (isCameraStreamActive) {
            isCameraStreamActive = false
            postToPi("/api/stop_button4", "ปิดกล้องที่ Pi 5 เรียบร้อย", "ส่งคำสั่งปิดกล้องไม่สำเร็จ")
        }
        disconnectWebSocket()
        stopZoomMonitoring()
        removeCloseZoomButton()

        streamWebView?.apply {
            stopLoading()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        streamWebView = null

        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        floatingView = null
        floatingParams = null

        super.onDestroy()
    }
}
