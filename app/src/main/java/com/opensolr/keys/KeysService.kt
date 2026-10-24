package com.opensolr.keys

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.icu.text.BreakIterator
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/** The chevron under the keys that hides the keyboard, like the iPad key. */
class HideKeyView(ctx: android.content.Context, bg: Int, ink: Int, onTap: () -> Unit, private val forward: (View, android.view.MotionEvent) -> Boolean) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f * d; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND; color = ink
    }
    private val path = android.graphics.Path()
    private var onChevron = false
    init { setBackgroundColor(bg) }
    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) onChevron = kotlin.math.abs(e.x - width / 2f) < 40 * d
        if (!onChevron) return forward(this, e)
        if (e.actionMasked == android.view.MotionEvent.ACTION_UP) onTapRef()
        return true
    }
    private fun onTapRef() = onTap()
    private val onTap = onTap
    override fun onDraw(c: android.graphics.Canvas) {
        val cx = width / 2f; val cy = height / 2f; val s = 7 * d
        path.reset(); path.moveTo(cx - s, cy - s * 0.45f); path.lineTo(cx, cy + s * 0.45f); path.lineTo(cx + s, cy - s * 0.45f)
        c.drawPath(path, p)
    }
}

/** The emoji button under the keys. */
class SmileyView(ctx: android.content.Context, bg: Int, ink: Int, onTap: () -> Unit) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val r = android.graphics.RectF()
    init { setBackgroundColor(bg); isClickable = true; setOnClickListener { onTap() } }
    override fun onDraw(c: android.graphics.Canvas) {
        val cx = width / 2f; val cy = height / 2f; val rad = 9 * d
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 1.6f * d; p.strokeCap = android.graphics.Paint.Cap.ROUND
        c.drawCircle(cx, cy, rad, p)
        r.set(cx - rad * 0.6f, cy - rad * 0.45f, cx + rad * 0.6f, cy + rad * 0.6f)
        c.drawArc(r, 20f, 140f, false, p)
        p.style = android.graphics.Paint.Style.FILL
        c.drawCircle(cx - rad * 0.38f, cy - rad * 0.25f, rad * 0.13f, p)
        c.drawCircle(cx + rad * 0.38f, cy - rad * 0.25f, rad * 0.13f, p)
    }
}

class KeysService : InputMethodService(), KeyboardView.Listener {
    private lateinit var prefs: Prefs
    private lateinit var feedback: Feedback
    private lateinit var kb: KeyboardView
    private lateinit var root: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var barScroll: HorizontalScrollView
    private lateinit var content: FrameLayout
    private lateinit var spacer: View
    private lateinit var hideKey: View
    private var emojiPanel: EmojiPanel? = null
    private var d = 1f

    private var autocorrect = true
    private var capsFlags = 0
    private var lastSpaceAt = 0L
    private var lastCorrection: Pair<String, String>? = null
    private var rejectedWord: String? = null
    /** The last word written that the next word may still change (ca/că, their/there). */
    private class Pending(val prev: String?, val word: String)
    private var pending: Pending? = null
    private var viewWasHidden = true
    /** Non-null while the keys type into the emoji search instead of the app. */
    private var emojiSearch: String? = null

