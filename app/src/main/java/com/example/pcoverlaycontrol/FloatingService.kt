package com.example.pcoverlaycontrol

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
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
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
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
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class FloatingService : Service() {

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "overlay_channel"
        private const val NOTIFICATION_ID = 1
        private const val ZOOM_CHECK_INTERVAL_MS = 2000L
        private const val ZOOM_MAX_NOT_FOUND = 10 // ~20 วินาที
        private const val WS_RECONNECT_DELAY_MS = 3000L
        private const val TAG = "FloatingService"

        private const val WS_PING_INTERVAL_SECONDS = 10L
        private const val MAX_PIN_ATTEMPTS = 5
        private const val PIN_LOCK_MS = 30_000L
        private const val USAGE_EVENTS_WINDOW_MS = 5 * 60 * 1000L

        // flags ของหน้าต่างปุ่มลอย (ย่อ) และของโหมดสตรีมเต็มจอ
        private const val FLAGS_FLOATING = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        private const val FLAGS_FULLSCREEN = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        // ตอนกรอก IP ต้องรับโฟกัสให้คีย์บอร์ดขึ้น (ปกติไม่รับ เพื่อไม่แย่งคีย์จากแอปอื่น)
        private const val FLAGS_FULLSCREEN_EDITING = FLAGS_FULLSCREEN and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
    }

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    // ping ทุก 10 วินาที เพื่อให้รู้ตัวเมื่อ Pi หลุดเงียบๆ (ไม่ส่ง close frame)
    private val okHttpClient = OkHttpClient.Builder()
        .pingInterval(WS_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()
    private var isDestroyed = false

    // 📌 IP ของ Raspberry Pi 5 เจ้าของเปลี่ยนได้จากแผงตั้งค่าที่ซ่อนอยู่ในหน้าสตรีม
    // (กดค้างที่ตัวเลขจำนวนนิ้วมือมุมซ้ายบน)
    private val piSettings by lazy { PiSettings(this) }

    // 📌 หน้าต่างปุ่มลอย
    private var floatingView: View? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var savedX = 0
    private var savedY = 0

    // 📌 สตรีมกล้อง (ปุ่ม 2)
    private var streamWebView: WebView? = null
    private var tvFingerCount: TextView? = null
    private var webSocket: WebSocket? = null
    private var isCameraStreamActive = false
    private var failedPinAttempts = 0
    private var pinLockedUntil = 0L
    private var webSocketLost = false // WebSocket เคยหลุด: เมื่อต่อใหม่ได้ต้องโหลดภาพสตรีมใหม่

    // 📌 Zoom (ปุ่ม 1)
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
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
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

    // ===========================================================================
    // 📌 ปุ่มลอยหลัก
    // ===========================================================================
    @SuppressLint("InflateParams") // หน้าต่าง overlay ไม่มี parent ให้ผูก จึงต้องส่ง null
    private fun initFloatingView() {
        if (!Settings.canDrawOverlays(this)) {
            showToast(getString(R.string.toast_need_overlay))
            stopSelf()
            return
        }

        floatingView?.let {
            it.visibility = View.VISIBLE
            return
        }

        var addedView: View? = null
        try {
            val view = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                FLAGS_FLOATING,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 100
            }

            windowManager.addView(view, params)
            addedView = view
            floatingView = view
            floatingParams = params

            val controlButtonsGroup = view.findViewById<View>(R.id.controlButtonsGroup)
            setupDraggableWindow(view, view.findViewById(R.id.btnMain), params) {
                controlButtonsGroup.isVisible = !controlButtonsGroup.isVisible
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

            view.findViewById<View>(R.id.btn1).setOnClickListener { openZoom() }
            view.findViewById<View>(R.id.btn2).setOnClickListener { openCameraStream() }
            view.findViewById<View>(R.id.btnCloseStream).setOnClickListener { closeCameraStream() }
            setupHostSettings(view)
        } catch (e: Exception) {
            Log.w(TAG, e)
            addedView?.let { runCatching { windowManager.removeView(it) } }
            floatingView = null
            floatingParams = null
            showToast(getString(R.string.toast_overlay_create_failed, e.message))
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
                            Log.w(TAG, e)
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (abs(event.rawX - initialTouchX) < 10 && abs(event.rawY - initialTouchY) < 10) {
                            v?.performClick()
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
    // 📌 [ปุ่มที่ 2] สตรีมภาพวิดีโอจาก Pi 5
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
        params.flags = FLAGS_FULLSCREEN
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN // กันคีย์บอร์ดบังช่องกรอก IP
        windowManager.updateViewLayout(view, params)

        view.findViewById<View>(R.id.mainMenuContainer).visibility = View.GONE
        view.findViewById<View>(R.id.streamContainer).visibility = View.VISIBLE
        tvFingerCount?.text = "0"

        startStream()
        showToast(getString(R.string.toast_opening_stream))
    }

    // โหลดภาพสตรีม สั่ง Pi เปิดโหมดติดตามคน และเชื่อม WebSocket (ใช้ IP ปัจจุบันจากการตั้งค่า)
    private fun startStream() {
        val baseUrl = piSettings.baseUrl
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
                <img src="$baseUrl/video_feed">
            </body>
            </html>
        """.trimIndent()

        streamWebView?.apply {
            onResume()
            loadDataWithBaseURL("$baseUrl/", streamHtml, "text/html", "UTF-8", null)
        }

        postToPi(
            "/api/trigger_button4",
            successMessage = getString(R.string.toast_tracking_on),
            failMessage = getString(R.string.toast_pi_connect_failed)
        )
        connectWebSocket()
    }

    // ===========================================================================
    // 📌 แผงตั้งค่า IP ของ Pi 5 (ซ่อนอยู่ ไม่มีปุ่มให้เห็น)
    // เปิดโดย "กดค้าง" ที่ตัวเลขจำนวนนิ้วมือมุมซ้ายบนในหน้าสตรีม
    // ===========================================================================
    private fun setupHostSettings(view: View) {
        val etPin = view.findViewById<EditText>(R.id.etPin)
        val etHost = view.findViewById<EditText>(R.id.etPiHost)
        val etNewPin = view.findViewById<EditText>(R.id.etNewPin)

        // กดค้างที่ตัวเลขนิ้วมือ: ถ้าเคยตั้ง PIN ไว้ให้ถาม PIN ก่อน ถ้ายังไม่เคยให้ตั้ง PIN พร้อมกับ IP
        tvFingerCount?.setOnLongClickListener {
            if (SystemClock.elapsedRealtime() < pinLockedUntil) {
                showToast(getString(R.string.toast_pin_locked_wait))
                return@setOnLongClickListener true
            }
            etPin.text.clear()
            etNewPin.text.clear()
            setStreamWindowFocusable(true)
            view.findViewById<View>(R.id.settingsPanel).visibility = View.VISIBLE
            if (piSettings.hasPin) showPinSection(view) else showHostSection(view, firstTime = true)
            true
        }

        view.findViewById<View>(R.id.btnPinCancel).setOnClickListener { hideHostSettings() }
        view.findViewById<View>(R.id.btnPinOk).setOnClickListener {
            val pin = etPin.text.toString()
            etPin.text.clear()
            if (piSettings.checkPin(pin)) {
                failedPinAttempts = 0
                showHostSection(view, firstTime = false)
            } else if (++failedPinAttempts >= MAX_PIN_ATTEMPTS) {
                failedPinAttempts = 0
                pinLockedUntil = SystemClock.elapsedRealtime() + PIN_LOCK_MS
                hideHostSettings()
                showToast(getString(R.string.toast_pin_locked))
            } else {
                showToast(getString(R.string.toast_pin_wrong))
            }
        }

        view.findViewById<View>(R.id.btnCancelHost).setOnClickListener { hideHostSettings() }
        view.findViewById<View>(R.id.btnResetHost).setOnClickListener { etHost.setText(PiSettings.DEFAULT_HOST) }
        view.findViewById<View>(R.id.btnSaveHost).setOnClickListener {
            val host = etHost.text.toString().trim()
            if (!PiSettings.isValidHost(host)) {
                showToast(getString(R.string.toast_host_invalid))
                return@setOnClickListener
            }

            val newPin = etNewPin.text.toString().trim()
            val mustSetPin = !piSettings.hasPin
            if ((mustSetPin || newPin.isNotEmpty()) && !PiSettings.isValidPin(newPin)) {
                showToast(getString(R.string.toast_pin_format))
                return@setOnClickListener
            }
            if (newPin.isNotEmpty()) piSettings.setPin(newPin)

            val oldHost = piSettings.host
            piSettings.host = host
            hideHostSettings()
            showToast(getString(R.string.toast_host_saved, host))
            if (host != oldHost) restartStream(oldHost)
        }
    }

    private fun showPinSection(view: View) {
        view.findViewById<View>(R.id.pinSection).visibility = View.VISIBLE
        view.findViewById<View>(R.id.hostSection).visibility = View.GONE
        view.findViewById<View>(R.id.etPin).requestFocus()
    }

    private fun showHostSection(view: View, firstTime: Boolean) {
        val etHost = view.findViewById<EditText>(R.id.etPiHost)
        val etNewPin = view.findViewById<EditText>(R.id.etNewPin)
        etHost.setText(piSettings.host)
        etHost.setSelection(etHost.text.length)
        etNewPin.setHint(if (firstTime) R.string.settings_first_pin_hint else R.string.settings_new_pin_hint)
        view.findViewById<View>(R.id.pinSection).visibility = View.GONE
        view.findViewById<View>(R.id.hostSection).visibility = View.VISIBLE
        etHost.requestFocus()
    }

    private fun hideHostSettings() {
        val view = floatingView ?: return
        val panel = view.findViewById<View>(R.id.settingsPanel)
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(panel.windowToken, 0)
        listOf(R.id.etPin, R.id.etNewPin).forEach { id ->
            view.findViewById<EditText>(id).apply { text.clear(); clearFocus() }
        }
        view.findViewById<EditText>(R.id.etPiHost).clearFocus()
        view.findViewById<View>(R.id.hostSection).visibility = View.GONE
        view.findViewById<View>(R.id.pinSection).visibility = View.VISIBLE
        panel.visibility = View.GONE
        if (isCameraStreamActive) setStreamWindowFocusable(false)
    }

    private fun setStreamWindowFocusable(focusable: Boolean) {
        val view = floatingView ?: return
        val params = floatingParams ?: return
        params.flags = if (focusable) FLAGS_FULLSCREEN_EDITING else FLAGS_FULLSCREEN
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(TAG, e)
        }
    }

    // เชื่อมต่อใหม่ด้วย IP ที่เพิ่งบันทึก และสั่ง Pi เครื่องเดิมให้ปิดกล้อง (กันค้างทำงานอยู่)
    private fun restartStream(oldHost: String) {
        if (!isCameraStreamActive) return
        disconnectWebSocket()
        postToPi("/api/stop_button4", baseUrl = PiSettings.baseUrlFor(oldHost))
        startStream()
    }

    private fun closeCameraStream() {
        if (!isCameraStreamActive) return
        isCameraStreamActive = false
        hideHostSettings()

        postToPi(
            "/api/stop_button4",
            successMessage = getString(R.string.toast_camera_stopped),
            failMessage = getString(R.string.toast_camera_stop_failed)
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
        params.flags = FLAGS_FLOATING
        windowManager.updateViewLayout(view, params)

        view.findViewById<View>(R.id.streamContainer).visibility = View.GONE
        view.findViewById<View>(R.id.mainMenuContainer).visibility = View.VISIBLE
    }

    // ถ้าไม่ระบุข้อความ จะส่งแบบเงียบ (ไม่ขึ้น Toast)
    private fun postToPi(
        path: String,
        successMessage: String? = null,
        failMessage: String? = null,
        baseUrl: String = piSettings.baseUrl
    ) {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(ByteArray(0).toRequestBody())
            .build()

        okHttpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                failMessage?.let { showToast("$it: ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val message = if (it.isSuccessful) successMessage else failMessage?.let { m -> "$m (HTTP ${it.code})" }
                    message?.let(::showToast)
                }
            }
        })
    }

    // 📌 รับจำนวนนิ้วมือจาก Pi 5 ผ่าน WebSocket (เชื่อมต่อเฉพาะตอนเปิดสตรีม)
    private fun connectWebSocket() {
        if (isDestroyed || !isCameraStreamActive || webSocket != null) return

        val request = Request.Builder().url(piSettings.webSocketUrl).build()
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                mainHandler.post {
                    // ภาพ MJPEG ค้างถาวรเมื่อเครือข่ายหลุด จึงต้องโหลดใหม่หลังต่อกลับมาได้
                    if (webSocketLost && isCameraStreamActive && this@FloatingService.webSocket === webSocket) {
                        webSocketLost = false
                        startStream()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val count = try {
                    JSONObject(text).optInt("count", 0)
                } catch (e: Exception) {
                    return
                }
                mainHandler.post { tvFingerCount?.text = count.toString() }
            }

            // Pi ปิดการเชื่อมต่อก่อน: ต้องตอบ close frame กลับ onClosed ถึงจะถูกเรียก
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
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
            webSocketLost = true
            mainHandler.postDelayed({ connectWebSocket() }, WS_RECONNECT_DELAY_MS)
        }
    }

    private fun disconnectWebSocket() {
        webSocket?.close(1000, "Stream closed")
        webSocket = null
        webSocketLost = false
    }

    // ===========================================================================
    // 📌 [ปุ่มที่ 1] Zoom
    // ===========================================================================
    private fun openZoom() {
        try {
            previousForegroundPackage = getForegroundPackageName()

            val launchIntent = packageManager.getLaunchIntentForPackage(ZoomAccessibilityService.ZOOM_PACKAGE)
            if (launchIntent == null) {
                showToast(getString(R.string.toast_zoom_missing))
                return
            }
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            startActivity(launchIntent)
            showToast(getString(R.string.toast_opening_zoom))

            showCloseZoomButton()
            startZoomMonitoring()
        } catch (e: Exception) {
            Log.w(TAG, e)
            showToast(getString(R.string.toast_zoom_failed))
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

    // แอปที่ถูกเปิดขึ้นหน้าจอล่าสุดจาก UsageEvents (แม่นกว่า queryUsageStats)
    // คืน null ถ้าไม่มี event ในช่วง USAGE_EVENTS_WINDOW_MS
    private fun getForegroundPackageName(): String? {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val events = usageStatsManager.queryEvents(now - USAGE_EVENTS_WINDOW_MS, now) ?: return null

        @Suppress("DEPRECATION")
        val resumedType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            UsageEvents.Event.ACTIVITY_RESUMED
        } else {
            UsageEvents.Event.MOVE_TO_FOREGROUND
        }

        val event = UsageEvents.Event()
        var lastPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == resumedType) lastPackage = event.packageName
        }
        return lastPackage
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
                setColor("#FF1744".toColorInt())
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
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
            Log.w(TAG, e)
        }
    }

    private fun removeCloseZoomButton() {
        zoomCloseOverlayView?.let { view ->
            try {
                if (view.isAttachedToWindow) windowManager.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, e)
            }
        }
        zoomCloseOverlayView = null
    }

    private fun leaveZoom() {
        stopZoomMonitoring()
        removeCloseZoomButton()

        // ให้ AccessibilityService กด Leave ให้ แล้วรอสัญญาณ ACTION_ZOOM_LEAVE_FINISHED ค่อยกลับหน้าเดิม
        if (ZoomAccessibilityService.requestForceLeave()) {
            showToast(getString(R.string.toast_leaving_zoom))
        } else {
            showToast(getString(R.string.toast_need_accessibility))
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
            Log.w(TAG, e)
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        mainHandler.removeCallbacksAndMessages(null)

        try {
            unregisterReceiver(zoomStateReceiver)
        } catch (e: Exception) {
            Log.w(TAG, e)
        }

        if (isCameraStreamActive) {
            isCameraStreamActive = false
            postToPi("/api/stop_button4", getString(R.string.toast_camera_stopped), getString(R.string.toast_camera_stop_failed))
        }
        disconnectWebSocket()
        stopZoomMonitoring()
        removeCloseZoomButton()
        okHttpClient.dispatcher.executorService.shutdown() // คำสั่งปิดกล้องที่ส่งค้างอยู่ยังทำงานจนจบ

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
                Log.w(TAG, e)
            }
        }
        floatingView = null
        floatingParams = null

        super.onDestroy()
    }
}
