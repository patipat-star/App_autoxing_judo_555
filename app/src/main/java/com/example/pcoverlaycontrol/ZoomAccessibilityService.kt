package com.example.pcoverlaycontrol

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean

class ZoomAccessibilityService : AccessibilityService() {

    companion object {
        // 📌 flag สั่งให้เริ่มค้นหาปุ่ม Leave/End แล้วกดออกจากการประชุม
        val forceLeaveRequested = AtomicBoolean(false)

        // 📌 ขอให้ Accessibility ช่วยเปิดกล้อง/ไมค์ใน Zoom เมื่อเข้าสู่หน้าประชุม
        // หมายเหตุ: จะทำงานได้ก็ต่อเมื่อ Zoom มีปุ่มเปิดสื่อให้กด และสิทธิ์ของ Zoom เองได้รับอนุญาตแล้ว
        val autoEnableMediaRequested = AtomicBoolean(false)

        // ปุ่มที่ใช้เปิดกล้อง/ไมค์ใน Zoom (รองรับข้อความ/ContentDescription หลายแบบ)
        private val CAMERA_ON_TEXTS = listOf(
            "Join with Video", "Turn On My Video", "Turn On Video",
            "Start My Video", "Start Video", "เปิดวิดีโอ", "เปิดกล้อง"
        )

        private val MICROPHONE_ON_TEXTS = listOf(
            "Join Audio", "Join with Audio", "Turn On My Audio", "Turn On Audio",
            "Unmute", "Start Audio", "เปิดเสียง", "เปิดไมค์", "เปิดไมโครโฟน"
        )

        // 📌 flag "โหมดเฝ้าระวัง" หลังกดวางสาย กันไม่ให้ Zoom แอบเด้งหน้าเว็บ/feedback/settings ขึ้นมา
        private val postLeaveGuardActive = AtomicBoolean(false)
        private var guardEndTimeMs = 0L

        private const val GUARD_DURATION_MS = 6000L   // เฝ้าระวังต่อเนื่อง 6 วินาทีหลังกดวางสาย
        private const val GUARD_TICK_MS = 400L        // สั่ง Home ซ้ำทุกๆ 400ms ระหว่างที่เฝ้าระวัง

        private const val ZOOM_PACKAGE = "us.zoom.videomeetings"

        // 📌 ปุ่มบน Toolbar ของหน้าประชุม (กดครั้งแรกเพื่อเริ่มขั้นตอนออก)
        private val LEAVE_ICON_TEXTS = listOf(
            "Leave", "leave", "End", "ออกจากการประชุม", "จบการประชุม"
        )

        // 📌 ปุ่มยืนยันใน popup ที่เด้งขึ้นมาหลังกด Leave icon (ถ้ามี)
        private val CONFIRM_TEXTS = listOf(
            "Leave Meeting", "leave meeting", "End Meeting for All",
            "Leave", "ออกจากการประชุม", "End"
        )

        // 📌 ตั้งเวลาสูงสุดที่ยอมให้ "ค้นหาปุ่ม Leave" ได้ ก่อนจะยอมแพ้แล้วถือว่า
        //    ผู้ใช้ยังไม่ได้เข้าห้องประชุมจริง (อยู่แค่หน้า join/home ของ Zoom)
        //    → กรณีนี้ไม่มีปุ่ม Leave ให้กดอยู่แล้ว จึงแค่พากลับไปหน้าเดิมทันที
        private const val LEAVE_SEARCH_TIMEOUT_MS = 5000L // อ้างอิงจุดแก้คู่กันใน FloatingService.showCloseZoomButton()
        private const val LEAVE_SEARCH_INTERVAL_MS = 300L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // ป้องกันไม่ให้ watchdog ถูก start ซ้อนกันหลายชุด
    private val leaveAttemptActive = AtomicBoolean(false)
    private var leaveAttemptStartTime = 0L

    // 📌 Watchdog: ยิงคำสั่งกลับ Home ซ้ำๆ ตามรอบเวลา ไม่ต้องพึ่ง accessibility event เลย
    // เพราะ WebView ที่โหลดเนื้อหาใหม่ในหน้าต่างเดิมอาจไม่ยิง event ให้เราเลย
    private val homeWatchdog = object : Runnable {
        override fun run() {
            if (!postLeaveGuardActive.get()) return

            if (System.currentTimeMillis() > guardEndTimeMs) {
                postLeaveGuardActive.set(false)
                return
            }

            performGlobalAction(GLOBAL_ACTION_HOME)
            mainHandler.postDelayed(this, GUARD_TICK_MS)
        }
    }

    // ===========================================================================
    // ✅ ใหม่: leaveWatchdog — หัวใจของการแก้ปัญหา "ออกบ้างไม่ออกบ้าง"
    // เดิม: พึ่งพา onAccessibilityEvent เพียงอย่างเดียว ถ้าไม่มี event เด้งเข้ามา
    //       จังหวะที่ต้องกด ก็จะพลาดไปเฉยๆ ไม่มีการลองใหม่
    // ใหม่: เมื่อเริ่มขั้นตอนออกจากห้อง จะ "พยายามค้นหา+กดปุ่มเองทุก 300ms" ต่อเนื่อง
    //       โดยไม่รอ event เลย จนกว่าจะสำเร็จ หรือครบเวลา LEAVE_SEARCH_TIMEOUT_MS
    //       ถ้าครบเวลาแล้วยังไม่เจอปุ่ม Leave (แปลว่ายังไม่ได้เข้าห้องประชุมจริง)
    //       จะถือว่า "ไม่มีอะไรต้องออก" แล้วพากลับไปหน้าเดิมทันที ไม่ปล่อยให้ค้างเฉยๆ
    // ===========================================================================
    private val leaveWatchdog = object : Runnable {
        override fun run() {
            if (!forceLeaveRequested.get()) {
                leaveAttemptActive.set(false)
                return
            }

            // ครบเวลาแล้วยังไม่เจอปุ่มเลย → สรุปว่าไม่ได้อยู่ในห้องประชุมจริง (แค่หน้า join/home)
            // → เลิกค้นหา แล้วพากลับไปหน้าที่กด btn3 เข้ามาทันที
            if (System.currentTimeMillis() - leaveAttemptStartTime > LEAVE_SEARCH_TIMEOUT_MS) {
                forceLeaveRequested.set(false)
                leaveAttemptActive.set(false)
                returnToPreviousScreen()
                return
            }

            val root = rootInActiveWindow
            val pkg = root?.packageName?.toString()

            when {
                // ยังอยู่ในแอป Zoom → ลองค้นหาปุ่มต่อไป
                pkg == ZOOM_PACKAGE && root != null -> {
                    val clickedLeaveIcon = findAndClick(root, LEAVE_ICON_TEXTS)
                    val clickedConfirm = if (!clickedLeaveIcon) {
                        findAndClick(root, CONFIRM_TEXTS)
                    } else {
                        false
                    }

                    if (clickedConfirm) {
                        // กดยืนยันสำเร็จ = ออกจากห้องแน่นอนแล้ว จบขั้นตอนค้นหา
                        forceLeaveRequested.set(false)
                        leaveAttemptActive.set(false)
                        startPostLeaveGuard()
                        mainHandler.postDelayed({ returnToPreviousScreen() }, 150)
                        return
                    }
                    // ถ้ากดแค่ leave icon (ยังไม่เจอ confirm) หรือยังไม่เจอปุ่มเลย
                    // → ปล่อยให้ loop รอบถัดไปเช็คซ้ำ (รองรับทั้ง popup ที่มาช้า และ Zoom
                    //   บาง version ที่ไม่มี popup ยืนยัน ซึ่งจะหลุดออกจากแอปไปเองในรอบถัดไป)
                }

                // ออกจากแอป Zoom ไปแล้ว (ไม่ว่าจะเพราะกด leave icon สำเร็จโดยไม่มี popup
                // หรือแอปถูกปิดไปเอง) และไม่ใช่แอปของเราเอง → ถือว่าออกสำเร็จ
                pkg != null && pkg != ZOOM_PACKAGE && pkg != packageName -> {
                    forceLeaveRequested.set(false)
                    leaveAttemptActive.set(false)
                    startPostLeaveGuard()
                    returnToPreviousScreen()
                    return
                }
            }

            mainHandler.postDelayed(this, LEAVE_SEARCH_INTERVAL_MS)
        }
    }

    private fun startLeaveAttempt() {
        // กันไม่ให้ start ซ้อนกันหลายชุดถ้ามี event ยิงเข้ามาถี่ๆ
        if (leaveAttemptActive.getAndSet(true)) return
        leaveAttemptStartTime = System.currentTimeMillis()
        mainHandler.removeCallbacks(leaveWatchdog)
        mainHandler.post(leaveWatchdog)
    }

    // 📌 พากลับไปหน้าที่ผู้ใช้กด btn3 เข้ามา
    // หมายเหตุ: Android ไม่มี API สาธารณะให้ "สลับกลับไปแอปก่อนหน้าแบบเจาะจง" ได้ตรงๆ
    // จากใน AccessibilityService วิธีที่เสถียรที่สุดคือ BACK ก่อน (เผื่อยังมี task
    // ของแอปเดิมค้างอยู่ในลำดับ back stack) แล้วตามด้วย HOME เป็น fallback
    private fun returnToPreviousScreen() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        mainHandler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_HOME)
        }, 300)
    }

    // 📌 เริ่มโหมดเฝ้าระวัง: ตั้งเวลาสิ้นสุด แล้วเริ่มยิง Home ทันทีซ้ำไปเรื่อยๆ
    private fun startPostLeaveGuard() {
        postLeaveGuardActive.set(true)
        guardEndTimeMs = System.currentTimeMillis() + GUARD_DURATION_MS

        mainHandler.removeCallbacks(homeWatchdog)
        mainHandler.post(homeWatchdog)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString()

        // ระหว่างเฝ้าระวังอยู่ ถ้าเห็นแพ็กเกจแปลกปลอมโผล่มาระหว่าง event ก็สั่ง Home เสริมทันที
        if (postLeaveGuardActive.get()) {
            if (System.currentTimeMillis() > guardEndTimeMs) {
                postLeaveGuardActive.set(false)
            } else if (pkg != null && pkg != packageName && pkg != "com.android.systemui") {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }
        }

        // 📌 เมื่อกด btn3 ให้พยายามเปิดกล้อง + ไมค์ใน Zoom โดยใช้ Accessibility
        // จะคลิกเฉพาะปุ่มที่สื่อความหมายว่า "เปิด" เพื่อไม่สลับกลับเป็นปิดในรอบถัดไป
        val root = rootInActiveWindow
        if (autoEnableMediaRequested.get() && pkg == ZOOM_PACKAGE && root != null) {
            val cameraClicked = findAndClick(root, CAMERA_ON_TEXTS)
            val micClicked = findAndClick(root, MICROPHONE_ON_TEXTS)

            // เมื่อไม่พบปุ่มเปิดแล้ว ถือว่าขั้นตอนอัตโนมัติเสร็จ/ไม่มีปุ่มให้กด
            if (cameraClicked || micClicked) {
                // รอ event รอบถัดไป เผื่อ Zoom แสดงปุ่มเสียง/ภาพคนละจังหวะ
            } else {
                autoEnableMediaRequested.set(false)
            }
        }

        // ✅ จุดสำคัญ: event ที่นี่ทำหน้าที่แค่ "จุดชนวน" ให้เริ่ม watchdog loop เท่านั้น
        // ตัวการค้นหา-กดปุ่มจริงๆ ทำงานอยู่ใน leaveWatchdog ที่ทำงานต่อเนื่องเอง
        // ไม่ต้องพึ่ง event รอบถัดไปอีกต่อไป
        if (forceLeaveRequested.get()) {
            startLeaveAttempt()
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
        forceLeaveRequested.set(false)
        autoEnableMediaRequested.set(false)
        postLeaveGuardActive.set(false)
        leaveAttemptActive.set(false)
        mainHandler.removeCallbacks(homeWatchdog)
        mainHandler.removeCallbacks(leaveWatchdog)
    }

    override fun onDestroy() {
        super.onDestroy()
        forceLeaveRequested.set(false)
        autoEnableMediaRequested.set(false)
        mainHandler.removeCallbacks(homeWatchdog)
        mainHandler.removeCallbacks(leaveWatchdog)
    }
}