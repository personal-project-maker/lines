package org.linesapp.follow

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.max

class MainActivity : Activity(), RecognitionListener {
    private val BG = Color.parseColor("#14121A")
    private val PANEL = Color.parseColor("#1F1C28")
    private val PANEL2 = Color.parseColor("#2A2636")
    private val TEXT = Color.parseColor("#F4F1EA")
    private val MUTED = Color.parseColor("#A9A3B8")
    private val DIM = Color.parseColor("#6F6984")
    private val ACCENT = Color.parseColor("#F2C14E")
    private val OK = Color.parseColor("#6FD08C")

    private val REQ_MIC = 1
    private val REQ_FILE = 2

    private lateinit var prefs: SharedPreferences
    private lateinit var scroll: ScrollView
    private lateinit var text: TextView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var dot: View
    private lateinit var sceneSpinner: Spinner
    private lateinit var pauseBtn: Button

    private var model: FollowModel? = null
    private var words: List<DispWord> = emptyList()
    private var p = 0
    private val tail = ArrayList<String>()
    private var lastPartial = ""

    private var voskModel: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var paused = false
    private var visible = false

    private val saidSpan = ForegroundColorSpan(Color.parseColor("#6F6984"))
    private val nowSpan = ForegroundColorSpan(Color.parseColor("#F2C14E"))
    private val nowUnderline = UnderlineSpan()
    private val handler = Handler(Looper.getMainLooper())

