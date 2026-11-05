package com.example.wadrova.data

import platform.Foundation.NSLog

internal actual fun diagnostic(message: String) {
    // No varargs: Kotlin strings can't be passed through NSLog's variadic arguments. Escaping % keeps the message
    // from being read as a format string.
    NSLog("Wadrova: " + message.replace("%", "%%"))
}
