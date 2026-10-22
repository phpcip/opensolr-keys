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
class HideKeyView(ctx: android.content.Context, bg: Int, ink: Int, onTap: () -> Unit) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 2f * d; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND; color = ink
    }
    private val path = android.graphics.Path()
    init { setBackgroundColor(bg); isClickable = true; setOnClickListener { onTap() } }
    override fun onDraw(c: android.graphics.Canvas) {
        val cx = width / 2f; val cy = height / 2f; val s = 7 * d
        path.reset(); path.moveTo(cx - s, cy - s * 0.45f); path.lineTo(cx, cy + s * 0.45f); path.lineTo(cx + s, cy - s * 0.45f)
        c.drawPath(path, p)
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
    /** Non-null while the keys type into the emoji search instead of the app. */
    private var emojiSearch: String? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        feedback = Feedback(this, prefs)
        d = resources.displayMetrics.density
    }

    override fun onDestroy() { feedback.release(); super.onDestroy() }

    override fun onCreateInputView(): View {
        kb = KeyboardView(this).apply { listener = this@KeysService; sizePercent = prefs.sizePercent }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(kb.colorBg) }

        barScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
        barScroll.addView(bar, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(barScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (40 * d).roundToInt()))

        content = FrameLayout(this)
        content.addView(kb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        hideKey = HideKeyView(this, kb.colorBg, kb.colorText) { requestHideSelf(0) }
        root.addView(hideKey, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (26 * d).roundToInt()))
        spacer = View(this)
        root.addView(spacer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (prefs.bottomOffsetDp * d).roundToInt()))
        emojiPanel = null
        root.viewTreeObserver.addOnGlobalLayoutListener { hideSystemImeBar() }
        return root
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Dictionary.ensureLoaded(this, prefs.langs)
        Emoji.ensureLoaded(this)
        kb.sizePercent = prefs.sizePercent
        applyBottomSpace()
        root.post { applyBottomSpace(); hideSystemImeBar() }
        // a restart (same field, re-laid out) keeps the emoji panel or search open
        if (!restarting) { hideEmoji(); emojiSearch = null }

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val numeric = cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME
        kb.mode = if (numeric) Mode.NUMBERS else Mode.LETTERS
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
        lastCorrection = null; rejectedWord = null
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
                        prefs.learn(lc.first)
                        lastCorrection = null
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

    private fun applyCorrection(ic: InputConnection) {
        lastCorrection = null
        if (!autocorrect) return
        val word = currentWord(ic)
        if (word.isEmpty() || word.startsWith(":") || word == rejectedWord || word.lowercase() in prefs.learned) return
        val fixed = Dictionary.correct(word) ?: return
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(fixed, 1)
        lastCorrection = word to fixed
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
        val word = currentWord(ic)
        if (word.isEmpty()) return
        if (word != rejectedWord) rejectedWord = null
        val emojis = Emoji.suggest(word)
        for (e in emojis) bar.addView(chip(e, 22f) { replaceWord(word, e) })
        if (emojis.isEmpty() && autocorrect && !word.startsWith(":") && word != rejectedWord && word.lowercase() !in prefs.learned) {
            val fixed = Dictionary.correct(word)
            if (fixed != null) bar.addView(correctionChip(fixed, { replaceWord(word, fixed) }, { prefs.learn(word); rejectedWord = word; updateBar() }))
        }
        barScroll.scrollTo(0, 0)
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
        val panel = emojiPanel ?: EmojiPanel(this, kb, (kb.keyboardHeight() * 1.7f).toInt(), { e ->
            feedback.key(Feedback.Kind.STANDARD)
            currentInputConnection?.commitText(e, 1)
        }, { feedback.key(Feedback.Kind.STANDARD); hideEmoji() }, {
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
