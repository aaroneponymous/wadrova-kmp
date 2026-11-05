package com.example.wadrova.data

/**
 * A one-line diagnostic for developers (logcat on Android, the system log on iOS). Callers pass counts and broad
 * categories only: never image bytes, tokens, server addresses, ids of user content or file paths.
 */
internal expect fun diagnostic(message: String)
