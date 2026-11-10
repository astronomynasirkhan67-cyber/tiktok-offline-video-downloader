package com.nasir.videosaver

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicInteger

// Runs with shell privileges through Shizuku, so it can read Android/data.
class FileService() : IFileService.Stub() {

    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : this()

    @Volatile private var cancelled = false
    private val seen = AtomicInteger()
    private val found = AtomicInteger()

    private val pkgs = setOf(
        "com.zhiliaoapp.musically",
        "com.zhiliaoapp.musically.go",
        "com.ss.android.ugc.trill",
        "com.ss.android.ugc.aweme"
    )
    private val keys = listOf("tiktok", "musically", "trill", "aweme")

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

    private fun walk(d: File, inTikTok: Boolean, depth: Int, out: ArrayList<String>) {
        if (cancelled || depth > 25 || out.size >= 2000) return
        val fs = d.listFiles() ?: return
        for (f in fs) {
            if (cancelled || out.size >= 2000) return
            if (f.isDirectory) {
                val n = f.name.lowercase()
                val parent = f.parentFile?.absolutePath ?: ""
                if (f.absolutePath.endsWith("/Android/obb")) continue
                // Inside Android/data and Android/media, only visit TikTok apps.
                if ((parent.endsWith("/Android/data") || parent.endsWith("/Android/media")) && n !in pkgs) continue
                val tt = inTikTok || n in pkgs || keys.any { n.contains(it) }
                walk(f, tt, depth + 1, out)
            } else {
                seen.incrementAndGet()
                if (inTikTok && f.length() >= 150_000L && isMp4(f)) {
                    out.add(f.absolutePath + "\t" + f.length() + "\t" + f.lastModified())
                    found.incrementAndGet()
                }
            }
        }
    }

    override fun scanAll(): Array<String> {
        cancelled = false
        seen.set(0)
        found.set(0)
        val out = ArrayList<String>()
        walk(File("/storage/emulated/0"), false, 0, out)
        File("/storage").listFiles()?.forEach {
            if (it.isDirectory && it.name != "emulated" && it.name != "self") walk(it, false, 0, out)
        }
        return out.toTypedArray()
    }

    override fun progress(): String = seen.get().toString() + "|" + found.get()

    override fun cancel() {
        cancelled = true
    }

    override fun open(path: String?): ParcelFileDescriptor? = try {
        ParcelFileDescriptor.open(File(path!!), ParcelFileDescriptor.MODE_READ_ONLY)
    } catch (e: Exception) { null }

    override fun info(root: String?): String {
        val d = File(root ?: "")
        if (!d.exists()) return root + "\n  Folder not found\n\n"
        val all = ArrayList<File>()
        fun collect(x: File) {
            val fs = x.listFiles() ?: return
            for (f in fs) {
                if (all.size >= 20000) return
                if (f.isDirectory) collect(f) else all.add(f)
            }
        }
        collect(d)
        val total = all.sumOf { it.length() }
        val sb = StringBuilder()
        sb.append(root).append("\n  ").append(all.size).append(" files, ")
            .append(total / 1048576).append(" MB\n")
        for (f in all.sortedByDescending { it.length() }.take(5)) {
            val hex = try {
                FileInputStream(f).use {
                    val b = ByteArray(8)
                    val n = it.read(b)
                    b.take(maxOf(n, 0)).joinToString(" ") { x -> "%02x".format(x) }
                }
            } catch (e: Exception) { "?" }
            sb.append("  ").append(f.name).append(" (").append(f.length() / 1024)
                .append(" KB) ").append(hex).append("\n")
        }
        return sb.append("\n").toString()
    }
}
