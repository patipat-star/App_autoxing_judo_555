package com.example.pcoverlaycontrol

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** ที่อยู่ของ Raspberry Pi 5 ที่เจ้าของเปลี่ยนได้ (เก็บใน SharedPreferences) */
class PiSettings(context: Context) {

    companion object {
        const val DEFAULT_HOST = "192.168.100.245"
        private const val HTTP_PORT = 5000
        private const val WS_PORT = 8765
        private const val PREFS_NAME = "pi_settings"
        private const val KEY_HOST = "pi_host"

        // ต้องเป็นชื่อโฮสต์/IP ล้วนๆ ไม่มี path, user@, หรือพอร์ตแถมมา
        fun isValidHost(candidate: String): Boolean {
            if (candidate.isEmpty()) return false
            val url = "http://$candidate:$HTTP_PORT/".toHttpUrlOrNull() ?: return false
            return url.host.equals(candidate, ignoreCase = true) && url.encodedPath == "/"
        }

        fun baseUrlFor(host: String) = "http://$host:$HTTP_PORT"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, null) ?: DEFAULT_HOST
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    val baseUrl: String get() = baseUrlFor(host)
    val webSocketUrl: String get() = "ws://$host:$WS_PORT"
}
