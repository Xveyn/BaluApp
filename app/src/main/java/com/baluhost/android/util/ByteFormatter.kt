package com.baluhost.android.util

enum class ByteUnitMode { BINARY, DECIMAL }

object ByteFormatter {
    var mode: ByteUnitMode = ByteUnitMode.BINARY

    fun format(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val divisor = if (mode == ByteUnitMode.BINARY) 1024.0 else 1000.0
        val units = if (mode == ByteUnitMode.BINARY)
            arrayOf("B", "KiB", "MiB", "GiB", "TiB")
        else
            arrayOf("B", "KB", "MB", "GB", "TB")

        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= divisor && unitIndex < units.size - 1) {
            size /= divisor
            unitIndex++
        }
        return if (unitIndex == 0) "$bytes B"
        else "%.1f %s".format(size, units[unitIndex])
    }
}
