@file:OptIn(ExperimentalForeignApi::class)

package com.example.wadrova.data

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_OK
import platform.zlib.compress2
import platform.zlib.compressBound
import platform.zlib.uLongfVar
import platform.zlib.uncompress

internal actual fun zlibInflate(data: ByteArray, expectedSize: Int): ByteArray {
    if (data.isEmpty() || expectedSize <= 0) throw CutoutFormatException(CutoutProblem.INVALID_PNG, "The cutout's image data is corrupt.")
    // One spare byte: if it's used, the stream holds more than the header promises.
    val out = ByteArray(expectedSize + 1)
    val result = memScoped {
        val length = alloc<uLongfVar>().apply { value = out.size.convert() }
        val status = out.usePinned { dst ->
            data.usePinned { src ->
                uncompress(dst.addressOf(0).reinterpret(), length.ptr, src.addressOf(0).reinterpret(), data.size.convert())
            }
        }
        status to length.value.toLong()
    }
    if (result.first != Z_OK || result.second != expectedSize.toLong()) {
        throw CutoutFormatException(CutoutProblem.INVALID_PNG, "The cutout's image data has the wrong size.")
    }
    return out.copyOf(expectedSize)
}

/** One spare byte past [maxSize] tells "too large" apart from "fits": uncompress fails if it can't finish. */
internal actual fun zlibInflateAtMost(data: ByteArray, maxSize: Int): ByteArray {
    if (data.isEmpty()) throw CutoutFormatException(CutoutProblem.INVALID_PNG, "Compressed data is empty.")
    val out = ByteArray(maxSize + 1)
    val result = memScoped {
        val length = alloc<uLongfVar>().apply { value = out.size.convert() }
        val status = out.usePinned { dst ->
            data.usePinned { src ->
                uncompress(dst.addressOf(0).reinterpret(), length.ptr, src.addressOf(0).reinterpret(), data.size.convert())
            }
        }
        status to length.value.toLong()
    }
    if (result.first != Z_OK || result.second > maxSize) {
        throw CutoutFormatException(CutoutProblem.INVALID_PNG, "Compressed data is corrupt, truncated or too large.")
    }
    return out.copyOf(result.second.toInt())
}

internal actual fun zlibDeflate(data: ByteArray): ByteArray = memScoped {
    val bound = compressBound(data.size.convert()).toInt()
    val out = ByteArray(bound)
    val length = alloc<uLongfVar>().apply { value = bound.convert() }
    val status = out.usePinned { dst ->
        data.usePinned { src ->
            compress2(dst.addressOf(0).reinterpret(), length.ptr, src.addressOf(0).reinterpret(), data.size.convert(), Z_DEFAULT_COMPRESSION)
        }
    }
    check(status == Z_OK) { "Couldn't compress the cutout." }
    out.copyOf(length.value.toInt())
}
