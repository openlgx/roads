package org.openlgx.roads.upload

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.math.min

object FileChecksums {
    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    /** SHA-256 of [length] bytes starting at [offset] (for multipart upload parts). */
    fun sha256Hex(file: File, offset: Long, length: Long): String {
        require(offset >= 0 && length >= 0) { "offset and length must be non-negative" }
        require(offset + length <= file.length()) { "range past end of file" }
        val md = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            var remaining = length
            val buf = ByteArray(8192)
            while (remaining > 0) {
                val toRead = min(buf.size.toLong(), remaining).toInt()
                val n = raf.read(buf, 0, toRead)
                if (n <= 0) break
                md.update(buf, 0, n)
                remaining -= n.toLong()
            }
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }
}
