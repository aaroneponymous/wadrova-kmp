package com.example.wadrova.data

import platform.UIKit.UIApplication
import platform.UIKit.UIBackgroundTaskInvalid

/** Wraps the block in a UIKit background task. When the time runs out the task just ends; the upload is resent later. */
object IosBackgroundGrace : BackgroundGrace {
    override suspend fun <T> run(block: suspend () -> T): T {
        val application = UIApplication.sharedApplication
        var task = UIBackgroundTaskInvalid
        task = application.beginBackgroundTaskWithName("wadrova-upload") {
            application.endBackgroundTask(task)
            task = UIBackgroundTaskInvalid
        }
        try {
            return block()
        } finally {
            if (task != UIBackgroundTaskInvalid) application.endBackgroundTask(task)
        }
    }
}
