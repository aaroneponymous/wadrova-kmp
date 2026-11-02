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
import platform.PhotosUI.PHPickerConfigurationSelectionOrdered
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
        result.itemProvider.loadFileRepresentationForTypeIdentifier(UTTypeImage.identifier) { url, _ ->
            val copied = url?.let(::copyToTemporaryFile)
            dispatch_async(dispatch_get_main_queue()) {
                onResult(if (copied == null) PhotoPickResult.Failed else PhotoPickResult.Picked(PickedPhoto(copied)))
            }
        }
    }
}

private class MultiPickerDelegate(private val onResult: (PhotoBatch) -> Unit) :
    NSObject(), PHPickerViewControllerDelegateProtocol {

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, null)
        val results = didFinishPicking.filterIsInstance<PHPickerResult>()
        if (results.isEmpty()) {
            onResult(PhotoBatch(emptyList()))
            return
        }
        // Providers finish in any order, so each copy goes back to its selected position. Only the main queue touches these.
        val copies = arrayOfNulls<String>(results.size)
        var remaining = results.size
        results.forEachIndexed { index, result ->
            result.itemProvider.loadFileRepresentationForTypeIdentifier(UTTypeImage.identifier) { url, _ ->
                val copied = url?.let(::copyToTemporaryFile)
                dispatch_async(dispatch_get_main_queue()) {
                    copies[index] = copied
                    if (--remaining == 0) {
                        val photos = copies.filterNotNull().map(::PickedPhoto)
                        onResult(PhotoBatch(photos, failed = results.size - photos.size))
                    }
                }
            }
        }
    }
}

/** The provider's file exists only during its callback, so it's copied before returning. */
@OptIn(ExperimentalUuidApi::class)
private fun copyToTemporaryFile(url: NSURL): String? {
    val destination = NSTemporaryDirectory() + "picked-" + Uuid.random() + "." + (url.pathExtension ?: "img")
    val copied = NSFileManager.defaultManager.copyItemAtPath(url.path ?: return null, destination, null)
    return destination.takeIf { copied }
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

@Composable
actual fun rememberMultiPhotoPicker(maxItems: Int, onResult: (PhotoBatch) -> Unit): () -> Unit {
    val currentOnResult = rememberUpdatedState(onResult)
    // UIKit holds the delegate weakly, so the composition keeps it alive.
    val delegate = remember { MultiPickerDelegate { currentOnResult.value(it) } }
    return {
        val configuration = PHPickerConfiguration().apply {
            filter = PHPickerFilter.imagesFilter
            selectionLimit = maxItems.toLong()
            // Numbers the selections, and returns them in that order.
            selection = PHPickerConfigurationSelectionOrdered
        }
        val picker = PHPickerViewController(configuration)
        picker.delegate = delegate
        topViewController()?.presentViewController(picker, animated = true, completion = null)
            ?: onResult(PhotoBatch(emptyList(), failed = 1))
    }
}

private fun topViewController(): UIViewController? {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (top?.presentedViewController != null) top = top.presentedViewController
    return top
}
