package com.nasir.videosaver

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.*
import androidx.documentfile.provider.DocumentFile
import rikka.shizuku.Shizuku
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    class Vid(val key: String, val name: String, val size: Long, val modified: Long, val uri: Uri?, val path: String?)
    class Holder(
        val card: LinearLayout, val cb: CheckBox, val iv: ImageView,
        val name: TextView, val meta: TextView, val folder: TextView, val play: TextView
    )

    private val cPrimary = Color.parseColor("#6D28D9")
    private val cBg = Color.parseColor("#F4F2FA")
    private val cText = Color.parseColor("#1F1B2E")
    private val cSub = Color.parseColor("#6B6880")
    private val cSel = Color.parseColor("#EDE9FE")

    private val roots = listOf(
        "/storage/emulated/0/Android/data/com.zhiliaoapp.musically",
        "/storage/emulated/0/Android/data/com.ss.android.ugc.trill",
        "/storage/emulated/0/Android/data/com.zhiliaoapp.musically.go"
    )

    private val videos = ArrayList<Vid>()
    private val selected = HashSet<String>()
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val thumbs = LruCache<String, Bitmap>(80)
    private val dateFmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    private lateinit var scanBtn: Button
    private lateinit var saveBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var statusTitle: TextView
    private lateinit var statusSub: TextView
    private lateinit var progress: ProgressBar
    private lateinit var adapter: BaseAdapter

    @Volatile private var busy = false
    @Volatile private var scanning = false
    @Volatile private var svc: IFileService? = null
    private var pending: (() -> Unit)? = null

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        val a = pending
        pending = null
        if (result == PackageManager.PERMISSION_GRANTED && a != null) withShizuku(a)
        else toast("Shizuku permission was not granted")
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

    // ---------- UI helpers ----------
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun shape(color: Int, r: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

    private fun tv(t: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = sp; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun styleBtn(b: Button, primary: Boolean) {
        b.isAllCaps = false
        b.typeface = Typeface.DEFAULT_BOLD
        b.textSize = 14f
        b.stateListAnimator = null
        if (primary) {
            b.background = shape(cPrimary, 12)
            b.setTextColor(Color.WHITE)
        } else {
            b.background = shape(Color.WHITE, 12, 1, Color.parseColor("#CBC3E3"))
            b.setTextColor(cPrimary)
        }
    }

    private fun setStatus(t: String, s: String) { statusTitle.text = t; statusSub.text = s }

    private fun showProgress(on: Boolean, max: Int = 0, value: Int = 0) {
        if (!on) { progress.visibility = View.GONE; return }
        progress.visibility = View.VISIBLE
        if (max == 0) progress.isIndeterminate = true else {
            progress.isIndeterminate = false
            progress.max = max
            progress.progress = value
        }
    }

    private fun applySel(h: Holder, on: Boolean) {
        h.card.background = if (on) shape(cSel, 14, 2, cPrimary)
        else shape(Color.WHITE, 14, 1, Color.parseColor("#E5E1F2"))
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = cPrimary
        Shizuku.addRequestPermissionResultListener(permListener)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBg) }

        // Header
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cPrimary)
            setPadding(dp(20), dp(16), dp(20), dp(18))
            elevation = dp(4).toFloat()
        }
        header.addView(tv("Video Saver", 22f, Color.WHITE, true))
        header.addView(tv("Find and save your TikTok videos", 13f, Color.parseColor("#DDD6FE")))
        root.addView(header)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        // Action buttons
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun actionBtn(t: String, primary: Boolean, w: Float, c: () -> Unit) = Button(this).apply {
            text = t
            styleBtn(this, primary)
            textSize = 13f
            maxLines = 1
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { c() }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), w).apply { setMargins(dp(3), 0, dp(3), 0) }
        }
        scanBtn = actionBtn("Scan Phone", true, 1.3f) { onScanClicked() }
        actions.addView(scanBtn)
        actions.addView(actionBtn("Pick Folder", false, 1f) { pickFolder() })
        actions.addView(actionBtn("Diagnostics", false, 1f) { withShizuku { showInfo() } })
        content.addView(actions)

        // Status card
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(Color.WHITE, 16, 1, Color.parseColor("#E5E1F2"))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12); bottomMargin = dp(8); leftMargin = dp(3); rightMargin = dp(3) }
        }
        statusTitle = tv("Ready", 16f, cText, true)
        statusSub = tv("Tap “Scan Phone” to search your whole device for TikTok videos.", 13f, cSub)
        statusSub.setPadding(0, dp(2), 0, 0)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            progressTintList = ColorStateList.valueOf(cPrimary)
            indeterminateTintList = ColorStateList.valueOf(cPrimary)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10) }
        }
        card.addView(statusTitle); card.addView(statusSub); card.addView(progress)
        content.addView(card)

        // List
        adapter = object : BaseAdapter() {
            override fun getCount() = videos.size
            override fun getItem(p: Int) = videos[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val v = videos[p]
                val h: Holder
                val view: View
                if (cv == null) {
                    val cb = CheckBox(this@MainActivity).apply { buttonTintList = ColorStateList.valueOf(cPrimary) }
                    val iv = ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = shape(Color.parseColor("#DDD6FE"), 10)
                        outlineProvider = ViewOutlineProvider.BACKGROUND
                        clipToOutline = true
                        layoutParams = LinearLayout.LayoutParams(dp(68), dp(90)).apply { setMargins(dp(2), 0, dp(12), 0) }
                    }
                    val name = tv("", 14f, cText, true).apply {
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                    }
                    val meta = tv("", 12f, cSub)
                    val folder = tv("", 11f, Color.parseColor("#9B97B0")).apply {
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    }
                    val col = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        addView(name); addView(meta); addView(folder)
                    }
                    val play = tv("▶", 14f, Color.WHITE, true).apply {
                        gravity = Gravity.CENTER
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cPrimary) }
                        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { leftMargin = dp(8) }
                    }
                    val inner = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(8), dp(8), dp(10), dp(8))
                        addView(cb); addView(iv); addView(col); addView(play)
                    }
                    val outer = FrameLayout(this@MainActivity).apply {
                        setPadding(dp(3), dp(4), dp(3), dp(4))
                        addView(inner, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    }
                    h = Holder(inner, cb, iv, name, meta, folder, play)
                    outer.tag = h
                    view = outer
                } else {
                    view = cv
                    h = cv.tag as Holder
                }
                h.name.text = v.name
                h.meta.text = String.format(Locale.US, "%.1f MB  ·  %s", v.size / 1048576.0, dateFmt.format(Date(v.modified)))
                h.folder.text = v.path?.substringBeforeLast('/')?.substringAfterLast('/') ?: "Selected folder"
                h.cb.setOnCheckedChangeListener(null)
                h.cb.isChecked = selected.contains(v.key)
                applySel(h, h.cb.isChecked)
                h.cb.setOnCheckedChangeListener { _, on ->
                    if (on) selected.add(v.key) else selected.remove(v.key)
                    applySel(h, on)
                    refreshSave()
                }
                h.card.setOnClickListener { h.cb.toggle() }
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
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            divider = null
            dividerHeight = 0
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        val empty = tv("No videos yet\n\nTap “Scan Phone” to search your device for TikTok videos.", 14f, cSub).apply {
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
        }
        val listWrap = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        list.emptyView = empty
        content.addView(listWrap)
        root.addView(content)

        // Bottom bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            elevation = dp(8).toFloat()
        }
        clearBtn = Button(this).apply {
            text = "Clear"
            styleBtn(this, false)
            setOnClickListener { selected.clear(); adapter.notifyDataSetChanged(); refreshSave() }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) }
        }
        saveBtn = Button(this).apply {
            text = "Save to Gallery (0)"
            styleBtn(this, true)
            isEnabled = false
            alpha = 0.45f
            setOnClickListener { saveSelected() }
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 2.2f)
        }
        bar.addView(clearBtn); bar.addView(saveBtn)
        root.addView(bar)
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
                toast("Shizuku is not running. Open Shizuku, tap Start, then come back.")
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
                .processNameSuffix("fs").daemon(false).version(3)
            Shizuku.bindUserService(args, conn)
        } catch (e: Throwable) {
            toast("Shizuku error: ${e.message}")
        }
    }

    private fun onScanClicked() {
        if (scanning) {
            scanBtn.text = "Stopping…"
            pool.execute { try { svc?.cancel() } catch (e: Exception) { } }
        } else withShizuku { scanPhone() }
    }

    private fun resetList() {
        videos.clear(); selected.clear(); adapter.notifyDataSetChanged(); refreshSave()
    }

    private fun scanPhone() {
        val s = svc ?: return
        if (busy) return
        busy = true
        scanning = true
        resetList()
        scanBtn.text = "Stop Scan"
        setStatus("Scanning your phone…", "Starting…")
        showProgress(true)
        pollProgress(s)
        Thread {
            val found = ArrayList<Vid>()
            var report = ""
            try {
                for (line in s.scanAll()) {
                    val p = line.split("\t")
                    if (p.size == 3) found.add(Vid(p[0], File(p[0]).name, p[1].toLong(), p[2].toLong(), null, p[0]))
                }
                if (found.isEmpty()) for (r in roots) report += s.info(r)
            } catch (e: Exception) { report = "Error: ${e.message}" }
            scanning = false
            finishScan(found, report)
        }.start()
    }

    private fun pollProgress(s: IFileService) {
        if (!scanning) return
        pool.execute {
            try {
                val p = s.progress().split("|")
                if (p.size == 2) ui.post {
                    if (scanning) setStatus(
                        "Scanning your phone…",
                        String.format(Locale.US, "%,d files checked  ·  %,d TikTok videos found", p[0].toInt(), p[1].toInt())
                    )
                }
            } catch (e: Exception) { }
        }
        ui.postDelayed({ pollProgress(s) }, 500)
    }

    private fun showInfo() {
        val s = svc ?: return
        setStatus("Running diagnostics…", "Checking TikTok folders")
        showProgress(true)
        Thread {
            var t = ""
            try { for (r in roots) t += s.info(r) } catch (e: Exception) { t = "Error: ${e.message}" }
            ui.post {
                showProgress(false)
                setStatus("Diagnostics complete", "See the report for details.")
                AlertDialog.Builder(this).setTitle("TikTok folder report").setMessage(t)
                    .setPositiveButton("Close", null).show()
            }
        }.start()
    }

    // ---------- Manual folder (fallback) ----------
    private fun pickFolder() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.putExtra(
            DocumentsContract.EXTRA_INITIAL_URI,
            Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fdata%2Fcom.zhiliaoapp.musically")
        )
        try { startActivityForResult(i, 1) } catch (e: Exception) { toast("Could not open the folder picker") }
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
        setStatus("Scanning folder…", "Looking for videos")
        showProgress(true)
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

    private fun finishScan(found: ArrayList<Vid>, report: String) {
        found.sortByDescending { it.modified }
        ui.post {
            videos.addAll(found)
            busy = false
            scanning = false
            scanBtn.text = "Scan Phone"
            showProgress(false)
            adapter.notifyDataSetChanged()
            refreshSave()
            if (found.isEmpty()) {
                setStatus("No videos found", "Open Diagnostics to see what TikTok left on this device.")
                if (report.isNotEmpty())
                    AlertDialog.Builder(this).setTitle("No videos found").setMessage(report)
                        .setPositiveButton("Close", null).show()
            } else setStatus(
                "Found ${found.size} video" + (if (found.size == 1) "" else "s"),
                "Tap videos to select them, then save to your gallery."
            )
        }
    }

    // ---------- File access, thumbnails, playback, saving ----------
    private fun openStream(v: Vid): InputStream? =
        if (v.uri != null) contentResolver.openInputStream(v.uri)
        else svc?.open(v.path)?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }

    private fun thumb(v: Vid): Bitmap? {
        var pfd: ParcelFileDescriptor? = null
        try {
            val r = MediaMetadataRetriever()
            if (v.uri != null) {
                r.setDataSource(this, v.uri)
            } else {
                pfd = svc?.open(v.path)
                if (pfd == null) return null
                r.setDataSource(pfd.fileDescriptor)
            }
            val bm = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            r.release()
            return bm?.let { Bitmap.createScaledBitmap(it, 136, 180, true) }
        } catch (e: Exception) {
            return null
        } finally {
            try { pfd?.close() } catch (e: Exception) { }
        }
    }

    private fun play(v: Vid) {
        toast("Loading video…")
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
                if (v.uri == null && path == null) { toast("Could not open this video"); return@post }
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
        saveBtn.text = "Save to Gallery (${selected.size})"
        val on = selected.isNotEmpty() && !busy
        saveBtn.isEnabled = on
        saveBtn.alpha = if (on) 1f else 0.45f
    }

    private fun saveSelected() {
        if (busy) return
        busy = true
        refreshSave()
        val todo = videos.filter { selected.contains(it.key) }
        showProgress(true, todo.size, 0)
        Thread {
            var ok = 0
            todo.forEachIndexed { i, v ->
                ui.post {
                    setStatus("Saving to gallery…", "${i + 1} of ${todo.size}")
                    showProgress(true, todo.size, i)
                }
                if (saveToGallery(v)) ok++
            }
            ui.post {
                busy = false
                selected.clear()
                showProgress(false)
                adapter.notifyDataSetChanged()
                refreshSave()
                setStatus("Saved $ok of ${todo.size}", "Find them in your gallery under Movies/Video Saver.")
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
