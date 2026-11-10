package com.nasir.videosaver

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream

// यह सर्विस Shizuku के ज़रिए shell अधिकार के साथ चलती है,
// इसलिए Android/data के अंदर की फ़ाइलें पढ़ सकती है।
class FileService() : IFileService.Stub() {

    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    override fun destroy() {
        System.exit(0)
    }

    private fun isMp4(f: File): Boolean = try {
        FileInputStream(f).use {
            val b = ByteArray(12)
            val n = it.read(b)
            n >= 12 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() &&
                b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()
        }
    } catch (e: Exception) { false }

    override fun scan(root: String?): Array<String> {
        val out = ArrayList<String>()
        fun walk(d: File) {
            val fs = d.listFiles() ?: return
            for (f in fs) {
                if (out.size >= 3000) return
                if (f.isDirectory) walk(f)
                else if (f.length() >= 150_000L && isMp4(f))
                    out.add(f.absolutePath + "\t" + f.length() + "\t" + f.lastModified())
            }
        }
        if (root != null) walk(File(root))
        return out.toTypedArray()
    }

    override fun open(path: String?): ParcelFileDescriptor? = try {
        ParcelFileDescriptor.open(File(path!!), ParcelFileDescriptor.MODE_READ_ONLY)
    } catch (e: Exception) { null }

    override fun info(root: String?): String {
        val d = File(root ?: "")
        if (!d.exists()) return root + "\n  फ़ोल्डर नहीं मिला\n\n"
        val all = ArrayList<File>()
        fun walk(x: File) {
            val fs = x.listFiles() ?: return
            for (f in fs) {
                if (all.size >= 20000) return
                if (f.isDirectory) walk(f) else all.add(f)
            }
        }
        walk(d)
        val total = all.sumOf { it.length() }
        val sb = StringBuilder()
        sb.append(root).append("\n  ").append(all.size).append(" फ़ाइलें, ")
            .append(total / 1048576).append(" MB\n")
        for (f in all.sortedByDescending { it.length() }.take(5)) {
            val hex = try {
                FileInputStream(f).use {
                    val b = ByteArray(8); val n = it.read(b)
                    b.take(maxOf(n, 0)).joinToString(" ") { x -> "%02x".format(x) }
                }
            } catch (e: Exception) { "?" }
            sb.append("  ").append(f.name).append(" (").append(f.length() / 1024)
                .append(" KB) ").append(hex).append("\n")
        }
        return sb.append("\n").toString()
    }
}