    private val baseSp = 24f
    private var fontScale = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        prefs = getSharedPreferences("lines", MODE_PRIVATE)
        fontScale = prefs.getFloat("fontScale", 1f)
        buildUi()
        loadSavedScript()
        loadSpeechModel()
    }

    // ---------------- UI ----------------
    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        b.setTextColor(if (primary) Color.parseColor("#1B1608") else TEXT)
        b.minHeight = dp(56)
        val bg = GradientDrawable()
        bg.cornerRadius = dp(16).toFloat()
        bg.setColor(if (primary) ACCENT else PANEL2)
        b.background = bg
        b.setPadding(dp(10), dp(8), dp(10), dp(8))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.setPadding(dp(12), dp(10), dp(12), dp(10))

        // top bar
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        titleView = TextView(this)
        titleView.setTextColor(TEXT)
        titleView.setTypeface(null, Typeface.BOLD)
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        titleView.maxLines = 2
        top.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val smaller = button("A-") { changeFont(-0.1f) }
        smaller.contentDescription = "Smaller text"
        val bigger = button("A+") { changeFont(0.1f) }
        bigger.contentDescription = "Bigger text"
        val load = button("Load script") { pickScript() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = dp(6)
        top.addView(smaller, lp); top.addView(bigger, LinearLayout.LayoutParams(lp)); top.addView(load, LinearLayout.LayoutParams(lp))
        root.addView(top)

        // status row
        val statusRow = LinearLayout(this)
        statusRow.orientation = LinearLayout.HORIZONTAL
        statusRow.gravity = Gravity.CENTER_VERTICAL
        statusRow.setPadding(0, dp(8), 0, dp(8))
        dot = View(this)
        val dotBg = GradientDrawable(); dotBg.shape = GradientDrawable.OVAL; dotBg.setColor(MUTED)
        dot.background = dotBg
        statusRow.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)))
        statusView = TextView(this)
        statusView.setTextColor(MUTED)
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        statusView.setPadding(dp(8), 0, dp(8), 0)
        statusView.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        statusRow.addView(statusView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sceneSpinner = Spinner(this)
        sceneSpinner.contentDescription = "Jump to scene"
        statusRow.addView(sceneSpinner, LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(statusRow)

        // script
        scroll = ScrollView(this)
        val scrollBg = GradientDrawable(); scrollBg.cornerRadius = dp(18).toFloat(); scrollBg.setColor(PANEL)
        scroll.background = scrollBg
        scroll.isVerticalScrollBarEnabled = true
        text = TextView(this)
        text.setTextColor(TEXT)
        text.setLineSpacing(0f, 1.25f)
        text.setPadding(dp(18), dp(200), dp(18), dp(400))
        applyFont()
        scroll.addView(text, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // tap a word to move the cursor there
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val off = text.getOffsetForPosition(e.x, e.y)
                val w = wordAtChar(off) ?: return false
                tail.clear()
                setPos(w.from)
                return true
            }
        })
        text.setOnTouchListener { _, ev -> taps.onTouchEvent(ev); false }

        // bottom controls
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.setPadding(0, dp(10), 0, 0)
        val blp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = dp(6) } }
        bottom.addView(button("Back a line") { stepLine(-1) }, blp())
        pauseBtn = button("Pause", primary = true) { togglePause() }
        bottom.addView(pauseBtn, blp())
        bottom.addView(button("Forward a line") { stepLine(1) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bottom)

        setContentView(root)

        // leave room so the first and last lines can sit at the reading position
        scroll.post {
            val h = scroll.height
            text.setPadding(dp(18), (h * 0.3).toInt(), dp(18), (h * 0.65).toInt())
            setPos(p, smooth = false, force = true)
        }
    }

    private fun setStatus(msg: String, live: Boolean) {
        statusView.text = msg
        (dot.background as GradientDrawable).setColor(if (live) OK else MUTED)
    }

    private fun applyFont() {
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp * fontScale)
    }

    private fun changeFont(delta: Float) {
        fontScale = (fontScale + delta).coerceIn(0.6f, 2.5f)
        prefs.edit().putFloat("fontScale", fontScale).apply()
        applyFont()
        text.post { setPos(p, smooth = false, force = true) }
    }

    // ---------------- script ----------------
    private fun loadSavedScript() {
        val raw = prefs.getString("script", null)
        if (raw == null) {
            showNoScript()
            return
        }
        try {
            useScript(ScriptLoader.parse(raw.toByteArray(Charsets.UTF_8)), prefs.getInt("pos", 0))
        } catch (e: Exception) {
            showNoScript()
        }
    }

    private fun showNoScript() {
        model = null
        titleView.text = "Lines Follow"
        text.text = "Tap \"Load script\" and choose your script file.\n\nThe same .json file you loaded into Lines works best, because it knows which lines are yours. A Word document or a plain text file also works, but then every line is treated as yours."
        text.setTextColor(MUTED)
        setStatus("No script loaded", false)
    }

    private fun pickScript() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        try {
            startActivityForResult(i, REQ_FILE)
        } catch (e: Exception) {
            toast("Couldn't open the file picker")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val script = ScriptLoader.parse(bytes)
            val fm = FollowModel(script)
            if (fm.units.isEmpty()) { toast("That file didn't have any lines in it"); return }
            // keep it as JSON-ish text: docx/txt get stored as their plain lines
            val stored = if (bytes.size > 1 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte())
                script.scenes.flatMap { s -> s.items.map { it.t } }.joinToString("\n")
            else String(bytes, Charsets.UTF_8)
            prefs.edit().putString("script", stored).putInt("pos", 0).apply()
            useScript(script, 0)
            toast("Script loaded: ${fm.units.size} lines")
            restartListening()
        } catch (e: Exception) {
            toast("Couldn't read that file")
        }
    }

    private fun useScript(script: Script, pos: Int) {
        val fm = FollowModel(script)
        model = fm
        text.setTextColor(TEXT)
        render(fm)
        setupScenes(fm)
        p = -1
        text.post { setPos(pos.coerceIn(0, fm.E.size), smooth = false, force = true) }
    }

    private fun render(fm: FollowModel) {
        val sb = SpannableStringBuilder()
        var unitIdx = 0
        val all = ArrayList<DispWord>()
        fm.script.scenes.forEach { sc ->
            var anchor = sb.length
            val head = (sc.id + " " + sc.title).trim().uppercase() + (if (sc.pre) " (PRE-RECORDED)" else "")
            sb.append(head)
            sb.setSpan(ForegroundColorSpan(ACCENT), anchor, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.6f), anchor, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), anchor, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n\n")
            for (it in sc.items) {
                if (it.cue != null) {
                    val cs = sb.length
                    sb.append(it.cue).append(": ").append(it.t)
                    sb.setSpan(ForegroundColorSpan(MUTED), cs, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(0.72f), cs, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.BOLD), cs, cs + it.cue.length + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append("\n\n")
                    if (anchor < 0) anchor = cs
                    continue
                }
                val u = fm.units[unitIdx++]
                u.anchorChar = anchor
                anchor = -1
                for (d in u.disp) {
                    d.charStart = sb.length
                    sb.append(d.w)
                    d.charEnd = sb.length
                    sb.append(' ')
                    all.add(d)
                }
                sb.append("\n\n")
            }
        }
        words = all
        text.setText(sb, TextView.BufferType.SPANNABLE)
    }

    private fun setupScenes(fm: FollowModel) {
        val labels = ArrayList<String>()
        val starts = ArrayList<Int>()
        labels.add("Jump to scene"); starts.add(-1)
        fm.script.scenes.forEachIndexed { si, sc ->
            val first = fm.units.firstOrNull { it.sceneIdx == si } ?: return@forEachIndexed
            labels.add((sc.id + " " + sc.title).trim())
            starts.add(first.start)
        }
        val ad = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(MUTED); v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); v.maxLines = 1
                return v
            }
        }
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sceneSpinner.adapter = ad
        sceneSpinner.setSelection(0, false)
        sceneSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position <= 0) return
                tail.clear()
                setPos(starts[position], smooth = false)
                sceneSpinner.setSelection(0, false)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun wordAtChar(off: Int): DispWord? {
        var lo = 0; var hi = words.size - 1; var ans: DispWord? = null
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (words[mid].charStart <= off) { ans = words[mid]; lo = mid + 1 } else hi = mid - 1
        }
        if (ans == null || off > ans.charEnd) return null
        return ans
    }

    // ---------------- cursor ----------------
    private fun setPos(np0: Int, smooth: Boolean = true, force: Boolean = false) {
        val fm = model ?: return
        val np = np0.coerceIn(0, fm.E.size)
        val moved = np != p
        p = np
        val sp = text.text as? Spannable ?: return
        // the next word to say: first real word not yet fully said
        var now: DispWord? = null
        for (w in words) if (w.from < w.to && w.to > p) { now = w; break }
        val saidEnd = now?.charStart ?: sp.length
        sp.setSpan(saidSpan, 0, saidEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (now != null) {
            sp.setSpan(nowSpan, now.charStart, now.charEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(nowUnderline, now.charStart, now.charEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else {
            sp.removeSpan(nowSpan); sp.removeSpan(nowUnderline)
        }
        prefs.edit().putInt("pos", p).apply()

        val u = fm.unitAt(minOf(p, fm.E.size - 1))
        if (u != null) {
            val sc = fm.script.scenes[u.sceneIdx]
            titleView.text = (sc.id + " " + sc.title).trim()
        }
        if (p >= fm.E.size) setStatus("End of the script", false)

        // at the very start of one of your lines, show the cues or scene heading leading into it
        var target = now?.charStart ?: sp.length
        if (u != null && p == u.start && u.anchorChar >= 0) target = u.anchorChar
        if (moved || force) scrollToChar(target, smooth)
    }

    private fun scrollToChar(c: Int, smooth: Boolean) {
        text.post {
            val layout = text.layout ?: return@post
            val line = layout.getLineForOffset(c.coerceIn(0, text.text.length))
            val y = max(0, text.top + text.paddingTop + layout.getLineTop(line) - (scroll.height * 0.3).toInt())
            if (smooth) scroll.smoothScrollTo(0, y) else scroll.scrollTo(0, y)
        }
    }

    private fun stepLine(dir: Int) {
        val fm = model ?: return
        tail.clear()
        if (fm.E.isEmpty()) return
        val u = fm.unitAt(minOf(p, fm.E.size - 1)) ?: return
        if (dir > 0) {
            setPos(if (p >= fm.E.size) fm.E.size else u.end)
        } else {
            if (p > u.start && p < fm.E.size) setPos(u.start)
            else {
                val idx = if (p >= fm.E.size) fm.units.size else u.idx
                setPos(fm.units[max(0, idx - 1)].start)
            }
        }
    }

    private fun heard(raw: String, final: Boolean) {
        val fm = model ?: return
        if (paused) return
        val h = Words.toks(raw.replace("[unk]", " ")).filter { it !in Words.FILLER }
        if (h.isEmpty()) return
        val T: List<String>
        if (final) {
            tail.addAll(h)
            while (tail.size > 10) tail.removeAt(0)
            T = ArrayList(tail)
        } else {
            T = (tail.takeLast(4) + h).takeLast(10)
        }
        val np = fm.locate(T, p) ?: return
        setPos(np)
    }

    // ---------------- speech (Vosk, fully offline) ----------------
    private fun loadSpeechModel() {
        setStatus("Getting the speech model ready (first time takes a moment)...", false)
        thread {
            try {
                val dir = unpackModel()
                val m = Model(dir.absolutePath)
                runOnUiThread {
                    voskModel = m
                    if (model != null) setStatus("Ready", false)
                    startListening()
                }
            } catch (e: Throwable) {
                runOnUiThread { setStatus("Couldn't load the speech model: ${e.message}", false) }
            }
        }
    }

    private fun unpackModel(): File {
        val target = File(filesDir, "model")
        val marker = File(target, ".stamp")
        val stamp = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
        if (marker.exists() && marker.readText() == stamp) return target
        target.deleteRecursively()
        copyAssets("model", target)
        marker.writeText(stamp)
        return target
    }

    private fun copyAssets(path: String, dest: File) {
        val list = assets.list(path) ?: emptyArray()
        if (list.isEmpty()) {
            dest.parentFile?.mkdirs()
            try {
                assets.open(path).use { input -> FileOutputStream(dest).use { input.copyTo(it) } }
            } catch (e: Exception) {
                dest.mkdirs() // an empty folder
            }
        } else {
            dest.mkdirs()
            for (name in list) copyAssets("$path/$name", File(dest, name))
        }
    }

    private fun hasMic(): Boolean =
        Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startListening() {
        val fm = model ?: return
        val vm = voskModel ?: return
        if (paused || !visible || speech != null) return
        if (!hasMic()) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        try {
            // Listening only for words in your script makes recognition much more accurate;
            // anything else comes out as "unknown" and is ignored.
            val rec = Recognizer(vm, 16000f, fm.grammarJson())
            recognizer = rec
            val s = SpeechService(rec, 16000f)
            speech = s
            lastPartial = ""
            s.startListening(this)
            setStatus("Listening. It moves when you speak.", true)
        } catch (e: Exception) {
            setStatus("Couldn't start the microphone: ${e.message}", false)
            stopListening()
        }
    }

    private fun stopListening() {
        try { speech?.stop() } catch (_: Exception) {}
        try { speech?.shutdown() } catch (_: Exception) {}
        speech = null
        try { recognizer?.close() } catch (_: Exception) {}
        recognizer = null
    }

    private fun restartListening() {
        stopListening()
        startListening()
    }

    private fun togglePause() {
        paused = !paused
        pauseBtn.text = if (paused) "Resume" else "Pause"
        if (paused) {
            stopListening()
            setStatus("Paused", false)
        } else {
            tail.clear()
            startListening()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startListening()
        else setStatus("Microphone permission is needed to follow you. You can still use the buttons.", false)
    }

    override fun onPartialResult(hypothesis: String?) {
        val t = try { JSONObject(hypothesis ?: return).optString("partial") } catch (e: Exception) { return }
        if (t == lastPartial) return
        lastPartial = t
        heard(t, final = false)
    }

    override fun onResult(hypothesis: String?) {
        val t = try { JSONObject(hypothesis ?: return).optString("text") } catch (e: Exception) { return }
        lastPartial = ""
        heard(t, final = true)
    }

    override fun onFinalResult(hypothesis: String?) {
        onResult(hypothesis)
    }

    override fun onError(exception: Exception?) {
        setStatus("Microphone problem, retrying...", false)
        stopListening()
        handler.postDelayed({ startListening() }, 1500)
    }

    override fun onTimeout() {
        stopListening()
        startListening()
    }

    // ---------------- lifecycle ----------------
    override fun onStart() {
        super.onStart()
        visible = true
        startListening()
    }

    override fun onStop() {
        super.onStop()
        visible = false
        stopListening()
        if (!paused && model != null) setStatus("Ready", false)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        try { voskModel?.close() } catch (_: Exception) {}
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
