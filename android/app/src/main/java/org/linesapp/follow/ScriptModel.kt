package org.linesapp.follow

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Item(val cue: String?, val t: String, val u: String?)
class Scene(val id: String, val title: String, val pre: Boolean, val items: List<Item>)
class Script(val title: String, val scenes: List<Scene>)

class DispWord(val w: String, val from: Int, val to: Int) {
    var charStart = 0
    var charEnd = 0
}

class LineUnit(val id: String, val sceneIdx: Int, val text: String, val idx: Int) {
    var start = 0
    var end = 0
    val disp = ArrayList<DispWord>()
    var anchorChar = -1
}

/** Text normalisation, the same rules as the web version of Lines. */
object Words {
    private val ONES = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen")
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val ALIAS = mapOf("okay" to "ok", "mister" to "mr", "hr" to "hour", "hrs" to "hours", "ya" to "you",
        "gonna" to "going", "cause" to "because", "cos" to "because")
    val STOP: Set<String> = ("a an the and of to so but then in on at is it its i im me my we our you your they them " +
        "their he she his her that this these those as or if for with be been was are am do did dont just oh well now " +
        "yes no not by from up all").split(" ").toSet()
    val FILLER = setOf("um", "uh", "er", "erm", "ah", "hmm", "mm", "uhm")

    fun numWords(n: Int): String = when {
        n < 20 -> ONES[n]
        n < 100 -> TENS[n / 10] + (if (n % 10 != 0) " " + ONES[n % 10] else "")
        n < 1000 -> ONES[n / 100] + " hundred" + (if (n % 100 != 0) " and " + numWords(n % 100) else "")
        n < 10000 -> numWords(n / 1000) + " thousand" + (if (n % 1000 != 0) " " + numWords(n % 1000) else "")
        else -> n.toString()
    }

    private val reApos = Regex("[’‘`]")
    private val reQuotes = Regex("[“”\"«»]")
    private val reDash = Regex("[–—]")
    private val reSplit = Regex("[\\s\\-/]+")
    private val reKeep = Regex("[^a-z0-9'*]")
    private val rePart = Regex("\\d+|[a-z*]+")

    fun toks(input: String): List<String> {
        val out = ArrayList<String>()
        val s = input.lowercase().replace(reApos, "'").replace(reQuotes, " ").replace("…", " ").replace(reDash, " ")
        for (p0 in s.split(reSplit)) {
            val piece = p0.replace(reKeep, "").replace("'", "")
            if (piece.isEmpty()) continue
            for (m in rePart.findAll(piece)) {
                val r = m.value
                if (r.all { it.isDigit() }) {
                    val n = if (r.length > 9) -1 else r.toInt()
                    if (n < 0) { out.add(r); continue }
                    if (r.length == 4 && n in 1100..2099 && n % 100 != 0) {
                        out.addAll((numWords(n / 100) + " " + numWords(n % 100)).split(" "))
                    } else {
                        out.addAll(numWords(n).split(" ").filter { it != "and" || n < 100 })
                    }
                } else if (r == "mph") {
                    out.addAll(listOf("miles", "per", "hour"))
                } else {
                    out.add(ALIAS[r] ?: r)
                }
            }
        }
        return out
    }

    /** Words in the form the speech recogniser uses (keeps apostrophes), for the vocabulary list. */
    fun vocab(input: String): List<String> {
        val out = ArrayList<String>()
        val s = input.lowercase().replace(reApos, "'").replace(reDash, " ")
        for (p0 in s.split(Regex("[^a-z0-9']+"))) {
            val w = p0.trim('\'')
            if (w.isEmpty()) continue
            if (w.all { it.isDigit() }) out.addAll(toks(w)) else out.add(w)
        }
        return out
    }

    private fun lev(a: String, b: String): Int {
        val m = a.length; val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        var p = IntArray(n + 1) { it }
        for (i in 1..m) {
            val c = IntArray(n + 1); c[0] = i
            for (j in 1..n) c[j] = min(min(p[j] + 1, c[j - 1] + 1), p[j - 1] + (if (a[i - 1] == b[j - 1]) 0 else 1))
            p = c
        }
        return p[n]
    }

    /** a = expected script word, b = heard word. */
    fun eq(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.isEmpty() || b.isEmpty()) return false
        if (b.contains('*') && b[0] == a[0]) return true
        if (a.length >= 4 && b.length >= 4 && 1.0 - lev(a, b).toDouble() / max(a.length, b.length) >= 0.75) return true
        if (a.length >= 3 && b.length >= 3 && (a + "s" == b || b + "s" == a)) return true
        return false
    }
}

