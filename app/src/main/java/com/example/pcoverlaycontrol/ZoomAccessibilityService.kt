package com.example.pcoverlaycontrol

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ZoomAccessibilityService : AccessibilityService() {

    companion object {
        const val ZOOM_PACKAGE = "us.zoom.videomeetings"

        // 📌 สัญญาณที่ส่งไปให้ FloatingService
        const val ACTION_ZOOM_OPENED = "com.example.pcoverlaycontrol.ACTION_ZOOM_OPENED"
        const val ACTION_ZOOM_LEAVE_FINISHED = "com.example.pcoverlaycontrol.ACTION_ZOOM_LEAVE_FINISHED"

        private val LEAVE_ICON_TEXTS = listOf("Leave", "End", "ออกจากการประชุม", "จบการประชุม")
        private val CONFIRM_TEXTS = listOf("Leave Meeting", "End Meeting for All", "ออกจากการประชุม")

        private const val LEAVE_SEARCH_TIMEOUT_MS = 4000L
        private const val LEAVE_SEARCH_INTERVAL_MS = 200L

        @Volatile
        var isZoomInForeground: Boolean = false
            private set

        @Volatile
        private var instance: ZoomAccessibilityService? = null

        /**
         * สั่งให้กดปุ่ม Leave ใน Zoom อัตโนมัติ
         * เมื่อเสร็จ (หรือหมดเวลา) จะส่ง [ACTION_ZOOM_LEAVE_FINISHED]
         * @return false ถ้ายังไม่ได้เปิด Accessibility Service นี้
         */
        fun requestForceLeave(): Boolean {
            val service = instance ?: return false
            service.mainHandler.post { service.startLeaveAttempt() }
            return true
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // ใช้บน Main Thread เท่านั้น
    private var leaveAttemptActive = false
    private var leaveIconClicked = false
    private var leaveAttemptStartTime = 0L

    private val leaveWatchdog = object : Runnable {
        override fun run() {
            if (!leaveAttemptActive) return

            // หาปุ่มไม่เจอนานเกิน Timeout ให้ยอมแพ้และกลับไปหน้าเดิม
            if (System.currentTimeMillis() - leaveAttemptStartTime > LEAVE_SEARCH_TIMEOUT_MS) {
                finishLeaveProcess()
                return
            }

            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString()

            if (pkg == ZOOM_PACKAGE) {
                // 1. กดปุ่ม Leave ก่อน  2. แล้วกดยืนยันใน Pop-up
                if (!leaveIconClicked) {
                    leaveIconClicked = findAndClick(root, LEAVE_ICON_TEXTS)
                } else if (findAndClick(root, CONFIRM_TEXTS)) {
                    mainHandler.postDelayed({ finishLeaveProcess() }, 300L)
                    return
                }
            } else if (pkg != null && pkg != packageName) {
                // ผู้ใช้ออกจาก Zoom ไปแล้ว
                finishLeaveProcess()
                return
            }

            mainHandler.postDelayed(this, LEAVE_SEARCH_INTERVAL_MS)
        }
    }

    private fun startLeaveAttempt() {
        if (leaveAttemptActive) return
        leaveAttemptActive = true
        leaveIconClicked = false
        leaveAttemptStartTime = System.currentTimeMillis()
        mainHandler.removeCallbacks(leaveWatchdog)
        mainHandler.post(leaveWatchdog)
    }

    private fun finishLeaveProcess() {
        if (!leaveAttemptActive) return
        leaveAttemptActive = false
        mainHandler.removeCallbacks(leaveWatchdog)
        sendBroadcast(Intent(ACTION_ZOOM_LEAVE_FINISHED).setPackage(packageName))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return

        if (pkg == ZOOM_PACKAGE) {
            // แจ้ง FloatingService เฉพาะตอนเพิ่งเข้า Zoom (ไม่ส่งซ้ำทุก event)
            if (!isZoomInForeground) {
                isZoomInForeground = true
                sendBroadcast(Intent(ACTION_ZOOM_OPENED).setPackage(packageName))
            }
        } else if (pkg != packageName && pkg != "com.android.systemui") {
            isZoomInForeground = false
        }
    }

    private fun findAndClick(node: AccessibilityNodeInfo, targets: List<String>): Boolean {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(node)

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            val text = current.text?.toString()
            val desc = current.contentDescription?.toString()

            val isMatch = targets.any { t ->
                (text?.contains(t, ignoreCase = true) == true) ||
                        (desc?.contains(t, ignoreCase = true) == true)
            }

            if (isMatch) {
                val clickable = findClickableSelfOrParent(current)
                if (clickable != null) {
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    return true
                }
            }

            for (i in 0 until current.childCount) {
                current.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return false
    }

    private fun findClickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isZoomInForeground = false
        leaveAttemptActive = false
        mainHandler.removeCallbacks(leaveWatchdog)
    }

    override fun onDestroy() {
        instance = null
        isZoomInForeground = false
        leaveAttemptActive = false
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
