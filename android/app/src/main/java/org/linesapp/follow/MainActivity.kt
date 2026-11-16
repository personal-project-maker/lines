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
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
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
import java.util.Locale
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
    private val LOOKED = Color.parseColor("#FF7A7A")

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
    private var editMode = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var readOn = false
    private var lastArrival = -1
    private var readingBlock = 0
    private var blockCounter = 0
    private var afterReadPos = -1
    private var showLevel = 0       // 0 whole script, 1 three lines, 2 one line, 3 a few words, 4 one word
    private lateinit var showBtn: Button
    private val hideSpans = ArrayList<Any>()
    private var runs = JSONArray()
    private var runsSavedAt = 0L
    private lateinit var performBtn: Button
    private lateinit var editBtn: Button
    private lateinit var scrollBg: GradientDrawable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        prefs = getSharedPreferences("lines", MODE_PRIVATE)
        fontScale = prefs.getFloat("fontScale", 1f)
        showLevel = prefs.getInt("show", 0)
        readOn = prefs.getBoolean("readOn", false)
        runs = try { JSONArray(prefs.getString("runs", "[]")) } catch (e: Exception) { JSONArray() }
        local = LocalServer(this, this)
        val room = prefs.getString("room", null)
        val key = prefs.getString("key", null)
        if (room != null && key != null) online.setCredentials(room, key) else saveNewCredentials()
        buildUi()
        loadSavedScript()
        loadSpeechModel()
        initTts()
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

        // Perform: touching only scrolls, a tap moves you there. Edit: a tap opens skip and wording options.
        val modeRow = LinearLayout(this)
        modeRow.orientation = LinearLayout.HORIZONTAL
        modeRow.setPadding(0, 0, 0, dp(8))
        performBtn = button("Perform") { setEditMode(false) }
        editBtn = button("Edit") { setEditMode(true) }
        performBtn.minHeight = dp(44); editBtn.minHeight = dp(44)
        modeRow.addView(performBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = dp(6) })
        modeRow.addView(editBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = dp(6) })
        showBtn = button("") { chooseShow() }
        showBtn.minHeight = dp(44)
        modeRow.addView(showBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
        updateShowBtn()
        root.addView(modeRow)

        scroll = ScrollView(this)
        scrollBg = GradientDrawable(); scrollBg.cornerRadius = dp(18).toFloat(); scrollBg.setColor(PANEL)
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
                if (editMode) { showEditMenu(off); return true }
                tapToJump(off)
                return true
            }
        })
        taps.setIsLongpressEnabled(false)
        // Take the whole touch so the tap is seen when the finger lifts; the scroll box still takes over drags.
        text.setOnTouchListener { _, ev -> taps.onTouchEvent(ev); true }

        val looked = button("I looked") { markLooked() }
        looked.contentDescription = "I looked at the script. Marks this line as not yet learned"
        (looked.background as GradientDrawable).setColor(Color.parseColor("#5A2330"))
        looked.minHeight = dp(64)
        looked.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        root.addView(looked, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(10) })

        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.HORIZONTAL
        bottom.setPadding(0, dp(8), 0, 0)
        val blp = { LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = dp(6) } }
        bottom.addView(button("Back a line") { stepLine(-1) }, blp())
        pauseBtn = button("Pause", primary = true) { setPaused(!paused) }
        bottom.addView(pauseBtn, blp())
        bottom.addView(button("Forward a line") { stepLine(1) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bottom)

        setContentView(root)
        setEditMode(false)

        scroll.post {
            val h = scroll.height
            text.setPadding(dp(18), (h * 0.3).toInt(), dp(18), (h * 0.65).toInt())
            setPos(p, smooth = false, force = true)
        }
    }

    private fun setEditMode(on: Boolean) {
        editMode = on
        fun style(b: Button, active: Boolean) {
            val bg = GradientDrawable(); bg.cornerRadius = dp(14).toFloat()
            bg.setColor(if (active) ACCENT else PANEL2)
            b.background = bg
            b.setTextColor(if (active) Color.parseColor("#1B1608") else TEXT)
            b.contentDescription = b.text.toString() + if (active) ", selected" else ""
        }
        style(performBtn, !on); style(editBtn, on)
        scrollBg.setStroke(if (on) dp(3) else 0, ACCENT)
        if (on) toast("Edit mode: tap a word or line to skip it or change it")
    }

    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Load a script")
        m.menu.add(0, 2, 1, "Make a script (opens the script maker)")
        m.menu.add(0, 8, 2, "Read other characters aloud").setCheckable(true).setChecked(readOn)
        m.menu.add(0, 9, 2, "Reading aloud settings")
        m.menu.add(0, 3, 2, "Share my screen")
        m.menu.add(0, 5, 3, "Lines I've looked at")
        m.menu.add(0, 6, 4, "My progress")
        m.menu.add(0, 7, 4, "Start a new run")
        m.menu.add(0, 4, 5, "Undo all skips and changes")
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> pickScript()
                2 -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MAKER_URL))) } catch (e: Exception) { toast("Couldn't open the browser") }
                3 -> showShare()
                4 -> confirmUndoAll()
                5 -> showLooked()
                6 -> showProgress()
                7 -> startNewRun()
                8 -> setReadOn(!readOn)
                9 -> readingSettings()
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
                    if (it.readAloud) {
                        val rs = sb.length
                        sb.append("\uD83D\uDD0A ")
                        sb.setSpan(RelativeSizeSpan(0.6f), rs, sb.length - 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    if (it.looked > 0) {
                        val ms = sb.length
                        sb.append(if (it.looked > 1) "\u25CF${it.looked} " else "\u25CF ")
                        sb.setSpan(ForegroundColorSpan(LOOKED), ms, sb.length - 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(RelativeSizeSpan(0.6f), ms, sb.length - 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
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
    private fun setPos(np0: Int, smooth: Boolean = true, force: Boolean = false, read: Boolean = false) {
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
        applyHiding(sp, now)
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
        if (read && readOn && p != lastArrival) maybeRead()
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
            cancelReading(false)
            setPos(if (p >= fm.E.size) fm.E.size else if (k < fm.units.size) fm.units[k].start else fm.E.size, read = true)
        } else {
            cancelReading(false)
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
        val np = fm.locate(T, p)
        if (np != null) setPos(np, read = true)
        // You paused with at most two words of the line left unheard: treat the line as finished,
        // so the next character's line can be read without waiting for a word the phone missed.
        if (final && readOn && readingBlock == 0) {
            val u = fm.unitAt(minOf(p, fm.E.size - 1))
            if (u != null && p > u.start && u.end - p in 1..2) setPos(u.end, read = true)
        }
        if (np == null) return
        fm.unitAt(minOf(p, fm.E.size - 1))?.let { noteRehearsed(it.sceneIdx, 0) }
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
            if (item.cue == null) add(if (item.readAloud) "Stop reading this line for me" else "Read this line aloud for me") { edit("readAloud", si, ii, -1, !item.readAloud, null) }
            add("Change the wording") { askWording(si, ii) }
            if (item.looked > 0) add("Clear the \"looked\" mark (${item.looked})") { setLooked(si, ii, 0) }
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

    // ---------------- tapping to jump ----------------
    private fun tapToJump(off: Int) {
        val fm = model ?: return
        val sc = script ?: return
        tail.clear()
        cancelReading(false)
        val w = wordAtChar(off)
        val a = if (w != null) intArrayOf(0, w.si, w.ii) else anchorAtChar(off) ?: return
        val si = a[1]; val ii = a[2]
        val item = if (ii >= 0) sc.scenes[si].items[ii] else null
        if (item?.cue != null) { tapCue(si, ii); return }
        val np = fm.posFor(si, max(0, ii), 0)   // start of the line, or of the scene
        lastArrival = np
        setPos(np, force = true)
    }

    // ---------------- reading lines aloud ----------------
    private val ME = "(me)"

    private fun initTts() {
        try { tts?.shutdown() } catch (_: Exception) {}
        ttsReady = false
        tts = TextToSpeech(this) { st ->
            if (st != TextToSpeech.SUCCESS) runOnUiThread { if (readOn) setStatus("Couldn't start the phone's text-to-speech", false) }
            if (st == TextToSpeech.SUCCESS) {
                ttsReady = true
                try {
                    val r = tts?.setLanguage(Locale.UK) ?: 0
                    if (r < 0) tts?.setLanguage(Locale.US)
                } catch (_: Exception) {}
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (utteranceId?.endsWith("-end") == true) runOnUiThread { blockDone(utteranceId) } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { if (utteranceId?.endsWith("-end") == true) runOnUiThread { blockDone(utteranceId) } }
                })
            }
        }
    }

    private fun setReadOn(on: Boolean) {
        readOn = on
        prefs.edit().putBoolean("readOn", on).apply()
        if (!on) cancelReading(true)
        if (on && !ttsReady) { toast("The phone's voices aren't ready yet. If this keeps happening, check Android Settings, then Text-to-speech."); initTts() }
        else toast(if (on) "Reading other characters aloud" else "Stopped reading other characters")
        lastArrival = -1
        if (on) maybeRead() else lastArrival = p
        scheduleState()
    }

    private fun flatItems(): List<IntArray> {
        val out = ArrayList<IntArray>()
        script?.scenes?.forEachIndexed { si, s -> s.items.indices.forEach { ii -> out.add(intArrayOf(si, ii)) } }
        return out
    }

    private fun unitOf(si: Int, ii: Int): LineUnit? = model?.units?.firstOrNull { it.sceneIdx == si && it.itemIdx == ii }
    private fun isSpoken(si: Int, ii: Int): Boolean { val u = unitOf(si, ii); return u != null && u.end > u.start }

    /** Other characters' lines between two places in the script, leaving out skipped ones. */
    private fun cuesBetween(from: Int, until: Int, flat: List<IntArray>): ArrayList<Pair<String, String>> {
        val sc = script ?: return ArrayList()
        val out = ArrayList<Pair<String, String>>()
        for (k in max(0, from) until minOf(until, flat.size)) {
            val scene = sc.scenes[flat[k][0]]; val it = scene.items[flat[k][1]]
            if (it.cue != null && !it.skip && !scene.skip && it.t.isNotBlank()) out.add(it.cue to it.t)
        }
        return out
    }

    /** You've just reached the start of one of your lines (or the end): read what comes before it. */
    private fun maybeRead() {
        val fm = model ?: return
        val sc = script ?: return
        lastArrival = p
        val u = fm.units.firstOrNull { it.start == p && it.end > it.start }
        if (u == null && p != fm.E.size) return
        val flat = flatItems()
        val fu = if (u != null) flat.indexOfFirst { it[0] == u.sceneIdx && it[1] == u.itemIdx } else flat.size
        var fp = -1
        for (k in fu - 1 downTo 0) { val it = sc.scenes[flat[k][0]].items[flat[k][1]]; if (it.cue == null && isSpoken(flat[k][0], flat[k][1])) { fp = k; break } }
        val parts = cuesBetween(fp + 1, fu, flat)
        var after = -1
        if (u != null) { val it = sc.scenes[u.sceneIdx].items[u.itemIdx]; if (it.readAloud) { parts.add(ME to it.t); after = u.end } }
        if (parts.isEmpty()) return
        val firstAnchor = if (fp + 1 < flat.size) charFor(flat[fp + 1][0], flat[fp + 1][1], -1) ?: -1 else -1
        speakBlock(parts, firstAnchor, after)
    }

    /** A tap on another character's line: go there and read from it. */
    private fun tapCue(si: Int, ii: Int) {
        val fm = model ?: return
        val sc = script ?: return
        val flat = flatItems()
        val k = flat.indexOfFirst { it[0] == si && it[1] == ii }
        if (k < 0) return
        var fu = flat.size
        for (j in k until flat.size) { val it = sc.scenes[flat[j][0]].items[flat[j][1]]; if (it.cue == null && isSpoken(flat[j][0], flat[j][1])) { fu = j; break } }
        val target = if (fu < flat.size) fm.posFor(flat[fu][0], flat[fu][1], 0) else fm.E.size
        lastArrival = target
        setPos(target, smooth = false)
        val anchor = charFor(si, ii, -1) ?: -1
        if (!readOn) { if (anchor >= 0) scrollToChar(anchor, true); return }
        val parts = cuesBetween(k, fu, flat)
        var after = -1
        if (fu < flat.size) { val it = sc.scenes[flat[fu][0]].items[flat[fu][1]]; if (it.readAloud) { parts.add(ME to it.t); after = unitOf(flat[fu][0], flat[fu][1])?.end ?: -1 } }
        speakBlock(parts, anchor, after)
    }

    private fun speakBlock(parts: List<Pair<String, String>>, anchorChar: Int, after: Int) {
        val t = tts
        if (!ttsReady || t == null || parts.isEmpty()) {
            if (parts.isNotEmpty()) setStatus("Couldn't read aloud: the phone's text-to-speech isn't working", false)
            if (after >= 0) setPos(after, read = true)
            return
        }
        stopListening()
        t.stop()
        val id = ++blockCounter
        readingBlock = id
        afterReadPos = after
        val rate = prefs.getFloat("rate", 1f)
        val gap = prefs.getInt("gap", 500).toLong()
        parts.forEachIndexed { i, (who, words) ->
            if (gap > 0) t.playSilentUtterance(gap, TextToSpeech.QUEUE_ADD, "b$id-gap$i")
            voiceFor(who)?.let { v -> try { t.setVoice(v) } catch (_: Exception) {} }
            t.setPitch(pitchFor(who))
            t.setSpeechRate(rate)
            t.speak(words, TextToSpeech.QUEUE_ADD, null, if (i == parts.size - 1) "b$id-end" else "b$id-$i")
        }
        val who = parts.first().first
        setStatus(if (who == ME) "Reading your line for you..." else "Reading ${who}'s line...", false)
        if (anchorChar >= 0) scrollToChar(anchorChar, true)
    }

    private fun blockDone(uid: String) {
        if (readingBlock == 0 || !uid.startsWith("b$readingBlock-")) return
        readingBlock = 0
        val a = afterReadPos
        afterReadPos = -1
        if (a >= 0) setPos(a, read = true)
        if (readingBlock == 0) startListening()
    }

    /** Stops reading (if it is) and goes back to listening unless asked not to. */
    private fun cancelReading(resume: Boolean) {
        if (readingBlock == 0) return
        readingBlock = 0
        afterReadPos = -1
        try { tts?.stop() } catch (_: Exception) {}
        if (resume) startListening()
    }

    private fun voices(): List<Voice> = try {
        tts?.voices?.filter {
            it.locale.language == "en" && !it.isNetworkConnectionRequired &&
                it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true
        }?.sortedBy { it.name } ?: emptyList()
    } catch (e: Exception) { emptyList() }

    private fun characters(): List<String> {
        val out = LinkedHashSet<String>()
        script?.scenes?.forEach { s -> s.items.forEach { it.cue?.let { c -> out.add(c) } } }
        return out.toList()
    }

    private val PITCHES = floatArrayOf(1.0f, 0.85f, 1.15f, 0.95f, 1.08f)
    private val PITCH_NAMES = arrayOf("normal", "lower", "higher")
    private val PITCH_VALUES = floatArrayOf(1.0f, 0.82f, 1.18f)

    private fun voiceFor(who: String): Voice? {
        val vs = voices()
        if (vs.isEmpty()) return null
        prefs.getString("voice:$who", null)?.let { n -> vs.firstOrNull { it.name == n }?.let { return it } }
        val i = if (who == ME) 0 else characters().indexOf(who) + 1
        return vs[max(0, i) % vs.size]
    }

    private fun pitchFor(who: String): Float {
        val saved = prefs.getFloat("pitch:$who", -1f)
        if (saved > 0) return saved
        val i = if (who == ME) 0 else characters().indexOf(who) + 1
        return PITCHES[max(0, i) % PITCHES.size]
    }

    private fun readingSettings() {
        val rate = prefs.getFloat("rate", 1f)
        val gap = prefs.getInt("gap", 500)
        val items = arrayOf(
            (if (readOn) "Turn off" else "Turn on") + " reading other characters",
            "Speed: " + when { rate < 0.8f -> "slow"; rate < 0.95f -> "a bit slow"; rate < 1.1f -> "normal"; rate < 1.3f -> "a bit fast"; else -> "fast" },
            "Pause before each line: " + if (gap == 0) "none" else "${gap / 1000.0} seconds",
            "Choose a voice for each character",
            "Voice for lines read for me",
            "Test the voices")
        AlertDialog.Builder(this)
            .setTitle("Reading aloud")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> setReadOn(!readOn)
                    1 -> pick("Speed", arrayOf("Slow", "A bit slow", "Normal", "A bit fast", "Fast")) { i ->
                        prefs.edit().putFloat("rate", floatArrayOf(0.7f, 0.85f, 1f, 1.2f, 1.45f)[i]).apply(); readingSettings() }
                    2 -> pick("Pause before each line", arrayOf("None", "Half a second", "1 second", "2 seconds", "3 seconds")) { i ->
                        prefs.edit().putInt("gap", intArrayOf(0, 500, 1000, 2000, 3000)[i]).apply(); readingSettings() }
                    3 -> { val cs = characters(); if (cs.isEmpty()) toast("There are no other characters in this script")
                        else pick("Whose voice?", cs.toTypedArray()) { i -> chooseVoice(cs[i]) } }
                    4 -> chooseVoice(ME)
                    5 -> { val parts = characters().take(4).map { it to "This is how $it will sound." }.toMutableList()
                        parts.add(ME to "And this is how your lines will sound when I read them for you.")
                        speakBlock(parts, -1, -1) }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun pick(title: String, options: Array<String>, then: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setItems(options) { _, w -> then(w) }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseVoice(who: String) {
        val vs = voices()
        if (vs.isEmpty()) { toast("No offline English voices found. You can add them in Android Settings, under Text-to-speech."); return }
        val labels = ArrayList<String>(); val pairs = ArrayList<Pair<Voice, Int>>()
        vs.forEachIndexed { i, v ->
            val where = v.locale.displayCountry.ifBlank { v.locale.displayLanguage }
            for (pi in PITCH_NAMES.indices) { labels.add("Voice ${i + 1} ($where), ${PITCH_NAMES[pi]}"); pairs.add(v to pi) }
        }
        val name = if (who == ME) "your lines" else who
        pick("Voice for $name", labels.toTypedArray()) { i ->
            val (v, pi) = pairs[i]
            prefs.edit().putString("voice:$who", v.name).putFloat("pitch:$who", PITCH_VALUES[pi]).apply()
            speakBlock(listOf(who to "This is how ${if (who == ME) "your lines" else who} will sound."), -1, -1)
        }
    }

    // ---------------- show less of the script ----------------
    private val SHOW_NAMES = arrayOf("Whole script", "3 lines", "1 line", "A few words", "1 word")

    private fun updateShowBtn() {
        showBtn.text = "Show: " + SHOW_NAMES[showLevel].lowercase()
        showBtn.contentDescription = "Show: " + SHOW_NAMES[showLevel] + ". Tap to change how much of your script is shown"
    }

    private fun chooseShow() {
        AlertDialog.Builder(this)
            .setTitle("How much of your script to show")
            .setSingleChoiceItems(SHOW_NAMES, showLevel) { d, which -> setShow(which); d.dismiss() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setShow(level: Int) {
        showLevel = level.coerceIn(0, 4)
        prefs.edit().putInt("show", showLevel).apply()
        updateShowBtn()
        setPos(p, smooth = false, force = false)
        scheduleState()
    }

    /** Hides your upcoming words past the chosen amount. Cues and headings stay; hidden words keep their space. */
    private fun applyHiding(sp: Spannable, now: DispWord?) {
        for (h in hideSpans) sp.removeSpan(h)
        hideSpans.clear()
        val fm = model ?: return
        if (showLevel == 0 || now == null) return
        val nowIdx = words.indexOf(now)
        if (nowIdx < 0) return
        var endIdx = nowIdx
        when (showLevel) {
            1, 2 -> {
                var k = fm.units.indexOfFirst { it.sceneIdx == now.si && it.itemIdx == now.ii }
                if (k < 0) return
                var more = if (showLevel == 1) 2 else 0
                while (more > 0 && k + 1 < fm.units.size) { k++; if (fm.units[k].start < fm.units[k].end) more-- }
                val last = fm.units[k].disp.lastOrNull()
                if (last != null) endIdx = max(nowIdx, words.indexOf(last))
            }
            3 -> { var c = 0; var i = nowIdx; while (i < words.size && c < 4) { if (!words[i].skipped) { c++; endIdx = i }; i++ } }
            else -> endIdx = nowIdx
        }
        var i = endIdx + 1
        while (i < words.size) {
            val a = words[i]
            var j = i
            while (j + 1 < words.size && words[j + 1].si == a.si && words[j + 1].ii == a.ii) j++
            val span = ForegroundColorSpan(PANEL)
            sp.setSpan(span, a.charStart, words[j].charEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            hideSpans.add(span)
            i = j + 1
        }
    }

    // ---------------- progress over time ----------------

    private fun sceneKey(si: Int): String {
        val sc = script?.scenes?.getOrNull(si) ?: return "?"
        return (sc.id + " " + sc.title).trim()
    }

    private fun currentRun(forceNew: Boolean = false): JSONObject {
        val now = System.currentTimeMillis()
        val last = if (runs.length() > 0) runs.optJSONObject(runs.length() - 1) else null
        // Runs only change when you start a new one; the very first is made for you.
        if (!forceNew && last != null) return last
        val r = JSONObject(); r.put("start", now); r.put("end", now); r.put("scenes", JSONObject())
        runs.put(r)
        while (runs.length() > 60) runs.remove(0)
        return r
    }

    /** Notes that you rehearsed this scene in the current run, adding any looks. */
    private fun noteRehearsed(si: Int, looks: Int) {
        if (script == null) return
        val r = currentRun()
        val sc = r.optJSONObject("scenes") ?: JSONObject().also { r.put("scenes", it) }
        val k = sceneKey(si)
        sc.put(k, sc.optInt(k, 0) + looks)
        r.put("end", System.currentTimeMillis())
        val now = System.currentTimeMillis()
        if (looks > 0 || now - runsSavedAt > 10000) { prefs.edit().putString("runs", runs.toString()).apply(); runsSavedAt = now }
    }

    private fun startNewRun() {
        currentRun(forceNew = true)
        prefs.edit().putString("runs", runs.toString()).apply()
        toast("New run started. Looks from now on count towards this run.")
    }

    private fun showProgress() {
        val sc = script ?: return
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(8), dp(16), dp(8))
        fun label(s: String, size: Float, color: Int = TEXT, bold: Boolean = false): TextView {
            val t = TextView(this); t.text = s; t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size); t.setTextColor(color)
            if (bold) t.setTypeface(null, Typeface.BOLD); box.addView(t); return t
        }
        val recent = ArrayList<JSONObject>()
        for (i in max(0, runs.length() - 8) until runs.length()) runs.optJSONObject(i)?.let { r -> if ((r.optJSONObject("scenes")?.length() ?: 0) > 0) recent.add(r) }
        label("Each square is a run, oldest on the left, newest on the right. Start a new run from the Menu before each rehearsal. The number is how many times you looked. Green: none. Amber: 1 or 2. Red: 3 or more. Blank: not rehearsed in that run.", 13f, MUTED)
        if (recent.isEmpty()) label("\nNo runs yet. Start following and tap \"I looked\" whenever you need the script.", 15f)
        val fmt = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.UK)
        fun cell(row: LinearLayout, n: Int?, run: JSONObject) {
            val t = TextView(this)
            t.gravity = Gravity.CENTER
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            t.setTextColor(Color.parseColor("#1B1608"))
            val bg = GradientDrawable(); bg.cornerRadius = dp(6).toFloat()
            when {
                n == null -> { bg.setColor(Color.TRANSPARENT); bg.setStroke(dp(1), Color.parseColor("#3A3548")); t.text = "" }
                n == 0 -> { bg.setColor(OK); t.text = "0" }
                n <= 2 -> { bg.setColor(Color.parseColor("#FFB86B")); t.text = "$n" }
                else -> { bg.setColor(LOOKED); t.text = "$n" }
            }
            t.background = bg
            val when_ = fmt.format(java.util.Date(run.optLong("start")))
            t.contentDescription = if (n == null) "$when_: not rehearsed" else "$when_: looked $n times"
            row.addView(t, LinearLayout.LayoutParams(dp(34), dp(34)).also { it.marginEnd = dp(4) })
        }
        if (recent.isNotEmpty()) {
            label("\nAll scenes", 15f, TEXT, true)
            val totals = LinearLayout(this); totals.orientation = LinearLayout.HORIZONTAL
            for (r in recent) { val m = r.optJSONObject("scenes")!!; var t = 0; for (k in m.keys()) t += m.optInt(k); cell(totals, t, r) }
            box.addView(totals)
            label("Latest run: " + fmt.format(java.util.Date(recent.last().optLong("start"))), 12f, MUTED)
            for (si in sc.scenes.indices) {
                val k = sceneKey(si)
                if (recent.none { it.optJSONObject("scenes")?.has(k) == true }) continue
                label("\n" + k, 14f, TEXT, true)
                val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
                val seen = ArrayList<Int>()
                for (r in recent) { val m = r.optJSONObject("scenes")!!; val n = if (m.has(k)) m.optInt(k) else null; if (n != null) seen.add(n); cell(row, n, r) }
                box.addView(row)
                if (seen.size >= 2) label(if (seen.last() < seen.first()) "Getting there: ${seen.first()} looks at first, ${seen.last()} last time"
                    else if (seen.last() == 0 && seen.first() == 0) "Solid every time" else "${seen.first()} looks at first, ${seen.last()} last time", 12f, MUTED)
            }
        }
        val sv = ScrollView(this); sv.addView(box)
        AlertDialog.Builder(this)
            .setTitle("My progress")
            .setView(sv)
            .setPositiveButton("Close", null)
            .setNeutralButton("Start a new run") { _, _ -> startNewRun() }
            .setNegativeButton("Clear history") { _, _ ->
                AlertDialog.Builder(this).setTitle("Clear all your progress history?")
                    .setMessage("The red marks on lines stay. This only clears the runs.")
                    .setPositiveButton("Clear") { _, _ -> runs = JSONArray(); prefs.edit().putString("runs", "[]").apply() }
                    .setNegativeButton("Cancel", null).show()
            }
            .show()
    }

    // ---------------- lines you looked at ----------------
    /** Marks the line you're on now: the one you needed to look at. */
    private fun markLooked() {
        val fm = model ?: return
        val u = fm.unitAt(minOf(p, fm.E.size - 1)) ?: return
        val item = script?.scenes?.getOrNull(u.sceneIdx)?.items?.getOrNull(u.itemIdx) ?: return
        setLooked(u.sceneIdx, u.itemIdx, item.looked + 1)
        noteRehearsed(u.sceneIdx, 1)
        Toast.makeText(this, "Marked \"${shorten(item.t).take(40)}\"" + (if (item.looked > 1) " (${item.looked} times)" else ""), Toast.LENGTH_SHORT).show()
    }

    /** Changes only the marks: no need to rebuild the following or restart the microphone. */
    private fun setLooked(si: Int, ii: Int, n: Int) {
        val item = script?.scenes?.getOrNull(si)?.items?.getOrNull(ii) ?: return
        item.looked = max(0, n)
        item.lookedAt = if (n > 0) System.currentTimeMillis() else 0L
        redrawKeepingPlace()
    }

    private fun redrawKeepingPlace() {
        val fm = model ?: return
        val y = scroll.scrollY
        val keep = p
        render(fm)
        p = -1
        setPos(keep, smooth = false, force = false)
        text.post { scroll.scrollTo(0, y) }
        saveScript()
        broadcastScript()
    }

    private fun showLooked() {
        val sc = script ?: return
        val list = ArrayList<Triple<Int, Int, Item>>()
        sc.scenes.forEachIndexed { si, s -> s.items.forEachIndexed { ii, it -> if (it.cue == null && it.looked > 0) list.add(Triple(si, ii, it)) } }
        if (list.isEmpty()) { toast("No marks yet. Tap \"I looked\" whenever you need to look at the script."); return }
        list.sortWith(compareByDescending<Triple<Int, Int, Item>> { it.third.looked }.thenBy { it.first }.thenBy { it.second })
        val labels = list.map { (si, _, it) -> "${it.looked}x  ${sc.scenes[si].id}  ${shorten(it.t)}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Lines I've looked at (${list.size})")
            .setItems(labels) { _, which ->
                val (si, ii, _) = list[which]
                model?.let { m -> tail.clear(); setPos(m.posFor(si, ii, 0), force = true) }
            }
            .setNeutralButton("Clear all marks") { _, _ ->
                AlertDialog.Builder(this).setTitle("Clear all the marks?")
                    .setPositiveButton("Clear") { _, _ ->
                        for (s in sc.scenes) for (it in s.items) { it.looked = 0; it.lookedAt = 0 }
                        redrawKeepingPlace()
                    }.setNegativeButton("Cancel", null).show()
            }
            .setNegativeButton("Close", null)
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
            "readAloud" -> { item ?: return; if (item.cue != null) return; item.readAloud = value }
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
        o.put("show", showLevel)
        o.put("readOn", readOn)
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
                        tail.clear(); cancelReading(false)
                        val si = r[0]; val ii = r.getOrElse(1) { 0 }
                        val it = script?.scenes?.getOrNull(si)?.items?.getOrNull(ii)
                        if (it?.cue != null) tapCue(si, ii)
                        else { val np = fm.posFor(si, ii, r.getOrElse(2) { 0 }); lastArrival = np; setPos(np, force = true) } }
                    "step" -> stepLine(if (o.optInt("dir") < 0) -1 else 1)
                    "pause" -> setPaused(o.optBoolean("value", !paused))
                    "font" -> changeFont(o.optDouble("delta", 0.0).toFloat())
                    "scroll" -> { val r = ref("ref") ?: return@runOnUiThread
                        charFor(r[0], r.getOrElse(1) { -1 }, r.getOrElse(2) { -1 })?.let { scrollToChar(it, true) } }
                    "looked" -> markLooked()
                    "show" -> setShow(o.optInt("level", 0))
                    "read" -> setReadOn(o.optBoolean("value", !readOn))
                    "unlook" -> setLooked(o.optInt("si", -1), o.optInt("ii", -1), 0)
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
        if (paused || !visible || speech != null || readingBlock != 0) return
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
            cancelReading(false)
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
        cancelReading(false)
        visible = false
        stopListening()
        if (!paused && model != null) setStatus("Ready", false)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { tts?.shutdown() } catch (_: Exception) {}
        stopListening()
        local.stop()
        online.stop()
        try { voskModel?.close() } catch (_: Exception) {}
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
