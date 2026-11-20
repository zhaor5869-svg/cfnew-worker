package com.laoda.proxy.ui.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 字节数格式化：B/KB/MB/GB/TB，非 B 单位保留 1 位小数。 */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) {
        "$bytes B"
    } else {
        String.format(Locale.getDefault(), "%.1f %s", value, units[index])
    }
}

/** 速率格式化：同 formatBytes + "/s"。 */
fun formatSpeed(bps: Long): String = "${formatBytes(bps)}/s"

/** 时间格式化："yyyy-MM-dd HH:mm"。 */
fun formatTime(millis: Long): String {
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(millis))
}
