package com.example.wadrova.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Makes the generated-image sweep ([MirrorRepository.removeUnreferencedImages]) and a save that writes generated files
 * take turns. Without it the sweep could list a file written just before the transaction that links it, see it as
 * unreferenced and delete it. Both must share one instance; [createLocalRepositories] wires that up.
 */
class GeneratedImageGuard {
    private val mutex = Mutex()

    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
}