    /** Settings changed while the keyboard is up (size, offset sliders) apply right away. */
    private val onPrefChange = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "size" || key == "offset") applyLayoutPrefs()
        if (key == "longpress" && ::kb.isInitialized) kb.longPressMs = prefs.longPressMs.toLong()
        if (key == "preview" && ::kb.isInitialized) kb.previewLingerMs = prefs.previewMs.toLong()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        feedback = Feedback(this, prefs)
        d = resources.displayMetrics.density
        getSharedPreferences("keys", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(onPrefChange)
    }

    override fun onDestroy() {
        getSharedPreferences("keys", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(onPrefChange)
        feedback.release()
        super.onDestroy()
    }

    private fun applyLayoutPrefs() {
        if (!::kb.isInitialized) return
        kb.sizePercent = prefs.sizePercent
        applyBottomSpace()
        val panel = emojiPanel
        if (panel != null) {
            val open = panel.visibility == View.VISIBLE
            content.removeView(panel)
            emojiPanel = null
            if (open) showEmoji()
        }
    }

    override fun onCreateInputView(): View {
        kb = KeyboardView(this).apply { listener = this@KeysService; sizePercent = prefs.sizePercent }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(kb.colorBg) }

        barScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
        barScroll.addView(bar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(barScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * d).roundToInt()))

        bar.setOnTouchListener { _, e -> forwardToKeys(bar, e) }
        barScroll.setOnTouchListener { _, e -> forwardToKeys(barScroll, e) }

        content = FrameLayout(this)
        content.addView(kb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // under the keys: emoji on the left, the hide chevron in the middle
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        strip.addView(SmileyView(this, kb.colorBg, kb.colorText) { toggleEmoji() }, LinearLayout.LayoutParams((64 * d).roundToInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        hideKey = HideKeyView(this, kb.colorBg, kb.colorText, { requestHideSelf(0) }) { v, e -> forwardToKeys(v, e) }
        strip.addView(hideKey, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT).apply { weight = 1f })
        val rightPad = View(this).apply { setBackgroundColor(kb.colorBg) }
        rightPad.setOnTouchListener { v, e -> forwardToKeys(v, e) }
        strip.addView(rightPad, LinearLayout.LayoutParams((64 * d).roundToInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (30 * d).roundToInt()))
        spacer = View(this)
        root.addView(spacer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (prefs.bottomOffsetDp * d).roundToInt()))
        emojiPanel = null
        root.viewTreeObserver.addOnGlobalLayoutListener {
            hideSystemImeBar()
            // nothing on the keyboard is reserved for the system's edge gestures
            root.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, root.width, root.height))
        }
        return root
    }

    private val loc = IntArray(2); private val kbLoc = IntArray(2)

    /** A touch beside the keys (bar above, strip below) is handed to the nearest key. */
    private fun forwardToKeys(from: View, e: android.view.MotionEvent): Boolean {
        if (kb.visibility != View.VISIBLE) return false
        from.getLocationInWindow(loc); kb.getLocationInWindow(kbLoc)
        val copy = android.view.MotionEvent.obtain(e)
        copy.offsetLocation((loc[0] - kbLoc[0]).toFloat(), (loc[1] - kbLoc[1]).toFloat())
        kb.dispatchTouchEvent(copy)
        copy.recycle()
        return true
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Dictionary.ensureLoaded(this, prefs.langs)
        Emoji.ensureLoaded(this)
        kb.sizePercent = prefs.sizePercent
        kb.longPressMs = prefs.longPressMs.toLong()
        kb.previewLingerMs = prefs.previewMs.toLong()
        applyBottomSpace()
        root.post { applyBottomSpace(); hideSystemImeBar() }
        // a restart (same field, re-laid out) keeps the emoji panel or search open
        if (!restarting || viewWasHidden) { hideEmoji(); emojiSearch = null }
        viewWasHidden = false

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
        // Number fields get a pad that fits them; a numeric password is a PIN
        val numFlags = info.inputType and (InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        kb.mode = when {
            cls == InputType.TYPE_CLASS_PHONE -> Mode.PHONE
            cls == InputType.TYPE_CLASS_DATETIME -> Mode.DATETIME
            cls == InputType.TYPE_CLASS_NUMBER && numFlags != 0 -> Mode.DIGITS
            cls == InputType.TYPE_CLASS_NUMBER -> Mode.PIN
            else -> Mode.LETTERS
        }
        val password = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val plainUri = variation == InputType.TYPE_TEXT_VARIATION_URI || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        autocorrect = !numeric && !password && !plainUri && (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0 && prefs.langs.isNotEmpty()
        capsFlags = info.inputType and (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)

        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val noEnter = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        kb.enterLabel = when {
            noEnter -> getString(R.string.s_return)
            action == EditorInfo.IME_ACTION_SEARCH -> "search"
            action == EditorInfo.IME_ACTION_GO -> "go"
            action == EditorInfo.IME_ACTION_SEND -> "send"
            action == EditorInfo.IME_ACTION_NEXT -> "next"
            action == EditorInfo.IME_ACTION_DONE -> "done"
            else -> getString(R.string.s_return)
        }
        lastCorrection = null; rejectedWord = null; pending = null
        if (!restarting) kb.shift = KeyboardView.Shift.OFF
        updateShift()
        updateBar()
    }

    /** The system draws its own back/switcher strip inside our window; we draw our own hide key instead. */
    private fun hideSystemImeBar() {
        val decor = window?.window?.decorView as? ViewGroup ?: return
        for (i in 0 until decor.childCount) {
            val v = decor.getChildAt(i)
            if (v !== root.parent && v.javaClass.name.contains("NavigationBar")) v.visibility = View.GONE
        }
    }

    /** Keeps the keys above the gesture bar: real inset, never below a fixed minimum, plus the user's offset. */
    private fun applyBottomSpace() {
        val inset = window?.window?.decorView?.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
        val h = max(inset, (24 * d).roundToInt()) + (prefs.bottomOffsetDp * d).roundToInt()
        if (spacer.layoutParams.height != h) {
            spacer.layoutParams = spacer.layoutParams.apply { height = h }
            spacer.requestLayout()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        viewWasHidden = true
        kb.dismissPopups()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateShift()
        updateBar()
    }

    // ---- keys ----

    override fun onPressed(key: Key) {
        feedback.key(
            when (key.type) {
                KType.DELETE -> Feedback.Kind.DELETE
                KType.SPACE -> Feedback.Kind.SPACE
                KType.ENTER -> Feedback.Kind.RETURN
                else -> Feedback.Kind.STANDARD
            }
        )
    }

    override fun onKey(key: Key, text: String?) {
        val ic = currentInputConnection ?: return
        val q = emojiSearch
        if (q != null) {
            when (key.type) {
                KType.CHAR -> emojiSearch = q + (text ?: "")
                KType.SPACE -> emojiSearch = "$q "
                KType.DELETE -> emojiSearch = q.dropLast(1)
                KType.ENTER -> { emojiSearch = null; updateShift() }
                KType.MODE -> kb.mode = when (key.label) { "123" -> Mode.NUMBERS; "#+=" -> Mode.SYMBOLS; else -> Mode.LETTERS }
                KType.EMOJI -> { emojiSearch = null; showEmoji() }
                KType.SHIFT -> {}
            }
            if (kb.shift == KeyboardView.Shift.ON && key.type == KType.CHAR) kb.shift = KeyboardView.Shift.OFF
            updateBar()
            return
        }
        when (key.type) {
            KType.CHAR -> {
                val t = text ?: return
                if (t.length == 1 && t[0] in ".,!?;:)") applyCorrection(ic)
                ic.commitText(t, 1)
                afterChar()
            }
            KType.SPACE -> {
                val now = SystemClock.uptimeMillis()
                val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
                if (now - lastSpaceAt < 500 && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(". ", 1)
                    lastSpaceAt = 0
                } else {
                    applyCorrection(ic)
                    ic.commitText(" ", 1)
                    lastSpaceAt = now
                }
                afterChar()
            }
            KType.ENTER -> {
                applyCorrection(ic)
                val info = currentInputEditorInfo
                val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
                if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0 && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    ic.performEditorAction(action)
                } else {
                    ic.commitText("\n", 1)
                }
                afterChar()
            }
            KType.DELETE -> {
                val lc = lastCorrection
                if (lc != null) {
                    val before = ic.getTextBeforeCursor(lc.second.length + 1, 0)?.toString() ?: ""
                    if (before.length == lc.second.length + 1 && before.startsWith(lc.second)) {
                        ic.deleteSurroundingText(before.length, 0)
                        ic.commitText(lc.first + before.last(), 1)
                        rejectedWord = lc.first
                        lastCorrection = null
                        pending = null
                        updateShift(); updateBar()
                        return
                    }
                }
                lastCorrection = null
                val sel = ic.getSelectedText(0)
                if (!sel.isNullOrEmpty()) ic.commitText("", 1)
                else {
                    val before = ic.getTextBeforeCursor(16, 0)?.toString() ?: ""
                    if (before.isNotEmpty()) {
                        val it = BreakIterator.getCharacterInstance()
                        it.setText(before)
                        val last = it.preceding(before.length)
                        ic.deleteSurroundingText(before.length - (if (last < 0) 0 else last), 0)
                    }
                }
                updateShift(); updateBar()
            }
            KType.SHIFT -> {}
            KType.MODE -> kb.mode = when (key.label) { "123" -> Mode.NUMBERS; "#+=" -> Mode.SYMBOLS; else -> Mode.LETTERS }
            KType.EMOJI -> showEmoji()
        }
    }

    private fun afterChar() {
        if (kb.shift == KeyboardView.Shift.ON) kb.shift = KeyboardView.Shift.OFF
        updateShift()
        updateBar()
    }

    private fun updateShift() {
        if (kb.shift == KeyboardView.Shift.LOCK || capsFlags == 0 || kb.mode != Mode.LETTERS) return
        val ic = currentInputConnection ?: return
        val caps = ic.getCursorCapsMode(capsFlags) != 0
        kb.shift = if (caps) KeyboardView.Shift.ON else KeyboardView.Shift.OFF
    }

    // ---- words, corrections, emoji suggestions ----

    private fun currentWord(ic: InputConnection): String {
        val before = ic.getTextBeforeCursor(48, 0)?.toString() ?: return ""
        var i = before.length
        while (i > 0 && (before[i - 1].isLetter() || before[i - 1] == '\'')) i--
        if (i > 0 && before[i - 1] == ':' && (i == 1 || !before[i - 2].isLetterOrDigit())) i--
        return before.substring(i)
    }

    /** The word before [word] in the text, when only one separator sits between them. */
    private fun previousWord(ic: InputConnection, word: String): String? {
        val before = ic.getTextBeforeCursor(96, 0)?.toString() ?: return null
        if (!before.endsWith(word)) return null
        var i = before.length - word.length
        val sepEnd = i
        while (i > 0 && (before[i - 1] == ' ' || before[i - 1] == ',')) i--
        if (i == sepEnd || i == 0) return null
        val wordEnd = i
        while (i > 0 && (before[i - 1].isLetter() || before[i - 1] == '\'')) i--
        return if (i < wordEnd) before.substring(i, wordEnd) else null
    }

    private fun applyCorrection(ic: InputConnection) {
        lastCorrection = null
        if (!autocorrect) return
        val typed = currentWord(ic)
        if (typed.isEmpty() || typed.startsWith(":")) { pending = null; return }
        var word = typed
        var prev = previousWord(ic, word)
        prev = revisePrevious(ic, prev, word)
        if (word == rejectedWord || word.lowercase() in prefs.learned) { pending = null; return }
        val fixed = Dictionary.choose(prev, word, null)
        if (fixed != null) {
            ic.deleteSurroundingText(word.length, 0)
            ic.commitText(fixed, 1)
            lastCorrection = word to fixed
            word = fixed
        }
        pending = if (Dictionary.isAmbiguous(word) && typed == Dictionary.strip(typed)) Pending(prev, word) else null
    }

    /** With the next word known, rewrites the previous ambiguous word when the context says so. */
    private fun revisePrevious(ic: InputConnection, prev: String?, next: String): String? {
        val p = pending ?: return prev
        pending = null
        if (prev == null || prev != p.word) return prev
        if (prev.lowercase() in prefs.learned) return prev
        val better = Dictionary.choose(p.prev, prev, next, revising = true) ?: return prev
        val before = ic.getTextBeforeCursor(96, 0)?.toString() ?: return prev
        val tail = before.length - next.length
        val start = before.lastIndexOf(prev, tail - 1)
        if (start < 0 || tail - (start + prev.length) > 2) return prev
        ic.deleteSurroundingText(before.length - start, 0)
        ic.commitText(better + before.substring(start + prev.length), 1)
        return better
    }

    private fun updateBar() {
        bar.removeAllViews()
        val q = emojiSearch
        if (q != null) {
            bar.addView(chip("\uD83D\uDD0D " + q.ifEmpty { getString(R.string.s_emoji_search) }, 17f) {})
            bar.addView(chip("×", 20f) { emojiSearch = null; updateShift(); updateBar() })
            for (e in Emoji.search(q)) bar.addView(chip(e, 22f) { commitEmoji(e); emojiSearch = null; updateShift(); updateBar() })
            barScroll.scrollTo(0, 0)
            return
        }
        if (emojiPanel?.visibility == View.VISIBLE) {
            bar.addView(chip("\uD83D\uDD0D " + getString(R.string.s_emoji_search), 17f) { startEmojiSearch() })
            return
        }
        val ic = currentInputConnection ?: return
        // A selection: the words in it that autocorrect would fix, offered as the whole corrected text
        val selected = ic.getSelectedText(0)?.toString()
        if (!selected.isNullOrEmpty()) {
            kb.nextLetter = null
            val fixed = if (autocorrect) fixedText(selected) else null
            if (fixed != null) bar.addView(correctionChip(fixed, { feedback.key(Feedback.Kind.STANDARD); ic.commitText(fixed, 1); updateBar() }, { updateBar() }))
            barScroll.scrollTo(0, 0)
            return
        }
        val word = currentWord(ic)
        // The rest of the word after the cursor: placed inside or before a word, its suggestion comes back
        val after = wordAfter(ic)
        // the letters likely to come next widen their keys' targets
        kb.nextLetter = if (after.isEmpty()) Dictionary.nextLetters(word) else null
        val whole = word + after
        if (whole.isEmpty()) return
        if (whole != rejectedWord) rejectedWord = null
        val emojis = if (after.isEmpty()) Emoji.suggest(word) else emptyList()
        for (e in emojis) bar.addView(chip(e, 22f) { replaceWord(word, e); prefs.useEmoji(e) })
        if (emojis.isEmpty() && autocorrect && !whole.startsWith(":") && whole != rejectedWord && whole.lowercase() !in prefs.learned) {
            val fixed = Dictionary.choose(previousWord(ic, word), whole, null)
            if (fixed != null) bar.addView(correctionChip(fixed, { replaceAround(word.length, after.length, fixed) }, { prefs.learn(whole); rejectedWord = whole; updateBar() }))
        }
        barScroll.scrollTo(0, 0)
    }

    /** The letters right after the cursor, up to the end of the word. */
    private fun wordAfter(ic: InputConnection): String {
        val after = ic.getTextAfterCursor(48, 0)?.toString() ?: return ""
        var i = 0
        while (i < after.length && (after[i].isLetter() || after[i] == '\'')) i++
        return after.substring(0, i)
    }

    /** Replaces the word around the cursor: [before] letters behind it, [after] ahead of it. */
    private fun replaceAround(before: Int, after: Int, with: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(before, after)
        ic.commitText(with, 1)
        ic.endBatchEdit()
        lastCorrection = null
        updateBar()
    }

    /** [text] with every word autocorrect would fix replaced, each read with its neighbours; null when nothing changes. */
    private fun fixedText(text: String): String? {
        val parts = Regex("[\\p{L}']+|[^\\p{L}']+").findAll(text).map { it.value }.toList()
        val words = parts.indices.filter { parts[it][0].isLetter() }
        var changed = false
        val out = parts.toMutableList()
        for ((n, i) in words.withIndex()) {
            val w = parts[i]
            if (w.lowercase() in prefs.learned) continue
            val prev = words.getOrNull(n - 1)?.let { out[it] }
            val next = words.getOrNull(n + 1)?.let { parts[it] }
            val f = Dictionary.choose(prev, w, next) ?: continue
            if (f != w) { out[i] = f; changed = true }
        }
        return if (changed) out.joinToString("") else null
    }

    private fun chip(label: String, size: Float, onTap: () -> Unit) = TextView(this).apply {
        text = label; textSize = size; gravity = Gravity.CENTER; setTextColor(kb.colorText)
        typeface = Typeface.SANS_SERIF
        setPadding((10 * d).toInt(), 0, (10 * d).toInt(), 0)
        background = GradientDrawable().apply { cornerRadius = 5 * d; setColor(kb.colorKey) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (30 * d).roundToInt()).apply { marginEnd = (6 * d).toInt() }
        setOnClickListener { feedback.key(Feedback.Kind.STANDARD); onTap() }
    }

    /** The pending correction: tap applies it, the × keeps the typed word and remembers it. */
    private fun correctionChip(label: String, onApply: () -> Unit, onDismiss: () -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.background = GradientDrawable().apply { cornerRadius = 5 * d; setColor(kb.colorKey) }
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (30 * d).roundToInt()).apply { marginEnd = (6 * d).toInt() }
        val t = TextView(this).apply {
            text = label; textSize = 17f; gravity = Gravity.CENTER; setTextColor(kb.colorText); typeface = Typeface.SANS_SERIF
            setPadding((12 * d).toInt(), 0, (8 * d).toInt(), 0)
            setOnClickListener { feedback.key(Feedback.Kind.STANDARD); onApply() }
        }
        val x = TextView(this).apply {
            text = "×"; textSize = 20f; gravity = Gravity.CENTER; setTextColor(kb.colorText); typeface = Typeface.SANS_SERIF
            setPadding((8 * d).toInt(), 0, (12 * d).toInt(), 0)
            setOnClickListener { feedback.key(Feedback.Kind.STANDARD); onDismiss() }
        }
        row.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        row.addView(x, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return row
    }

    private fun replaceWord(word: String, with: String) {
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(with, 1)
        lastCorrection = null
        updateBar()
    }

    // ---- emoji panel ----

    private fun commitEmoji(e: String) {
        feedback.key(Feedback.Kind.STANDARD)
        currentInputConnection?.commitText(e, 1)
        prefs.useEmoji(e)
    }

    private fun toggleEmoji() {
        feedback.key(Feedback.Kind.STANDARD)
        if (emojiSearch != null) { emojiSearch = null; updateShift() }
        if (emojiPanel?.visibility == View.VISIBLE) { hideEmoji(); updateBar() } else showEmoji()
    }

    private fun startEmojiSearch() {
        feedback.key(Feedback.Kind.STANDARD)
        emojiSearch = ""
        hideEmoji()
        kb.mode = Mode.LETTERS
        kb.shift = KeyboardView.Shift.OFF
        updateBar()
    }

    private fun showEmoji() {
        kb.dismissPopups()
        val panel = emojiPanel ?: EmojiPanel(this, kb, (kb.keyboardHeight() * 1.2f).toInt(), { prefs.recentEmoji }, { e ->
            commitEmoji(e)
        }, { feedback.key(Feedback.Kind.STANDARD); hideEmoji(); updateBar() }, {
            feedback.key(Feedback.Kind.DELETE)
            onKey(Key(KType.DELETE), null)
        }).also { emojiPanel = it; content.addView(it) }
        panel.visibility = View.VISIBLE
        kb.visibility = View.INVISIBLE
        updateBar()
    }

    private fun hideEmoji() {
        emojiPanel?.visibility = View.GONE
        kb.visibility = View.VISIBLE
    }
}
