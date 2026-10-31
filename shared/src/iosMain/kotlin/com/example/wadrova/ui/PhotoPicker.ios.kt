@file:OptIn(ExperimentalForeignApi::class)

package com.example.wadrova.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import com.example.wadrova.data.PickedPhoto
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.UniformTypeIdentifiers.UTTypeImage
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private class PickerDelegate(private val onResult: (PhotoPickResult) -> Unit) :
    NSObject(), PHPickerViewControllerDelegateProtocol {

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult
        if (result == null) {
            onResult(PhotoPickResult.Cancelled)
            return
        }
        // The provider's file exists only during this callback, so it's copied before returning.
        result.itemProvider.loadFileRepresentationForTypeIdentifier(UTTypeImage.identifier) { url, _ ->
            val copied = url?.let(::copyToTemporaryFile)
            dispatch_async(dispatch_get_main_queue()) {
                onResult(if (copied == null) PhotoPickResult.Failed else PhotoPickResult.Picked(PickedPhoto(copied)))
            }
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun copyToTemporaryFile(url: NSURL): String? {
        val destination = NSTemporaryDirectory() + "picked-" + Uuid.random() + "." + (url.pathExtension ?: "img")
        val copied = NSFileManager.defaultManager.copyItemAtPath(url.path ?: return null, destination, null)
        return destination.takeIf { copied }
    }
}

@Composable
actual fun rememberPhotoPicker(onResult: (PhotoPickResult) -> Unit): () -> Unit {
    val currentOnResult = rememberUpdatedState(onResult)
    // UIKit holds the delegate weakly, so the composition keeps it alive.
    val delegate = remember { PickerDelegate { currentOnResult.value(it) } }
    return {
        val configuration = PHPickerConfiguration().apply {
            filter = PHPickerFilter.imagesFilter
            selectionLimit = 1
        }
        val picker = PHPickerViewController(configuration)
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)
            ?: onResult(PhotoPickResult.Failed)
    }
}

private fun topViewController(): UIViewController? {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}
