package com.example.pcoverlaycontrol

/**
 * ตรวจว่า [value] มี [target] อยู่หรือไม่
 * - ข้อความ ASCII (อังกฤษ) ต้องตรงเป็น "คำ" กัน "End" ไปตรงกับ Calendar / Send / Attendees
 * - ข้อความที่มีอักขระนอก ASCII (ไทย) ไม่มีช่องว่างคั่นคำ จึงใช้ contains
 */
fun matchesTarget(value: String?, target: String): Boolean {
    if (value.isNullOrBlank() || target.isEmpty()) return false
    return if (target.all { it.code < 128 }) {
        Regex("""\b${Regex.escape(target)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(value)
    } else {
        value.contains(target)
    }
}
