package com.example.wadrova.data

/**
 * Asks the platform to let a short task finish if the app leaves the foreground. iOS gives about 30 seconds and
 * then suspends the app; Android keeps the process while it can. Neither survives a force-quit, which is why
 * interrupted uploads are resent on the next launch.
 */
interface BackgroundGrace {
    suspend fun <T> run(block: suspend () -> T): T
}

/** For platforms and tests that need nothing extra. */
object NoBackgroundGrace : BackgroundGrace {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}
