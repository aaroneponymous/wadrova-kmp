package com.example.wadrova.data

import android.util.Log

internal actual fun diagnostic(message: String) {
    try {
        Log.i("Wadrova", message)
    } catch (_: RuntimeException) {
        // Host unit tests run against stubbed Android classes; a diagnostic is never worth failing over.
    }
}
