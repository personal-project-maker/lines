package org.linesapp.follow

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
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
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import kotlin.math.max

class MainActivity : Activity(), RecognitionListener, RemoteHost {
    private val BG = Color.parseColor("#14121A")
    private val PANEL = Color.parseColor("#1F1C28")
    private val PANEL2 = Color.parseColor("#2A2636")
    private val TEXT = Color.parseColor("#F4F1EA")
    private val MUTED = Color.parseColor("#A9A3B8")
    private val DIM = Color.parseColor("#6F6984")
    private val SKIPPED = Color.parseColor("#55506A")
    private val ACCENT = Color.parseColor("#F2C14E")
    private val OK = Color.parseColor("#6FD08C")

    private val REQ_MIC = 1
    private val REQ_FILE = 2
    private val MAKER_URL = "https://personal-project-maker.github.io/lines/maker.html"

    private lateinit var prefs: SharedPreferences
    private lateinit var scroll: ScrollView
    private lateinit var text: TextView
    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var dot: View
    private lateinit var sceneSpinner: Spinner
    private lateinit var pauseBtn: Button

    private var script: Script? = null
    private var model: FollowModel? = null
    private var words: List<DispWord> = emptyList()
    /** Every scene heading and item on screen: char start, scene, item (-1 = the scene heading). */
    private var anchors: List<IntArray> = emptyList()
    private var p = 0
    private val tail = ArrayList<String>()
    private var lastPartial = ""
    private var statusText = ""
    private var statusLive = false

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

