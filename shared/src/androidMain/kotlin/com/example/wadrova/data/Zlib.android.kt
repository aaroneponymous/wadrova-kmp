package com.example.wadrova.data

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

internal actual fun zlibInflate(data: ByteArray, expectedSize: Int): ByteArray {
    val inflater = Inflater()
    try {
        inflater.setInput(data)
        val out = ByteArray(expectedSize)
        var written = 0
        while (written < expectedSize) {
            val n = inflater.inflate(out, written, expectedSize - written)
            if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break
            written += n
        }
        // More data than the header promises is as wrong as less.
        val extra = ByteArray(1)
        if (written != expectedSize || (!inflater.finished() && inflater.inflate(extra) > 0)) {
            throw CutoutFormatException("The cutout's image data has the wrong size.")
        }
        return out
    } finally {
        inflater.end()
    }
}

internal actual fun zlibDeflate(data: ByteArray): ByteArray {
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
    try {
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 64)
        val buffer = ByteArray(64 * 1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}
