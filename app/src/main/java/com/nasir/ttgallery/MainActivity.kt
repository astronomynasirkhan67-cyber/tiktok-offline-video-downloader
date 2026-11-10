package com.nasir.ttgallery

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.Executors

class MainActivity : Activity() {

    data class Vid(val uri: Uri, val name: String, val size: Long, val modified: Long)

    private val videos = ArrayList<Vid>()
    private val selected = HashSet<Uri>()
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val thumbs = LruCache<String, Bitmap>(80)
    private lateinit var status: TextView
    private lateinit var saveBtn: Button
    private lateinit var adapter: BaseAdapter
    @Volatile private var busy = false

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(36), dp(12), dp(8))
        }
        root.addView(TextView(this).apply {
            text = "TikTok Offline → Gallery"; textSize = 20f; setTextColor(Color.BLACK)
        })
        fun btn(t: String, c: () -> Unit) = Button(this).apply {
            text = t; textSize = 12f; isAllCaps = false
            setOnClickListener { c() }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("TikTok\n(musically)") { pick("com.zhiliaoapp.musically", 1) })
        row.addView(btn("TikTok\n(trill)") { pick("com.ss.android.ugc.trill", 2) })
        row.addView(btn("कोई भी\nफ़ोल्डर") { pick(null, 3) })
        root.addView(row)
        status = TextView(this).apply {
            text = "ऊपर से फ़ोल्डर चुनें, ऐप वीडियो स्कैन करेगा"
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
                        gravity = android.view.Gravity.CENTER_VERTICAL
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
                h.cb.isChecked = selected.contains(v.uri)
                h.cb.setOnCheckedChangeListener { _, on ->
                    if (on) selected.add(v.uri) else selected.remove(v.uri)
                    refreshSave()
                }
                view.setOnClickListener { h.cb.toggle() }
                h.play.setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(v.uri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "प्लेयर नहीं मिला", Toast.LENGTH_SHORT).show()
                    }
                }
                val key = v.uri.toString()
                h.iv.tag = key
                val c = thumbs.get(key)
                if (c != null) h.iv.setImageBitmap(c) else {
                    h.iv.setImageDrawable(null)
                    pool.execute {
                        val bm = thumb(v)
                        if (bm != null) {
                            thumbs.put(key, bm)
                            ui.post { if (h.iv.tag == key) h.iv.setImageBitmap(bm) }
                        }
                    }
                }
                return view
            }
        }
        val list = ListView(this).apply {
            adapter = this@MainActivity.adapter
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(list)
        saveBtn = Button(this).apply {
            text = "गैलरी में सेव करें (0)"; isEnabled = false
            setOnClickListener { saveSelected() }
        }
        root.addView(saveBtn)
        setContentView(root)
    }

    class Holder(val cb: CheckBox, val iv: ImageView, val name: TextView, val size: TextView, val play: Button)

    private fun refreshSave() {
        saveBtn.text = "गैलरी में सेव करें (${selected.size})"
        saveBtn.isEnabled = selected.isNotEmpty() && !busy
    }

    private fun pick(pkg: String?, code: Int) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        if (pkg != null) {
            i.putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fdata%2F$pkg")
            )
        }
        try { startActivityForResult(i, code) } catch (e: Exception) {
            Toast.makeText(this, "फ़ोल्डर पिकर नहीं खुला", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val tree = data?.data
        if (res == RESULT_OK && tree != null) scan(tree)
    }

    private fun scan(tree: Uri) {
        if (busy) return
        busy = true
        videos.clear(); selected.clear(); adapter.notifyDataSetChanged(); refreshSave()
        status.text = "स्कैन हो रहा है..."
        Thread {
            val found = ArrayList<Vid>()
            try {
                val root = DocumentFile.fromTreeUri(this, tree)
                if (root != null) walk(root, found)
            } catch (e: Exception) { }
            found.sortByDescending { it.modified }
            ui.post {
                videos.addAll(found)
                busy = false
                adapter.notifyDataSetChanged()
                refreshSave()
                status.text = if (found.isEmpty())
                    "कोई वीडियो नहीं मिला। दूसरा फ़ोल्डर चुनें।"
                else "${found.size} वीडियो मिले। जो पसंद हो उसे चुनें।"
            }
        }.start()
    }

    private fun walk(dir: DocumentFile, out: ArrayList<Vid>) {
        for (f in dir.listFiles()) {
            if (f.isDirectory) { walk(f, out); continue }
            if (f.length() < 150_000L) continue
            if (isMp4(f.uri)) {
                out.add(Vid(f.uri, f.name ?: "video", f.length(), f.lastModified()))
                if (out.size % 20 == 0) {
                    val n = out.size
                    ui.post { status.text = "स्कैन हो रहा है... $n मिले" }
                }
            }
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

    private fun thumb(v: Vid): Bitmap? = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(this, v.uri)
        val bm = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        r.release()
        bm?.let { Bitmap.createScaledBitmap(it, 90, 120, true) }
    } catch (e: Exception) { null }

    private fun saveSelected() {
        if (busy) return
        busy = true
        refreshSave()
        val todo = videos.filter { selected.contains(it.uri) }
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
                status.text = "$ok/${todo.size} वीडियो गैलरी में सेव हुए (Movies/TikTok Offline)"
            }
        }.start()
    }

    private fun saveToGallery(v: Vid): Boolean {
        try {
            val base = v.name.substringBeforeLast('.').take(40).replace(Regex("[^A-Za-z0-9_-]"), "_")
            val name = "TikTok_${base}_${v.uri.hashCode() and 0xffff}.mp4"
            val cv = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/TikTok Offline")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val out = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: return false
            contentResolver.openOutputStream(out)!!.use { o ->
                contentResolver.openInputStream(v.uri)!!.use { it.copyTo(o) }
            }
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            contentResolver.update(out, done, null, null)
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