    private lateinit var local: LocalServer
    private val online = OnlineLink(this)
    private var shareDialog: AlertDialog? = null
    private var refreshShare: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        prefs = getSharedPreferences("lines", MODE_PRIVATE)
        fontScale = prefs.getFloat("fontScale", 1f)
        local = LocalServer(this, this)
        val room = prefs.getString("room", null)
        val key = prefs.getString("key", null)
        if (room != null && key != null) online.setCredentials(room, key) else saveNewCredentials()
        buildUi()
        loadSavedScript()
        loadSpeechModel()
        if (prefs.getBoolean("shareLocal", false)) local.start()
        if (prefs.getBoolean("shareOnline", false)) online.start()
        handleViewIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW) i.data?.let { loadFromUri(it) }
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
        lateinit var menuBtn: Button
        menuBtn = button("Menu") { showMenu(menuBtn) }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.marginStart = dp(6)
        top.addView(smaller, lp); top.addView(bigger, LinearLayout.LayoutParams(lp)); top.addView(menuBtn, LinearLayout.LayoutParams(lp))
        root.addView(top)

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

        scroll = ScrollView(this)
        val scrollBg = GradientDrawable(); scrollBg.cornerRadius = dp(18).toFloat(); scrollBg.setColor(PANEL)
        scroll.background = scrollBg
        text = TextView(this)
        text.setTextColor(TEXT)
        text.setLineSpacing(0f, 1.25f)
        text.setPadding(dp(18), dp(200), dp(18), dp(400))
        applyFont()
        scroll.addView(text, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> scheduleState(400) }

        // tap a word to move there; press and hold for skip and edit options
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val off = text.getOffsetForPosition(e.x, e.y)
                val w = wordAtChar(off) ?: return false
                val fm = model ?: return false
                tail.clear()
                setPos(fm.posFor(w.si, w.ii, w.wi))
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                val off = text.getOffsetForPosition(e.x, e.y)
                showEditMenu(off)
            }
        })
        text.setOnTouchListener { _, ev -> taps.onTouchEvent(ev); false }

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.setPadding(0, dp(10), 0, 0)
        val blp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = dp(6) } }
        bottom.addView(button("Back a line") { stepLine(-1) }, blp())
        pauseBtn = button("Pause", primary = true) { setPaused(!paused) }
        bottom.addView(pauseBtn, blp())
        bottom.addView(button("Forward a line") { stepLine(1) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bottom)

        setContentView(root)

        scroll.post {
            val h = scroll.height
            text.setPadding(dp(18), (h * 0.3).toInt(), dp(18), (h * 0.65).toInt())
            setPos(p, smooth = false, force = true)
        }
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Load a script")
        m.menu.add(0, 2, 1, "Make a script (opens the script maker)")
        m.menu.add(0, 3, 2, "Share my screen")
        m.menu.add(0, 4, 3, "Undo all skips and changes")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> pickScript()
                2 -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MAKER_URL))) } catch (e: Exception) { toast("Couldn't open the browser") }
                3 -> showShare()
                4 -> confirmUndoAll()
            }
            true
        }
        m.show()
    }

    private fun setStatus(msg: String, live: Boolean) {
        statusText = msg; statusLive = live
        statusView.text = msg
        (dot.background as GradientDrawable).setColor(if (live) OK else MUTED)
        scheduleState()
    }

    private fun applyFont() {
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSp * fontScale)
    }

    private fun changeFont(delta: Float) {
        fontScale = (fontScale + delta).coerceIn(0.6f, 2.5f)
        prefs.edit().putFloat("fontScale", fontScale).apply()
        applyFont()
        text.post { setPos(p, smooth = false, force = true) }
        scheduleState()
    }

    // ---------------- script ----------------
    private fun loadSavedScript() {
        val raw = prefs.getString("script", null)
        if (raw == null) { showNoScript(); return }
        try {
            useScript(ScriptLoader.parse(raw.toByteArray(Charsets.UTF_8)), prefs.getInt("pos", 0))
        } catch (e: Exception) {
            showNoScript()
        }
    }

    private fun showNoScript() {
        script = null
        model = null
        titleView.text = "Lines Follow"
        text.text = "Open the Menu and choose \"Load a script\".\n\nThe same .json file you loaded into Lines works best, because it knows which lines are yours. You can make one from a Word document with \"Make a script\" in the Menu. A Word document or plain text file also loads directly, but then every line is treated as yours."
        text.setTextColor(MUTED)
        setStatus("No script loaded", false)
    }

    private fun pickScript() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        try { startActivityForResult(i, REQ_FILE) } catch (e: Exception) { toast("Couldn't open the file picker") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE || resultCode != RESULT_OK) return
        data?.data?.let { loadFromUri(it) }
    }

    private fun loadFromUri(uri: Uri) {
        try {
            val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val sc = ScriptLoader.parse(bytes)
            val fm = FollowModel(sc)
            if (fm.units.isEmpty()) { toast("That file didn't have any lines in it"); return }
            useScript(sc, 0)
            saveScript()
            toast("Script loaded: ${fm.units.size} lines")
            restartListening()
        } catch (e: Exception) {
            toast("Couldn't read that file")
        }
    }

    private fun saveScript() {
        val sc = script ?: return
        prefs.edit().putString("script", sc.toJson()).putInt("pos", p).apply()
    }

    private fun useScript(sc: Script, pos: Int) {
        script = sc
        val fm = FollowModel(sc)
        model = fm
        text.setTextColor(TEXT)
        render(fm)
        setupScenes(fm)
        p = -1
        text.post { setPos(pos.coerceIn(0, fm.E.size), smooth = false, force = true) }
        broadcastScript()
    }

    private fun render(fm: FollowModel) {
        val sb = SpannableStringBuilder()
        var unitIdx = 0
        val all = ArrayList<DispWord>()
        val anc = ArrayList<IntArray>()
        fun strike(a: Int, b: Int) {
            sb.setSpan(StrikethroughSpan(), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(SKIPPED), a, b, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fm.script.scenes.forEachIndexed { si, sc ->
            var anchor = sb.length
            anc.add(intArrayOf(sb.length, si, -1))
            val head = (sc.id + " " + sc.title).trim().uppercase() + (if (sc.pre) " (PRE-RECORDED)" else "") + (if (sc.skip) " (SKIPPED)" else "")
            sb.append(head)
            sb.setSpan(ForegroundColorSpan(if (sc.skip) SKIPPED else ACCENT), anchor, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(0.6f), anchor, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), anchor, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n\n")
            sc.items.forEachIndexed { ii, it ->
                anc.add(intArrayOf(sb.length, si, ii))
                if (it.cue != null) {
                    val cs = sb.length
                    sb.append(it.cue).append(": ").append(it.t)
                    sb.setSpan(ForegroundColorSpan(MUTED), cs, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(0.72f), cs, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.BOLD), cs, cs + it.cue.length + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (sc.skip || it.skip) strike(cs, sb.length)
                    sb.append("\n\n")
                    if (anchor < 0) anchor = cs
                } else {
                    val u = fm.units[unitIdx++]
                    u.anchorChar = anchor
                    anchor = -1
                    for (d in u.disp) {
                        d.charStart = sb.length
                        sb.append(d.w)
                        d.charEnd = sb.length
                        if (d.skipped) strike(d.charStart, d.charEnd)
                        sb.append(' ')
                        all.add(d)
                    }
                    sb.append("\n\n")
                }
            }
        }
        words = all
        anchors = anc
        text.setText(sb, TextView.BufferType.SPANNABLE)
    }

    private fun setupScenes(fm: FollowModel) {
        val labels = ArrayList<String>()
        val starts = ArrayList<Int>()
        labels.add("Jump to scene"); starts.add(-1)
        fm.script.scenes.forEachIndexed { si, sc ->
            labels.add((sc.id + " " + sc.title).trim() + if (sc.skip) " (skipped)" else "")
            starts.add(fm.posFor(si, 0, 0))
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
                setPos(starts[position], smooth = false, force = true)
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

    private fun anchorAtChar(off: Int): IntArray? {
        var ans: IntArray? = null
        for (a in anchors) { if (a[0] <= off) ans = a else break }
        return ans
    }

    private fun charFor(si: Int, ii: Int, wi: Int): Int? {
        if (ii >= 0 && wi >= 0) words.firstOrNull { it.si == si && it.ii == ii && it.wi == wi }?.let { return it.charStart }
        return anchors.firstOrNull { it[1] == si && it[2] == ii }?.get(0) ?: anchors.firstOrNull { it[1] == si }?.get(0)
    }

    // ---------------- cursor ----------------
    private fun setPos(np0: Int, smooth: Boolean = true, force: Boolean = false) {
        val fm = model ?: return
        val np = np0.coerceIn(0, fm.E.size)
        val moved = np != p
        p = np
        val sp = text.text as? Spannable ?: return
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

        var target = now?.charStart ?: sp.length
        if (u != null && p == u.start && u.anchorChar >= 0) target = u.anchorChar
        if (moved || force) scrollToChar(target, smooth)
        scheduleState()
    }

    private fun scrollToChar(c: Int, smooth: Boolean) {
        text.post {
            val layout = text.layout ?: return@post
            val line = layout.getLineForOffset(c.coerceIn(0, text.text.length))
            val y = max(0, text.top + text.paddingTop + layout.getLineTop(line) - (scroll.height * 0.3).toInt())
            if (smooth) scroll.smoothScrollTo(0, y) else scroll.scrollTo(0, y)
        }
    }

    /** What sits at the reading line of the screen right now, as (scene, item, word). */
    private fun viewRef(): IntArray? {
        val layout = text.layout ?: return null
        val y = scroll.scrollY + (scroll.height * 0.3).toInt() - text.top - text.paddingTop
        val line = layout.getLineForVertical(max(0, y))
        val off = layout.getLineStart(line)
        val end = layout.getLineEnd(line)
        val w = words.firstOrNull { it.charStart in off until end }
        if (w != null) return intArrayOf(w.si, w.ii, w.wi)
        val a = anchorAtChar(off) ?: return null
        return intArrayOf(a[1], a[2], -1)
    }

    private fun stepLine(dir: Int) {
        val fm = model ?: return
        tail.clear()
        if (fm.E.isEmpty()) return
        val u = fm.unitAt(minOf(p, fm.E.size - 1)) ?: return
        if (dir > 0) {
            var k = u.idx + 1
            while (k < fm.units.size && fm.units[k].start == fm.units[k].end) k++
            setPos(if (p >= fm.E.size) fm.E.size else if (k < fm.units.size) fm.units[k].start else fm.E.size)
        } else {
            if (p > u.start && p < fm.E.size) setPos(u.start)
            else {
                var k = (if (p >= fm.E.size) fm.units.size else u.idx) - 1
                while (k > 0 && fm.units[k].start == fm.units[k].end) k--
                setPos(fm.units[max(0, k)].start)
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

    // ---------------- editing: skip and change wording ----------------
    private fun showEditMenu(off: Int) {
        val sc = script ?: return
        val w = wordAtChar(off)
        val a = if (w != null) intArrayOf(0, w.si, w.ii) else anchorAtChar(off) ?: return
        val si = a[1]; val ii = a[2]
        val scene = sc.scenes[si]
        val labels = ArrayList<String>(); val actions = ArrayList<() -> Unit>()
        fun add(l: String, f: () -> Unit) { labels.add(l); actions.add(f) }
        if (ii >= 0) {
            val item = scene.items[ii]
            if (w != null) {
                val skipped = w.wi in item.skipWords
                add(if (skipped) "Don't skip \"${w.w}\"" else "Skip the word \"${w.w}\"") { edit("skipWord", si, ii, w.wi, !skipped, null) }
                add("Start following from here") { model?.let { m -> tail.clear(); setPos(m.posFor(si, ii, w.wi)) } }
            }
            add(if (item.skip) "Don't skip this line" else "Skip this line") { edit("skipItem", si, ii, -1, !item.skip, null) }
            add("Change the wording") { askWording(si, ii) }
            if (item.orig != null) add("Put back the original wording") { edit("restore", si, ii, -1, true, null) }
        }
        add(if (scene.skip) "Don't skip this scene" else "Skip this whole scene") { edit("skipScene", si, -1, -1, !scene.skip, null) }
        AlertDialog.Builder(this)
            .setTitle(if (ii >= 0) shorten(scene.items[ii].t) else (scene.id + " " + scene.title).trim())
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shorten(s: String) = if (s.length > 70) s.take(67) + "..." else s

    private fun askWording(si: Int, ii: Int) {
        val item = script?.scenes?.getOrNull(si)?.items?.getOrNull(ii) ?: return
        val box = EditText(this)
        box.setText(item.t)
        box.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        box.setSelection(item.t.length)
        val wrap = LinearLayout(this); wrap.setPadding(dp(20), dp(8), dp(20), 0); wrap.addView(box, LinearLayout.LayoutParams(-1, -2))
        AlertDialog.Builder(this)
            .setTitle("Change the wording")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                val t = box.text.toString().replace(Regex("\\s+"), " ").trim()
                if (t.isNotEmpty() && t != item.t) edit("text", si, ii, -1, true, t)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmUndoAll() {
        if (script == null) return
        AlertDialog.Builder(this)
            .setTitle("Undo all skips and changes?")
            .setMessage("Every skipped word, line and scene comes back, and all wording goes back to the original.")
            .setPositiveButton("Undo all") { _, _ -> edit("undoAll", -1, -1, -1, true, null) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** One place for every change to the script, from this screen or from a remote page. */
    private fun edit(op: String, si: Int, ii: Int, wi: Int, value: Boolean, newText: String?) {
        val sc = script ?: return
        val fm = model ?: return
        val keep = fm.refFor(p)
        val scene = sc.scenes.getOrNull(si)
        val item = if (ii >= 0) scene?.items?.getOrNull(ii) else null
        when (op) {
            "skipWord" -> { item ?: return; if (value) item.skipWords.add(wi) else item.skipWords.remove(wi) }
            "skipItem" -> { item ?: return; item.skip = value }
            "skipScene" -> { scene ?: return; scene.skip = value }
            "text" -> {
                item ?: return; val t = newText?.trim() ?: return
                if (t.isEmpty()) return
                if (item.orig == null) item.orig = item.t
                item.t = t; item.skipWords.clear()
                if (item.orig == t) item.orig = null
            }
            "restore" -> { item ?: return; val o = item.orig ?: return; item.t = o; item.orig = null; item.skipWords.clear() }
            "undoAll" -> for (s in sc.scenes) {
                s.skip = false
                for (it in s.items) { it.skip = false; it.skipWords.clear(); it.orig?.let { o -> it.t = o }; it.orig = null }
            }
            else -> return
        }
        val y = scroll.scrollY
        val nm = FollowModel(sc)
        model = nm
        render(nm)
        setupScenes(nm)
        p = -1
        val np = if (keep != null) nm.posFor(keep.first, keep.second, keep.third) else nm.E.size
        setPos(np, smooth = false, force = false)
        text.post { scroll.scrollTo(0, y) }
        saveScript()
        broadcastScript()
        restartListening()   // the vocabulary may have changed
    }

    // ---------------- sharing ----------------
    private fun saveNewCredentials() {
        online.newCredentials()
        prefs.edit().putString("room", online.room).putString("key", online.keyB64Url).apply()
    }

    private val stateTask = Runnable { sendState() }
    private fun scheduleState(delay: Long = 120) {
        handler.removeCallbacks(stateTask)
        handler.postDelayed(stateTask, delay)
    }

    private fun stateJson(): String {
        val o = JSONObject()
        o.put("type", "state")
        val fm = model
        val r = fm?.refFor(p)
        o.put("now", if (r == null) JSONObject.NULL else JSONArray(listOf(r.first, r.second, r.third)))
        viewRef()?.let { o.put("view", JSONArray(it.toList())) }
        o.put("paused", paused)
        o.put("font", fontScale.toDouble())
        o.put("status", statusText)
        o.put("live", statusLive)
        o.put("title", titleView.text.toString())
        return o.toString()
    }

    private fun scriptJson(): String {
        val o = JSONObject()
        o.put("type", "script")
        val sc = script
        o.put("script", if (sc == null) JSONObject.NULL else JSONObject(sc.toJson()))
        return o.toString()
    }

    private fun sharing() = local.running || online.wanted
    private fun broadcast(json: String) {
        if (local.running) local.send(json)
        if (online.wanted) online.send(json)
    }
    private fun sendState() { if (sharing()) broadcast(stateJson()) }
    private fun broadcastScript() { if (sharing()) { broadcast(scriptJson()); scheduleState() } }

    override fun onRemoteJoined() {
        runOnUiThread { broadcast(scriptJson()); broadcast(stateJson()) }
    }

    override fun onRemoteStatus() {
        runOnUiThread { refreshShare?.invoke() }
    }

    override fun onRemoteMessage(json: String) {
        runOnUiThread {
            try {
                val o = JSONObject(json)
                val fm = model
                fun ref(k: String): IntArray? = o.optJSONArray(k)?.let { a -> IntArray(a.length()) { a.optInt(it) } }
                when (o.optString("type")) {
                    "hello" -> onRemoteJoined()
                    "jump" -> { val r = ref("ref") ?: return@runOnUiThread; fm ?: return@runOnUiThread
                        tail.clear(); setPos(fm.posFor(r[0], r.getOrElse(1) { 0 }, r.getOrElse(2) { 0 }), force = true) }
                    "step" -> stepLine(if (o.optInt("dir") < 0) -1 else 1)
                    "pause" -> setPaused(o.optBoolean("value", !paused))
                    "font" -> changeFont(o.optDouble("delta", 0.0).toFloat())
                    "scroll" -> { val r = ref("ref") ?: return@runOnUiThread
                        charFor(r[0], r.getOrElse(1) { -1 }, r.getOrElse(2) { -1 })?.let { scrollToChar(it, true) } }
                    "edit" -> edit(o.optString("op"), o.optInt("si", -1), o.optInt("ii", -1), o.optInt("wi", -1),
                        o.optBoolean("value", true), if (o.has("t")) o.optString("t") else null)
                }
            } catch (_: Exception) {}
        }
    }

    private fun showShare() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), dp(8))
        fun label(s: String, size: Float = 15f, bold: Boolean = false, color: Int = TEXT): TextView {
            val t = TextView(this); t.text = s; t.setTextColor(color); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) t.setTypeface(null, Typeface.BOLD)
            t.setPadding(0, dp(6), 0, dp(4)); box.addView(t); return t
        }
        fun row(vararg bs: Button) {
            val r = LinearLayout(this); r.orientation = LinearLayout.HORIZONTAL; r.setPadding(0, dp(4), 0, dp(4))
            bs.forEachIndexed { i, b -> r.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { if (i < bs.size - 1) it.marginEnd = dp(6) }) }
            box.addView(r)
        }
        label("Anyone with a link can see your screen and move, scroll and edit the script while that link is turned on.", 13f, color = MUTED)

        label("Same Wi-Fi or your hotspot", 16f, true)
        label("Works without internet. They need to be on the same Wi-Fi as this phone, or connected to its hotspot.", 13f, color = MUTED)
        val localStatus = label("", 14f)
        val localToggle = button("") {
            if (local.running) local.stop() else local.start()
            prefs.edit().putBoolean("shareLocal", local.running).apply()
            handler.postDelayed({ refreshShare?.invoke() }, 300)
        }
        val localSend = button("Send link") {
            val ip = LocalServer.localAddresses().firstOrNull()
            if (ip == null) toast("This phone isn't on Wi-Fi or a hotspot") else sendLink("http://$ip:${local.port}/")
        }
        row(localToggle, localSend)

        label("Anywhere online", 16f, true)
        label("Needs internet on both ends. It goes through a free public relay, but everything is scrambled with a key that's only in your link.", 13f, color = MUTED)
        val onlineStatus = label("", 14f)
        val onlineToggle = button("") {
            if (online.wanted) online.stop() else online.start()
            prefs.edit().putBoolean("shareOnline", online.wanted).apply()
            refreshShare?.invoke()
        }
        val onlineSend = button("Send link") { sendLink(online.link()) }
        row(onlineToggle, onlineSend)
        row(button("Make a new link (the old one stops working)") {
            val was = online.wanted
            online.stop(); saveNewCredentials()
            if (was) handler.postDelayed({ online.start(); refreshShare?.invoke() }, 800)
            toast("New online link made. Send it again to anyone who should have it.")
            refreshShare?.invoke()
        })

        refreshShare = {
            val ips = LocalServer.localAddresses()
            localToggle.text = if (local.running) "Turn off" else "Turn on"
            localStatus.text = when {
                !local.running -> "Off"
                ips.isEmpty() -> "On, but this phone isn't on Wi-Fi or a hotspot"
                else -> "On at http://${ips.first()}:${local.port}/" + (if (local.viewers > 0) "\n${local.viewers} connected" else "")
            }
            onlineToggle.text = if (online.wanted) "Turn off" else "Turn on"
            onlineStatus.text = when {
                !online.wanted -> "Off"
                online.connected -> "On and connected"
                else -> "Connecting..."
            }
        }
        refreshShare?.invoke()
        val sv = ScrollView(this); sv.addView(box)
        shareDialog = AlertDialog.Builder(this)
            .setTitle("Share my screen")
            .setView(sv)
            .setPositiveButton("Done", null)
            .setOnDismissListener { refreshShare = null }
            .show()
    }

    private fun sendLink(link: String) {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "text/plain"
        i.putExtra(Intent.EXTRA_TEXT, link)
        try { startActivity(Intent.createChooser(i, "Send the link")) } catch (e: Exception) { toast(link) }
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
                dest.mkdirs()
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
        val was = speech != null
        stopListening()
        if (was || (!paused && visible)) startListening()
    }

    private fun setPaused(v: Boolean) {
        paused = v
        pauseBtn.text = if (paused) "Resume" else "Pause"
        if (paused) {
            stopListening()
            setStatus("Paused", false)
        } else {
            tail.clear()
            startListening()
        }
        scheduleState()
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
        local.stop()
        online.stop()
        try { voskModel?.close() } catch (_: Exception) {}
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
