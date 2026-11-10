package com.nasir.videosaver

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.documentfile.provider.DocumentFile
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {

    class Vid(val key: String, val name: String, val size: Long, val modified: Long, val uri: Uri?, val path: String?)
    class Holder(val cb: CheckBox, val iv: ImageView, val name: TextView, val size: TextView, val play: Button)

    private val ROOTS = listOf(
        "/storage/emulated/0/Android/data/com.zhiliaoapp.musically",
        "/storage/emulated/0/Android/data/com.ss.android.ugc.trill",
        "/storage/emulated/0/Android/data/com.zhiliaoapp.musically.go"
    )

    private val videos = ArrayList<Vid>()
    private val selected = HashSet<String>()
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val thumbs = LruCache<String, Bitmap>(80)
    private lateinit var status: TextView
    private lateinit var saveBtn: Button
    private lateinit var adapter: BaseAdapter
    @Volatile private var busy = false
    @Volatile private var svc: IFileService? = null
    private var pending: (() -> Unit)? = null

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        val a = pending
        pending = null
        if (result == PackageManager.PERMISSION_GRANTED && a != null) withShizuku(a)
        else toast("Shizuku की अनुमति नहीं मिली")
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName?, b: IBinder?) {
            svc = IFileService.Stub.asInterface(b)
            val a = pending
            pending = null
            a?.invoke()
        }
        override fun onServiceDisconnected(n: ComponentName?) { svc = null }
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Shizuku.addRequestPermissionResultListener(permListener)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(36), dp(12), dp(8))
        }
        root.addView(TextView(this).apply {
            text = "Video Saver"; textSize = 22f; setTextColor(Color.parseColor("#7C3AED"))
        })
        fun btn(t: String, c: () -> Unit) = Button(this).apply {
            text = t; textSize = 12f; isAllCaps = false
            setOnClickListener { c() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("Shizuku से\nस्कैन करें") { withShizuku { scanShizuku() } })
        row.addView(btn("फ़ोल्डर\nचुनें") { pickFolder() })
        row.addView(btn("जाँच") { withShizuku { showInfo() } })
        root.addView(row)
        status = TextView(this).apply {
            text = "Shizuku चालू करके \"Shizuku से स्कैन करें\" दबाएँ"
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(status)

        adapter = object : BaseAdapter() {
            override fun getCount() = videos.size
            override fun getItem(p: Int) = videos[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val v = videos[p]
                val h: Holder
                val view: View
                if (cv == null) {
                    val cb = CheckBox(this@MainActivity)
                    val iv = ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(Color.LTGRAY)
                        layoutParams = LinearLayout.LayoutParams(dp(68), dp(90)).apply { setMargins(dp(4), dp(4), dp(8), dp(4)) }
                    }
                    val name = TextView(this@MainActivity).apply { setTextColor(Color.BLACK); maxLines = 1 }
                    val size = TextView(this@MainActivity)
                    val col = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        addView(name); addView(size)
                    }
                    val play = Button(this@MainActivity).apply { text = "▶"; minWidth = 0; minimumWidth = dp(48) }
                    val r = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(cb); addView(iv); addView(col); addView(play)
                    }
                    h = Holder(cb, iv, name, size, play)
                    r.tag = h
                    view = r
                } else {
                    view = cv
                    h = cv.tag as Holder
                }
                h.name.text = v.name
                h.size.text = String.format("%.1f MB", v.size / 1048576.0)
                h.cb.setOnCheckedChangeListener(null)
                h.cb.isChecked = selected.contains(v.key)
                h.cb.setOnCheckedChangeListener { _, on ->
                    if (on) selected.add(v.key) else selected.remove(v.key)
                    refreshSave()
                }
                view.setOnClickListener { h.cb.toggle() }
                h.play.setOnClickListener { play(v) }
                h.iv.tag = v.key
                val c = thumbs.get(v.key)
                if (c != null) h.iv.setImageBitmap(c) else {
                    h.iv.setImageDrawable(null)
                    pool.execute {
                        val bm = thumb(v)
                        if (bm != null) {
                            thumbs.put(v.key, bm)
                            ui.post { if (h.iv.tag == v.key) h.iv.setImageBitmap(bm) }
                        }
                    }
                }
                return view
            }
        }
        root.addView(ListView(this).apply {
            adapter = this@MainActivity.adapter
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })
        saveBtn = Button(this).apply {
            text = "गैलरी में सेव करें (0)"; isEnabled = false
            setOnClickListener { saveSelected() }
        }
        root.addView(saveBtn)
        setContentView(root)
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permListener)
    }

    // ---------- Shizuku ----------
    private fun withShizuku(action: () -> Unit) {
        try {
            if (!Shizuku.pingBinder()) {
                toast("Shizuku चालू नहीं है। Shizuku ऐप खोलकर Start दबाएँ, फिर वापस आएँ।")
                packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { startActivity(it) }
                return
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                pending = action
                Shizuku.requestPermission(100)
                return
            }
            if (svc != null) { action(); return }
            pending = action
            val args = Shizuku.UserServiceArgs(ComponentName(packageName, FileService::class.java.name))
                .processNameSuffix("fs").daemon(false).version(1)
            Shizuku.bindUserService(args, conn)
        } catch (e: Throwable) {
            toast("Shizuku एरर: ${e.message}")
        }
    }

    private fun resetList() {
        videos.clear(); selected.clear(); adapter.notifyDataSetChanged(); refreshSave()
    }

    private fun scanShizuku() {
        val s = svc ?: return
        if (busy) return
        busy = true
        resetList()
        status.text = "Shizuku से स्कैन हो रहा है..."
        Thread {
            val found = ArrayList<Vid>()
            var diag = ""
            try {
                for (r in ROOTS) {
                    for (line in s.scan(r)) {
                        val p = line.split("\t")
                        if (p.size == 3) found.add(Vid(p[0], File(p[0]).name, p[1].toLong(), p[2].toLong(), null, p[0]))
                    }
                }
                if (found.isEmpty()) for (r in ROOTS) diag += s.info(r)
            } catch (e: Exception) { diag = "एरर: ${e.message}" }
            finishScan(found, diag)
        }.start()
    }

    private fun showInfo() {
        val s = svc ?: return
        status.text = "जाँच हो रही है..."
        Thread {
            var t = ""
            try { for (r in ROOTS) t += s.info(r) } catch (e: Exception) { t = "एरर: ${e.message}" }
            ui.post {
                status.text = "जाँच पूरी"
                AlertDialog.Builder(this).setTitle("फ़ोल्डर जाँच").setMessage(t).setPositiveButton("ठीक है", null).show()
            }
        }.start()
    }

    // ---------- SAF (वैकल्पिक) ----------
    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fdata%2Fcom.zhiliaoapp.musically")
        )
        try { startActivityForResult(i, 1) } catch (e: Exception) { toast("फ़ोल्डर पिकर नहीं खुला") }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val tree = data?.data
        if (res == RESULT_OK && tree != null) scanSaf(tree)
    }

    private fun scanSaf(tree: Uri) {
        if (busy) return
        busy = true
        resetList()
        status.text = "स्कैन हो रहा है..."
        Thread {
            val found = ArrayList<Vid>()
            try {
                val root = DocumentFile.fromTreeUri(this, tree)
                if (root != null) walk(root, found)
            } catch (e: Exception) { }
            finishScan(found, "")
        }.start()
    }

    private fun walk(dir: DocumentFile, out: ArrayList<Vid>) {
        for (f in dir.listFiles()) {
            if (f.isDirectory) { walk(f, out); continue }
            if (f.length() < 150_000L) continue
            if (isMp4(f.uri)) out.add(Vid(f.uri.toString(), f.name ?: "video", f.length(), f.lastModified(), f.uri, null))
        }
    }

    private fun isMp4(u: Uri): Boolean = try {
        contentResolver.openInputStream(u)?.use {
            val b = ByteArray(12)
            val n = it.read(b)
            n >= 12 && b[4] == 'f'.code.toByte() && b[5] == 't'.code.toByte() &&
                b[6] == 'y'.code.toByte() && b[7] == 'p'.code.toByte()
        } ?: false
    } catch (e: Exception) { false }

    private fun finishScan(found: ArrayList<Vid>, diag: String) {
        found.sortByDescending { it.modified }
        ui.post {
            videos.addAll(found)
            busy = false
            adapter.notifyDataSetChanged()
            refreshSave()
            if (found.isEmpty()) {
                status.text = "कोई वीडियो नहीं मिला"
                if (diag.isNotEmpty())
                    AlertDialog.Builder(this).setTitle("कोई वीडियो नहीं मिला").setMessage(diag)
                        .setPositiveButton("ठीक है", null).show()
            } else status.text = "${found.size} वीडियो मिले। जो पसंद हो उसे चुनें।"
        }
    }

    // ---------- फ़ाइल पढ़ना, थंबनेल, प्ले, सेव ----------
    private fun openStream(v: Vid): InputStream? =
        if (v.uri != null) contentResolver.openInputStream(v.uri)
        else svc?.open(v.path)?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }

    private fun thumb(v: Vid): Bitmap? = try {
        val r = MediaMetadataRetriever()
        var pfd: ParcelFileDescriptor? = null
        if (v.uri != null) r.setDataSource(this, v.uri) else {
            pfd = svc?.open(v.path) ?: return null
            r.setDataSource(pfd.fileDescriptor)
        }
        val bm = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        r.release()
        pfd?.close()
        bm?.let { Bitmap.createScaledBitmap(it, 90, 120, true) }
    } catch (e: Exception) { null }

    private fun play(v: Vid) {
        toast("लोड हो रहा है...")
        Thread {
            var path: String? = null
            if (v.uri == null) {
                try {
                    val f = File(cacheDir, "play.mp4")
                    openStream(v)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    path = f.absolutePath
                } catch (e: Exception) { }
            }
            ui.post {
                if (v.uri == null && path == null) { toast("वीडियो नहीं खुला"); return@post }
                val vv = VideoView(this)
                val mc = MediaController(this)
                mc.setAnchorView(vv)
                vv.setMediaController(mc)
                if (path != null) vv.setVideoPath(path) else vv.setVideoURI(v.uri)
                val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                d.setContentView(vv, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
                vv.setOnPreparedListener { it.start() }
                d.setOnDismissListener { vv.stopPlayback() }
                d.show()
            }
        }.start()
    }

    private fun refreshSave() {
        saveBtn.text = "गैलरी में सेव करें (${selected.size})"
        saveBtn.isEnabled = selected.isNotEmpty() && !busy
    }

    private fun saveSelected() {
        if (busy) return
        busy = true
        refreshSave()
        val todo = videos.filter { selected.contains(it.key) }
        Thread {
            var ok = 0
            todo.forEachIndexed { i, v ->
                ui.post { status.text = "सेव हो रहा है ${i + 1}/${todo.size}..." }
                if (saveToGallery(v)) ok++
            }
            ui.post {
                busy = false
                selected.clear()
                adapter.notifyDataSetChanged()
                refreshSave()
                status.text = "$ok/${todo.size} वीडियो गैलरी में सेव हुए (Movies/Video Saver)"
            }
        }.start()
    }

    private fun saveToGallery(v: Vid): Boolean {
        try {
            val base = v.name.substringBeforeLast('.').take(40).replace(Regex("[^A-Za-z0-9_-]"), "_")
            val name = "VS_${base}_${v.key.hashCode() and 0xffff}.mp4"
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Video Saver")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val out = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
            contentResolver.openOutputStream(out)!!.use { o ->
                openStream(v)!!.use { it.copyTo(o) }
            }
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            contentResolver.update(out, done, null, null)
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
