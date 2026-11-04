package com.example.wadrova.data

/** Lowercase hex SHA-256, from the platform's own implementation. */
expect fun sha256Hex(bytes: ByteArray): String

internal fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
