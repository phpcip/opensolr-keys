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
/** A round button under the keys: [fill] at rest, [press] while held, shrinks a little on the touch and springs back on release. */
abstract class RoundKeyView(ctx: android.content.Context, bg: Int, private val fill: Int, private val press: Int, protected val ink: Int, private val onDown: () -> Unit, private val onTap: () -> Unit) : View(ctx) {
    protected val d = resources.displayMetrics.density
    private val disc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var held = false
    private var mix = 0f
    private var anim: android.animation.ValueAnimator? = null
    init { setBackgroundColor(bg) }

    /** Touches outside the disc; the base keeps them. */
    protected open fun outside(e: android.view.MotionEvent): Boolean = true
    private fun onDisc(x: Float, y: Float) = kotlin.math.hypot(x - width / 2f, y - centerY()) < radius() + 10 * d
    private fun radius() = kotlin.math.min(22 * d, width / 2f - 2 * d)
    /** The disc sits a few dp above the bottom edge of the screen; the view itself may reach past that edge. */
    private fun centerY(): Float {
        val visible = android.graphics.Rect()
        val bottom = if (getLocalVisibleRect(visible)) visible.bottom.toFloat() else height.toFloat()
        return bottom - 10 * d - radius()
    }
    private var tracking = false

    override fun onTouchEvent(e: android.view.MotionEvent): Boolean {
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) tracking = onDisc(e.x, e.y)
        if (!tracking) return outside(e)
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { setHeld(true); onDown() }
            android.view.MotionEvent.ACTION_MOVE -> setHeld(onDisc(e.x, e.y))
            android.view.MotionEvent.ACTION_UP -> { val hit = held; setHeld(false); if (hit) onTap() }
            android.view.MotionEvent.ACTION_CANCEL -> setHeld(false)
        }
        return true
    }

    private fun setHeld(on: Boolean) {
        if (on == held) return
        held = on
        anim?.cancel()
        anim = android.animation.ValueAnimator.ofFloat(mix, if (on) 1f else 0f).apply {
            duration = if (on) 70 else 220
            interpolator = if (on) android.view.animation.DecelerateInterpolator() else android.view.animation.OvershootInterpolator(3f)
            addUpdateListener { mix = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val cx = width / 2f; val cy = centerY()
        val k = mix.coerceIn(0f, 1f)
        disc.color = android.animation.ArgbEvaluator().evaluate(k, fill, press) as Int
        val scale = 1f - 0.12f * mix
        c.save(); c.scale(scale, scale, cx, cy)
        c.drawCircle(cx, cy, radius(), disc)
        drawIcon(c, cx, cy)
        c.restore()
    }

    protected abstract fun drawIcon(c: android.graphics.Canvas, cx: Float, cy: Float)
}

/** The hide button, in the bottom right corner; touches beside the disc still reach the keys. */
class HideKeyView(ctx: android.content.Context, bg: Int, fill: Int, press: Int, ink: Int, onDown: () -> Unit, onTap: () -> Unit, private val forward: (View, android.view.MotionEvent) -> Boolean) : RoundKeyView(ctx, bg, fill, press, ink, onDown, onTap) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 2.3f * d; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND; color = ink
    }
    private val path = android.graphics.Path()
    override fun outside(e: android.view.MotionEvent) = forward(this, e)
    override fun drawIcon(c: android.graphics.Canvas, cx: Float, cy: Float) {
        val s = 7.3f * d
        path.reset(); path.moveTo(cx - s, cy - s * 0.4f); path.lineTo(cx, cy + s * 0.5f); path.lineTo(cx + s, cy - s * 0.4f)
        c.drawPath(path, p)
    }
}

/** The emoji button, in the bottom left corner. */
class SmileyView(ctx: android.content.Context, bg: Int, fill: Int, press: Int, ink: Int, onDown: () -> Unit, onTap: () -> Unit) : RoundKeyView(ctx, bg, fill, press, ink, onDown, onTap) {
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val r = android.graphics.RectF()
    override fun drawIcon(c: android.graphics.Canvas, cx: Float, cy: Float) {
        val rad = 10.4f * d
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 1.9f * d; p.strokeCap = android.graphics.Paint.Cap.ROUND
        c.drawCircle(cx, cy, rad, p)
        r.set(cx - rad * 0.6f, cy - rad * 0.45f, cx + rad * 0.6f, cy + rad * 0.6f)
        c.drawArc(r, 20f, 140f, false, p)
        p.style = android.graphics.Paint.Style.FILL
        c.drawCircle(cx - rad * 0.38f, cy - rad * 0.25f, rad * 0.13f, p)
        c.drawCircle(cx + rad * 0.38f, cy - rad * 0.25f, rad * 0.13f, p)
    }
}

