package com.example.wadrova.data

import java.security.MessageDigest

actual fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
