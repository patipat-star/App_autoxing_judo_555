package com.example.pcoverlaycontrol

import android.content.Context
import androidx.core.content.edit
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/** ที่อยู่ของ Raspberry Pi 5 ที่เจ้าของเปลี่ยนได้ (เก็บใน SharedPreferences) */
class PiSettings(context: Context) {

    companion object {
        const val DEFAULT_HOST = "192.168.100.245"
        private const val HTTP_PORT = 5000
        private const val WS_PORT = 8765
        private const val PREFS_NAME = "pi_settings"
        private const val KEY_HOST = "pi_host"
        private const val KEY_PIN_HASH = "owner_pin_hash"
        private const val PIN_SALT = "pcoverlaycontrol:"

        // ต้องเป็นชื่อโฮสต์/IP ล้วนๆ ไม่มี path, user@, หรือพอร์ตแถมมา
        fun isValidHost(candidate: String): Boolean {
            if (candidate.isEmpty()) return false
            val url = "http://$candidate:$HTTP_PORT/".toHttpUrlOrNull() ?: return false
            return url.host.equals(candidate, ignoreCase = true) && url.encodedPath == "/"
        }

        fun baseUrlFor(host: String) = "http://$host:$HTTP_PORT"

        // PIN เจ้าของ: ตัวเลข 4-8 หลัก
        fun isValidPin(candidate: String): Boolean =
            candidate.length in 4..8 && candidate.all { it in '0'..'9' }

        fun hashPin(pin: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest((PIN_SALT + pin).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, null) ?: DEFAULT_HOST
        set(value) = prefs.edit { putString(KEY_HOST, value) }

    val hasPin: Boolean get() = prefs.getString(KEY_PIN_HASH, null) != null

    fun checkPin(pin: String): Boolean =
        prefs.getString(KEY_PIN_HASH, null) == hashPin(pin)

    fun setPin(pin: String) {
        prefs.edit { putString(KEY_PIN_HASH, hashPin(pin)) }
    }

    val baseUrl: String get() = baseUrlFor(host)
    val webSocketUrl: String get() = "ws://$host:$WS_PORT"
}