class KeysService : InputMethodService(), KeyboardView.Listener {
    private companion object {
        /** Word suggestions on the bar at once. */
        const val WORD_CHIPS = 3
    }
    private lateinit var prefs: Prefs
    private lateinit var feedback: Feedback
    private lateinit var kb: KeyboardView
    private lateinit var root: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var barScroll: HorizontalScrollView
    private lateinit var content: FrameLayout
    private lateinit var spacer: View
    private var stripLow = false
    private lateinit var hideKey: View
    private var emojiPanel: EmojiPanel? = null
    private var d = 1f

    private var autocorrect = true
    private var capsFlags = 0
    private var lastSpaceAt = 0L
    private var lastCorrection: Pair<String, String>? = null
    private var rejectedWord: String? = null
    /** Words whose correction was undone by hand in this text: sent as they are, they are learned and never corrected again. */
    private val kept = LinkedHashSet<String>()
    private var lastKeyAt = 0L
    /** The last word written that the next word may still change (ca/că, their/there). */
    private class Pending(val prev: String?, val word: String)
    private var pending: Pending? = null
    private var viewWasHidden = true
    /** Non-null while the keys type into the emoji search instead of the app. */
    private var emojiSearch: String? = null
    /** The emoji suggestions of the word just replaced by one of them: they stay on the bar for more taps. */
    private var stickyEmoji: List<String>? = null
    /** The word just picked from the bar: the space after it is written when the next word starts, so punctuation sits right against it. */
    private var phantom: String? = null
    private var shownSearch: String? = null
    /** The clipboard text taken off the bar with its × (kept as a hash, across restarts): back when something new is copied. */
    private var dismissedClip: String?
        get() = getSharedPreferences("keys", MODE_PRIVATE).getString("clip_dismissed", null)
        set(v) { getSharedPreferences("keys", MODE_PRIVATE).edit().putString("clip_dismissed", v).apply() }
    private val clipboard by lazy { getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager }
    private val onClipChange = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        dismissedClip = null
        if (::bar.isInitialized) updateBar()
    }

    /** Settings changed while the keyboard is up (size, offset sliders) apply right away. */
    private val onPrefChange = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "size" || key == "offset") applyLayoutPrefs()
        // languages ticked while the keyboard is up: their words are loaded now, not at the next field
        if (key == "langs") Dictionary.ensureLoaded(this, prefs.langs)
        if (key == "longpress" && ::kb.isInitialized) kb.longPressMs = prefs.longPressMs.toLong()
        if (key == "preview" && ::kb.isInitialized) kb.previewLingerMs = prefs.previewMs.toLong()
        if (key == "balloon" && ::kb.isInitialized) kb.balloonPercent = prefs.balloonPercent
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        feedback = Feedback(this, prefs)
        Emoji.tones = prefs.tones
        d = resources.displayMetrics.density
        getSharedPreferences("keys", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(onPrefChange)
        runCatching { clipboard.addPrimaryClipChangedListener(onClipChange) }
    }

    override fun onDestroy() {
        getSharedPreferences("keys", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(onPrefChange)
        runCatching { clipboard.removePrimaryClipChangedListener(onClipChange) }
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
        feedback.view = kb
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(kb.colorBg) }

        barScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isFillViewport = true }
        bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
        barScroll.addView(bar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(barScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * d).roundToInt()))

        // a bar with more than fits scrolls sideways under the finger; one that fits hands its empty space to the keys
        fun barScrolls() = barScroll.canScrollHorizontally(1) || barScroll.canScrollHorizontally(-1)
        bar.setOnTouchListener { _, e -> if (barScrolls()) false else forwardToKeys(bar, e) }
        barScroll.setOnTouchListener { _, e -> if (barScrolls()) false else forwardToKeys(barScroll, e) }

        content = FrameLayout(this)
        content.addView(kb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // under the keys: emoji on the left, the hide chevron in the bottom right corner
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        strip.addView(SmileyView(this, kb.colorBg, kb.colorSpecial, kb.colorKey, kb.colorText, { feedback.key(Feedback.Kind.STANDARD) }) { toggleEmoji() }, LinearLayout.LayoutParams((64 * d).roundToInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        val middle = View(this).apply { setBackgroundColor(kb.colorBg) }
        middle.setOnTouchListener { v, e -> forwardToKeys(v, e) }
        strip.addView(middle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT).apply { weight = 1f })
        hideKey = HideKeyView(this, kb.colorBg, kb.colorSpecial, kb.colorKey, kb.colorText, { feedback.key(Feedback.Kind.STANDARD) }, { requestHideSelf(0) }) { v, e -> forwardToKeys(v, e) }
        strip.addView(hideKey, LinearLayout.LayoutParams((64 * d).roundToInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        // the strip reaches the bottom of the screen: the gesture bar's space is under its buttons, not under the keys
        root.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * d).roundToInt()))
        spacer = strip
        emojiPanel = null
        root.viewTreeObserver.addOnGlobalLayoutListener {
            hideSystemImeBar()
            // the keys are kept from the side edge gestures; the strip over the gesture bar stays the system's
            root.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, root.width, spacer.top))
        }
        return root
    }

    private val loc = IntArray(2); private val kbLoc = IntArray(2)

    /** A touch beside the keys (bar above, strip below) is handed to the nearest key. */
    private fun forwardToKeys(from: View, e: android.view.MotionEvent): Boolean {
        if (kb.visibility != View.VISIBLE) return false
        // only the strip's top, right under the keys, reaches them; lower down a touch does nothing
        if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) stripLow = from !== barScroll && e.y > 6 * d
        if (stripLow) return true
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
        kb.balloonPercent = prefs.balloonPercent
        applyBottomSpace()
        root.post { applyBottomSpace(); hideSystemImeBar() }
        // a restart (same field, re-laid out) keeps the emoji panel or search open
        if (!restarting || viewWasHidden) { hideEmoji(); emojiSearch = null; stickyEmoji = null }
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
        lastCorrection = null; rejectedWord = null; pending = null; phantom = null
        if (!restarting) kept.clear()
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
        val h = (40 * d).roundToInt() + max(inset, (24 * d).roundToInt()) + (prefs.bottomOffsetDp * d).roundToInt()
        if (spacer.layoutParams.height != h) {
            spacer.layoutParams = spacer.layoutParams.apply { height = h }
            spacer.requestLayout()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        viewWasHidden = true
        if (::kb.isInitialized) kb.dismissPopups()
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // the editor can report its cursor before the keyboard view exists
        if (!::kb.isInitialized) return
        // the app emptied the field by itself (a send button): the text went out as it was
        if (kept.isNotEmpty() && newSelStart == 0 && newSelEnd == 0 && SystemClock.uptimeMillis() - lastKeyAt > 150) {
            val ic = currentInputConnection
            if (ic != null && ic.getTextAfterCursor(1, 0).isNullOrEmpty() && ic.getTextBeforeCursor(1, 0).isNullOrEmpty()) learnKept(null)
        }
        updateShift()
        updateBar()
    }

    /** Learns the words kept against a correction; with [text] given, only the ones still in it. */
    private fun learnKept(text: String?) {
        val words = text?.lowercase()?.split(Regex("[^\\p{L}']+"))?.toSet()
        for (w in kept) if (words == null || w in words) prefs.learn(w)
        kept.clear()
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

    override fun onRepeat(key: Key) = feedback.tick()

    override fun onKey(key: Key, text: String?) {
        val ic = currentInputConnection ?: return
        lastKeyAt = SystemClock.uptimeMillis()
        if (key.type != KType.DELETE) stickyEmoji = null
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
        // a word picked from the bar is final: no correction on it, and its space comes with the next word
        val picked = phantom?.let { ic.getTextBeforeCursor(it.length, 0)?.toString() == it } == true
        phantom = null
        when (key.type) {
            KType.CHAR -> {
                val t = text ?: return
                if (picked) { if (t[0].isLetterOrDigit()) ic.commitText(" ", 1) }
                else if (t.length == 1 && t[0] in ".,!?;:)") applyCorrection(ic)
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
                    if (!picked) applyCorrection(ic)
                    ic.commitText(" ", 1)
                    lastSpaceAt = now
                }
                afterChar()
            }
            KType.ENTER -> {
                if (!picked) applyCorrection(ic)
                val info = currentInputEditorInfo
                val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
                if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0 && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                    if (kept.isNotEmpty()) learnKept((ic.getTextBeforeCursor(4000, 0)?.toString() ?: "") + (ic.getTextAfterCursor(1000, 0)?.toString() ?: ""))
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
                        kept.add(lc.first.lowercase())
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
        fillBar()
        // nothing to suggest: the settings cog on the left, the clipboard to paste in the middle, the Opensolr logo on the right
        if (bar.childCount == 0) {
            bar.addView(iconChip(R.drawable.ic_cog, true) {
                startActivity(android.content.Intent(this, SettingsActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            bar.addView(View(this), LinearLayout.LayoutParams(0, 1).apply { weight = 1f })
            clipChip()?.let { bar.addView(it) }
            bar.addView(View(this), LinearLayout.LayoutParams(0, 1).apply { weight = 1f })
            bar.addView(iconChip(R.drawable.opensolr_logo, false) {
                runCatching {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://opensolr.com"))
                        .addCategory(android.content.Intent.CATEGORY_BROWSABLE).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            })
        } else if (emojiSearch == null && emojiPanel?.visibility != View.VISIBLE) {
            // suggestions on the bar: the clipboard stays, at the right end after them, until its × is tapped
            clipChip()?.let { chip ->
                bar.addView(View(this), LinearLayout.LayoutParams(0, 1).apply { weight = 1f })
                bar.addView(chip)
            }
        }
    }

    /**
     * The text on the clipboard as one short line: a tap pastes all of it, as often as wanted. The × button beside it
     * empties the clipboard, and only that takes it off the bar. A clip the copying app marked sensitive (a password) shows as dots.
     */
    private fun clipChip(): View? {
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return null
        if (clip.itemCount == 0) return null
        val text = runCatching { clip.getItemAt(0).coerceToText(this)?.toString() }.getOrNull()
        if (text.isNullOrBlank() || text.hashCode().toString() == dismissedClip) return null
        val sensitive = android.os.Build.VERSION.SDK_INT >= 33 &&
            clip.description?.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true
        val line = if (sensitive) "••••••" else text.trim().replace(Regex("\\s+"), " ")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val pill = chip(line, 15f) {
            val ic = currentInputConnection ?: return@chip
            ic.commitText(text, 1)
            updateShift()
            updateBar()
        }.apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            // the cog, the × and the logo keep their places
            maxWidth = resources.displayMetrics.widthPixels - (170 * d).roundToInt()
            contentDescription = getString(R.string.s_paste)
        }
        row.addView(pill)
        // the × is a button of its own, drawn like a key: a tap empties the clipboard
        val clear = TextView(this).apply {
            setText("×"); textSize = 20f; gravity = Gravity.CENTER; setTextColor(kb.colorText); typeface = Typeface.SANS_SERIF
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(kb.colorSpecial),
                GradientDrawable().apply { cornerRadius = 5 * d; setColor(kb.colorKey); setStroke((1 * d).roundToInt(), kb.colorSpecial) }, null)
            contentDescription = getString(R.string.s_clear_clipboard)
            setOnClickListener {
                feedback.key(Feedback.Kind.STANDARD)
                val cleared = android.os.Build.VERSION.SDK_INT >= 28 && runCatching { clipboard.clearPrimaryClip() }.isSuccess
                // where the clipboard cannot be emptied, the bar forgets this text until something new is copied
                if (!cleared) dismissedClip = text.hashCode().toString()
                updateBar()
            }
        }
        row.addView(clear, LinearLayout.LayoutParams((34 * d).roundToInt(), (30 * d).roundToInt()))
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (30 * d).roundToInt())
        return row
    }

    /** A square icon button in the bar; [tint] paints the icon in the keys' text color. */
    private fun iconChip(res: Int, tint: Boolean, onTap: () -> Unit) = android.widget.ImageView(this).apply {
        setImageResource(res)
        if (tint) setColorFilter(kb.colorText)
        val pad = (6 * d).toInt()
        setPadding(pad, pad, pad, pad)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(kb.colorSpecial), null,
            GradientDrawable().apply { cornerRadius = 15 * d; setColor(Color.WHITE) })
        contentDescription = if (tint) "Settings" else "Opensolr"
        layoutParams = LinearLayout.LayoutParams((34 * d).roundToInt(), (30 * d).roundToInt()).apply { marginStart = (4 * d).toInt(); marginEnd = (4 * d).toInt() }
        setOnClickListener { feedback.key(Feedback.Kind.STANDARD); onTap() }
    }

    private fun fillBar() {
        val q = emojiSearch
        if (q != null) {
            bar.addView(chip("\uD83D\uDD0D " + q.ifEmpty { getString(R.string.s_emoji_search) }, 17f) {})
            bar.addView(chip("×", 20f) { emojiSearch = null; updateShift(); updateBar() })
            for (e in Emoji.search(q)) bar.addView(chip(e, 22f) { commitEmoji(e) })
            // picking one keeps the row where the finger left it
            if (q != shownSearch) barScroll.scrollTo(0, 0)
            shownSearch = q
            return
        }
        shownSearch = null
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
        // an emoticon just typed (:) :)) <3 :P): the emoji it stands for
        val tail = if (after.isEmpty()) ic.getTextBeforeCursor(24, 0)?.toString() else null
        val face = tail?.let { Emoji.emoticon(it) }
        if (face != null && tail != null) {
            kb.nextLetter = null
            val typed = tail.takeLast(face.first)
            for (e in face.second) bar.addView(chip(e, 22f) { stickyEmoji = face.second; replaceWord(typed, e); prefs.useEmoji(e) })
            barScroll.scrollTo(0, 0)
            return
        }
        // the letters likely to come next widen their keys' targets
        kb.nextLetter = if (after.isEmpty()) Dictionary.nextLetters(word) else null
        val whole = word + after
        if (whole.isEmpty()) {
            // the word became an emoji: its suggestions stay, each tap adds one more
            stickyEmoji?.let { for (e in it) bar.addView(chip(e, 22f) { commitEmoji(e) }) }
            return
        }
        stickyEmoji = null
        if (whole != rejectedWord) rejectedWord = null
        // the word just picked from the bar is done: nothing more to offer for it
        val done = phantom
        if (done != null) {
            if (after.isEmpty() && ic.getTextBeforeCursor(done.length, 0)?.toString() == done) { kb.nextLetter = null; return }
            phantom = null
        }
        // words first: the correction the space key would make, then what the word may be the start of; the emoji after them
        if (autocorrect && !whole.startsWith(":")) {
            val prev = previousWord(ic, word)
            val fixed = if (whole != rejectedWord && whole.lowercase() !in prefs.learned) Dictionary.choose(prev, whole, null) else null
            if (fixed != null) bar.addView(correctionChip(fixed, { pickWord(word.length, after.length, fixed) }, { prefs.learn(whole); rejectedWord = whole; updateBar() }))
            if (after.isEmpty()) {
                var room = WORD_CHIPS - bar.childCount
                for (w in Dictionary.complete(prev, word, WORD_CHIPS)) {
                    if (room <= 0) break
                    if (w.equals(fixed, ignoreCase = true)) continue
                    bar.addView(chip(w, 17f) { pickWord(word.length, 0, w) })
                    room--
                }
            }
        }
        val emojis = if (after.isEmpty()) Emoji.suggest(word) else emptyList()
        for (e in emojis) bar.addView(chip(e, 22f) { stickyEmoji = emojis; replaceWord(word, e); prefs.useEmoji(e) })
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

    /** A word chosen from the bar replaces the one around the cursor; at the end of the text its space waits for the next word. */
    private fun pickWord(before: Int, after: Int, with: String) {
        phantom = if (after == 0) with else null
        replaceAround(before, after, with)
        updateShift()
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
        }, { base, form ->
            // the form picked with a long press is the one this emoji is offered in from now on
            val t = HashMap(prefs.tones)
            if (form == base) t.remove(base) else t[base] = form
            prefs.tones = t; Emoji.tones = t
        }, { feedback.key(Feedback.Kind.STANDARD); hideEmoji(); updateBar() }, {
            feedback.key(Feedback.Kind.DELETE)
            onKey(Key(KType.DELETE), null)
        }).also { emojiPanel = it; content.addView(it) }
        panel.refreshRecent()
        panel.visibility = View.VISIBLE
        kb.visibility = View.INVISIBLE
        updateBar()
    }

    private fun hideEmoji() {
        emojiPanel?.visibility = View.GONE
        kb.visibility = View.VISIBLE
    }
}