object ScriptLoader {
    fun parse(bytes: ByteArray): Script {
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) return fromLines(docxParagraphs(bytes), "My script")
        val txt = String(bytes, Charsets.UTF_8).removePrefix("﻿").trim()
        if (txt.startsWith("{")) return fromJson(JSONObject(txt))
        return fromLines(txt.split(Regex("\\r?\\n")), "My script")
    }

    private fun fromJson(o: JSONObject): Script {
        val scenesArr: JSONArray = o.getJSONArray("scenes")
        val scenes = ArrayList<Scene>()
        var auto = 0
        for (i in 0 until scenesArr.length()) {
            val sc = scenesArr.getJSONObject(i)
            val itemsArr = sc.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<Item>()
            for (k in 0 until itemsArr.length()) {
                val it = itemsArr.optJSONObject(k) ?: continue
                val t = it.optString("t", "")
                val cue = it.optString("cue", "")
                if (cue.isNotEmpty()) items.add(Item(cue, t, null))
                else items.add(Item(null, t, if (it.has("u")) it.get("u").toString() else "auto${++auto}"))
            }
            scenes.add(Scene(sc.optString("id", (i + 1).toString()), sc.optString("title", ""), sc.optBoolean("pre", false), items))
        }
        return Script(o.optString("title", "My script"), scenes)
    }

    private fun fromLines(lines: List<String>, title: String): Script {
        var n = 0
        val items = lines.map { it.trim() }.filter { it.isNotEmpty() }.map { Item(null, it, (++n).toString()) }
        return Script(title, listOf(Scene("1", "Script", false, items)))
    }

    private fun docxParagraphs(bytes: ByteArray): List<String> {
        var xml = ""
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name == "word/document.xml") { xml = z.readBytes().toString(Charsets.UTF_8); break }
            }
        }
        val paras = ArrayList<String>()
        val pRe = Regex("<w:p[ >].*?</w:p>", RegexOption.DOT_MATCHES_ALL)
        val tRe = Regex("<w:t(?: [^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
        for (pm in pRe.findAll(xml)) {
            val sb = StringBuilder()
            for (tm in tRe.findAll(pm.value)) sb.append(tm.groupValues[1])
            val s = sb.toString().replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'").trim()
            if (s.isNotEmpty()) paras.add(s)
        }
        return paras
    }
}

/** Your lines as one flat list of words, plus the matcher that keeps a cursor in them. */
class FollowModel(val script: Script) {
    val units = ArrayList<LineUnit>()
    val E = ArrayList<String>()
    private val eUnit = ArrayList<Int>()

    init {
        script.scenes.forEachIndexed { si, sc ->
            for (it in sc.items) {
                if (it.cue != null) continue
                val u = LineUnit(it.u ?: "", si, it.t, units.size)
                u.start = E.size
                for (w in it.t.split(Regex("\\s+")).filter { s -> s.isNotEmpty() }) {
                    val tk = Words.toks(w)
                    u.disp.add(DispWord(w, E.size, E.size + tk.size))
                    for (t in tk) { E.add(t); eUnit.add(u.idx) }
                }
                u.end = E.size
                units.add(u)
            }
        }
    }

    fun unitAt(p: Int): LineUnit? = if (p < 0 || p >= E.size) null else units[eUnit[p]]

    fun grammarJson(): String {
        val set = LinkedHashSet<String>()
        for (u in units) set.addAll(Words.vocab(u.text))
        val arr = JSONArray()
        for (w in set) arr.put(w)
        arr.put("[unk]")
        return arr.toString()
    }

    /**
     * Local alignment of the last few heard words against the script around the cursor p.
     * Returns the new cursor position, or null to stay put.
     */
    fun locate(T: List<String>, p: Int): Int? {
        val n = T.size
        if (n == 0) return null
        val lo = max(0, p - 40)
        val hi = min(E.size, p + 80)
        val m = hi - lo
        if (m <= 0) return null
        val sc = Array(n + 1) { DoubleArray(m + 1) }
        val mt = Array(n + 1) { IntArray(m + 1) }
        val ct = Array(n + 1) { IntArray(m + 1) }
        val nm = Array(n + 1) { IntArray(m + 1) }
        val nc = Array(n + 1) { IntArray(m + 1) }
        val endHit = Array(n + 1) { BooleanArray(m + 1) }
        for (i in 1..n) for (j in 1..m) {
            val ei = lo + j - 1
            val e = E[ei]
            val hit = Words.eq(e, T[i - 1])
            val content = e !in Words.STOP
            val w = if (content) 2.0 else 1.0
            val fresh = ei >= p
            var v = 0.0; var si = -1; var sj = -1; var add = 0
            val dg = sc[i - 1][j - 1] + (if (hit) w else -1.5)
            if (dg > v) { v = dg; si = i - 1; sj = j - 1; add = if (hit) 1 else 0 }
            val up = sc[i - 1][j] - 1.0          // extra word heard
            if (up > v) { v = up; si = i - 1; sj = j; add = 0 }
            val lf = sc[i][j - 1] - 0.8          // script word skipped
            if (lf > v) { v = lf; si = i; sj = j - 1; add = 0 }
            sc[i][j] = v
            if (si >= 0) {
                mt[i][j] = mt[si][sj] + add
                ct[i][j] = ct[si][sj] + (if (add == 1 && content) 1 else 0)
                nm[i][j] = nm[si][sj] + (if (add == 1 && fresh) 1 else 0)
                nc[i][j] = nc[si][sj] + (if (add == 1 && fresh && content) 1 else 0)
                endHit[i][j] = add == 1 && si == i - 1 && sj == j - 1
            }
        }
        var bestRank = Double.NEGATIVE_INFINITY
        var bestNp = -1
        for (i in intArrayOf(n, n - 1)) {
            if (i < 1) continue
            for (j in 1..m) {
                val v = sc[i][j] - (if (i < n) 0.5 else 0.0)
                if (v <= 0 || !endHit[i][j]) continue
                val np = lo + j
                val d = np - p
                val ok = when {
                    d == 0 -> v >= 3
                    d in 1..12 -> v >= 3.5 && nm[i][j] >= 2 && nc[i][j] >= 1   // normal forward movement
                    d > 12 -> v >= 7 && nm[i][j] >= 4 && nc[i][j] >= 2          // skipped ahead
                    else -> d <= -3 && v >= 9 && mt[i][j] >= 5 && ct[i][j] >= 3 // restarted an earlier line
                }
                if (!ok) continue
                val rank = v - 0.06 * abs(d) - (if (d < 0) 2.0 else 0.0)
                if (rank > bestRank) { bestRank = rank; bestNp = np }
            }
        }
        return if (bestNp >= 0 && bestNp != p) bestNp else null
    }
}
