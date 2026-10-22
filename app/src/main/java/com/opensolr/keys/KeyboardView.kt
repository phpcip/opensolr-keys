package com.opensolr.keys

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.PopupWindow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The keys, drawn to match the iOS keyboard: big rounded keys, bottom shadow line, preview balloon. */
class KeyboardView(ctx: Context) : View(ctx) {
    interface Listener {
        fun onKey(key: Key, text: String?)
        fun onPressed(key: Key)
    }

    enum class Shift { OFF, ON, LOCK }

    var listener: Listener? = null
    var sizePercent = 100
        set(v) { field = v; requestLayout() }
    var enterLabel = "return"
        set(v) { field = v; invalidate() }
    var mode = Mode.LETTERS
        set(v) { field = v; rows = Layouts.rows(v); keys = rows.flatten(); layoutKeys(width); invalidate() }
    var shift = Shift.OFF
        set(v) { field = v; invalidate() }

    // always the light iOS look: white keys, whatever the system theme
    private val dark = false
    private val d = resources.displayMetrics.density

    val colorBg = if (dark) Color.parseColor("#2A2A2C") else Color.parseColor("#D1D3D9")
    val colorKey = if (dark) Color.parseColor("#6E6E70") else Color.WHITE
    val colorSpecial = if (dark) Color.parseColor("#464648") else Color.parseColor("#ADB3BC")
    val colorText = if (dark) Color.WHITE else Color.BLACK
    val colorShadow = if (dark) Color.parseColor("#1A1A1C") else Color.parseColor("#898A8D")
    private val colorHighlight = Color.parseColor("#007AFF")
    private val colorHint = Color.parseColor("#8E8E93")

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.SANS_SERIF }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val rect = RectF()
    private val path = Path()

    private var rows: List<List<Key>> = Layouts.rows(Mode.LETTERS)
    private var keys: List<Key> = rows.flatten()

    private val gap = 4 * d
    private val rowGap = 8 * d
    private val side = 2 * d
    private val topPad = 7 * d
    private val bottomPad = 4 * d
    private val radius = 5 * d
    private var unit = 0f
    private var keyH = 0f

    private var activeKey: Key? = null
    private var activePointer = -1
    private var pressedKey: Key? = null
    private var repeatFired = false
    private var lastShiftTap = 0L

    private val preview = PopupWindow(ctx).apply {
        isClippingEnabled = false; isTouchable = false; isFocusable = false
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED; animationStyle = 0
        setBackgroundDrawable(null)
    }
    private val previewView = BalloonView(ctx)
    private val accents = PopupWindow(ctx).apply {
        isClippingEnabled = false; isTouchable = false; isFocusable = false
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED; animationStyle = 0
        setBackgroundDrawable(null)
    }
    private val accentView = AccentView(ctx)
    private var accentLeft = 0f
    private val winPos = IntArray(2)

    private val longPress = Runnable { onLongPress() }
    // the balloon lingers a moment after the finger lifts, like on iOS
    private val hidePreview = Runnable { if (preview.isShowing && activeKey?.type != KType.CHAR) preview.dismiss() }
    private val repeat = object : Runnable {
        override fun run() {
            val k = activeKey ?: return
            if (k.type == KType.DELETE) { repeatFired = true; listener?.onKey(k, null); postDelayed(this, 50) }
        }
    }

    init { isClickable = true }

    fun keyboardHeight(): Int {
        val kh = 48 * d * sizePercent / 100f
        val rowsH = rows.sumOf { r -> (kh * (r.firstOrNull()?.heightScale ?: 1f)).toDouble() }.toFloat()
        return (topPad + rowsH + (rows.size - 1) * rowGap + bottomPad).roundToInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), keyboardHeight())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys(w)

    private fun layoutKeys(wInt: Int) {
        if (wInt == 0) return
        val w = wInt.toFloat()
        unit = (w - 2 * side - 9 * gap) / 10f
        keyH = 48 * d * sizePercent / 100f
        var y = topPad
        val last = rows.size - 1
        for ((r, row) in rows.withIndex()) {
            val rh = keyH * (row.firstOrNull()?.heightScale ?: 1f)
            for (k in row) { k.y = y; k.h = rh }
            when {
                r == last -> {
                    var x = side
                    val fixed = row.filter { it.weight > 0f }.sumOf { (it.weight * unit).toDouble() }.toFloat()
                    val spaceW = w - 2 * side - fixed - (row.size - 1) * gap
                    for (k in row) { k.x = x; k.w = if (k.weight > 0f) k.weight * unit else spaceW; x += k.w + gap }
                }
                r == last - 1 -> {
                    val first = row.first(); val lastK = row.last()
                    first.x = side; first.w = unit * first.weight
                    lastK.w = unit * lastK.weight; lastK.x = w - side - lastK.w
                    val mid = row.subList(1, row.size - 1)
                    if (mid.size == 7) {
                        val total = 7 * unit + 6 * gap
                        var x = (w - total) / 2
                        for (k in mid) { k.x = x; k.w = unit; x += unit + gap }
                    } else {
                        val big = gap * 2.3f
                        val start = first.x + first.w + big
                        val end = lastK.x - big
                        val kw = (end - start - (mid.size - 1) * gap) / mid.size
                        var x = start
                        for (k in mid) { k.x = x; k.w = kw; x += kw + gap }
                    }
                }
                else -> {
                    val total = row.size * unit + (row.size - 1) * gap
                    var x = (w - total) / 2
                    for (k in row) { k.x = x; k.w = unit; x += unit + gap }
                }
            }
            y += rh + rowGap
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(colorBg)
        for (k in keys) drawKey(c, k)
    }

    private fun isSpecial(k: Key) = k.special || when (k.type) {
        KType.CHAR, KType.SPACE -> false
        else -> true
    }

    private fun drawKey(c: Canvas, k: Key) {
        val pressed = k === pressedKey
        var color = if (isSpecial(k)) colorSpecial else colorKey
        if (k.type == KType.SHIFT && shift != Shift.OFF) color = Color.WHITE
        else if (isSpecial(k) && pressed) color = colorKey
        else if ((k.type == KType.SPACE || k.type == KType.CHAR) && pressed && !k.special) color = colorSpecial
        if (k.type == KType.ENTER) {
            val r = k.h / 2
            fill.color = colorShadow
            c.drawCircle(k.x + k.w / 2, k.y + r + d, r, fill)
            fill.color = if (pressed) colorKey else colorHighlight
            c.drawCircle(k.x + k.w / 2, k.y + r, r, fill)
            drawReturn(c, k.x + k.w / 2, k.y + r, Color.WHITE)
            return
        }
        rect.set(k.x, k.y + d, k.x + k.w, k.y + k.h + d)
        fill.color = colorShadow
        c.drawRoundRect(rect, radius, radius, fill)
        rect.set(k.x, k.y, k.x + k.w, k.y + k.h)
        fill.color = color
        c.drawRoundRect(rect, radius, radius, fill)

        val cx = k.x + k.w / 2; val cy = k.y + k.h / 2
        val shiftOn = k.type == KType.SHIFT && shift != Shift.OFF
        val ink = if (shiftOn) Color.BLACK else colorText
        when (k.type) {
            KType.CHAR -> {
                text.color = ink
                text.textSize = k.h * 0.6f
                val s = if (mode == Mode.LETTERS && shift != Shift.OFF) k.text.uppercase() else k.text
                c.drawText(s, cx, cy - (text.descent() + text.ascent()) / 2, text)
                val hint = if (mode == Mode.LETTERS) Accents.hint(k.text) else null
                if (hint != null) {
                    text.color = colorHint
                    text.textSize = k.h * 0.24f
                    c.drawText(hint, k.x + k.w - 5 * d - text.measureText(hint) / 2, k.y + 4 * d - text.ascent(), text)
                }
            }
            KType.SPACE, KType.MODE, KType.ENTER -> {
                text.color = ink
                text.textSize = keyH * 0.36f
                val s = when (k.type) { KType.ENTER -> enterLabel; else -> k.label }
                c.drawText(s, cx, cy - (text.descent() + text.ascent()) / 2, text)
            }
            KType.SHIFT -> drawShift(c, cx, cy, ink, shift)
            KType.DELETE -> drawDelete(c, cx, cy, ink)
            KType.EMOJI -> drawEmoji(c, cx, cy, ink)
        }
    }

    private fun drawShift(c: Canvas, cx: Float, cy: Float, ink: Int, state: Shift) {
        val s = keyH * 0.2f
        val dy = if (state == Shift.LOCK) -s * 0.15f else 0f
        path.reset()
        path.moveTo(cx, cy - s + dy); path.lineTo(cx + s, cy + dy); path.lineTo(cx + s * 0.5f, cy + dy)
        path.lineTo(cx + s * 0.5f, cy + s * 0.85f + dy); path.lineTo(cx - s * 0.5f, cy + s * 0.85f + dy)
        path.lineTo(cx - s * 0.5f, cy + dy); path.lineTo(cx - s, cy + dy); path.close()
        if (state == Shift.OFF) {
            stroke.color = ink; stroke.strokeWidth = 1.6f * d
            c.drawPath(path, stroke)
        } else {
            fill.color = ink
            c.drawPath(path, fill)
            if (state == Shift.LOCK) {
                stroke.color = ink; stroke.strokeWidth = 1.6f * d
                c.drawLine(cx - s * 0.5f, cy + s * 1.25f, cx + s * 0.5f, cy + s * 1.25f, stroke)
            }
        }
    }

    private fun drawReturn(c: Canvas, cx: Float, cy: Float, ink: Int) {
        val s = keyH * 0.18f
        stroke.color = ink; stroke.strokeWidth = 2f * d
        path.reset()
        path.moveTo(cx + s, cy - s * 0.9f); path.lineTo(cx + s, cy + s * 0.2f); path.lineTo(cx - s, cy + s * 0.2f)
        c.drawPath(path, stroke)
        path.reset()
        path.moveTo(cx - s * 0.3f, cy - s * 0.5f); path.lineTo(cx - s, cy + s * 0.2f); path.lineTo(cx - s * 0.3f, cy + s * 0.9f)
        c.drawPath(path, stroke)
    }

    private fun drawDelete(c: Canvas, cx: Float, cy: Float, ink: Int) {
        val s = keyH * 0.19f
        path.reset()
        path.moveTo(cx - s * 1.25f, cy); path.lineTo(cx - s * 0.5f, cy - s * 0.8f); path.lineTo(cx + s * 1.15f, cy - s * 0.8f)
        path.lineTo(cx + s * 1.15f, cy + s * 0.8f); path.lineTo(cx - s * 0.5f, cy + s * 0.8f); path.close()
        stroke.color = ink; stroke.strokeWidth = 1.6f * d
        c.drawPath(path, stroke)
        val ox = cx + s * 0.3f; val r = s * 0.33f
        c.drawLine(ox - r, cy - r, ox + r, cy + r, stroke)
        c.drawLine(ox - r, cy + r, ox + r, cy - r, stroke)
    }

    private fun drawEmoji(c: Canvas, cx: Float, cy: Float, ink: Int) {
        val r = keyH * 0.2f
        stroke.color = ink; stroke.strokeWidth = 1.5f * d
        c.drawCircle(cx, cy, r, stroke)
        fill.color = ink
        c.drawCircle(cx - r * 0.38f, cy - r * 0.25f, r * 0.13f, fill)
        c.drawCircle(cx + r * 0.38f, cy - r * 0.25f, r * 0.13f, fill)
        rect.set(cx - r * 0.6f, cy - r * 0.45f, cx + r * 0.6f, cy + r * 0.6f)
        c.drawArc(rect, 20f, 140f, false, stroke)
    }

    private fun keyAt(x: Float, y: Float): Key? {
        for (k in keys) if (k.hit(x, y)) return k
        // between keys: pick the nearest in the same row
        var best: Key? = null; var bestD = Float.MAX_VALUE
        for (k in keys) {
            if (y < k.y - rowGap / 2 || y >= k.y + k.h + rowGap / 2) continue
            val dx = when { x < k.x -> k.x - x; x > k.x + k.w -> x - k.x - k.w; else -> 0f }
            if (dx < bestD) { bestD = dx; best = k }
        }
        return if (bestD <= gap) best else null
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                activeKey?.let { finish(it, true) }
                val k = keyAt(e.getX(idx), e.getY(idx)) ?: return true
                activePointer = e.getPointerId(idx)
                press(k)
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = e.findPointerIndex(activePointer)
                if (idx < 0) return true
                val x = e.getX(idx); val y = e.getY(idx)
                if (accents.isShowing) { accentView.select(((x - accentLeft) / accentView.cellW).toInt()); return true }
                val k = keyAt(x, y)
                if (k != null && k !== activeKey && activeKey?.type == KType.CHAR && k.type == KType.CHAR) {
                    removeCallbacks(longPress)
                    activeKey = k; pressedKey = k
                    showPreview(k)
                    postDelayed(longPress, 300)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (e.getPointerId(e.actionIndex) == activePointer) activeKey?.let { finish(it, true) }
            }
            MotionEvent.ACTION_CANCEL -> activeKey?.let { finish(it, false) }
        }
        return true
    }

    private fun press(k: Key) {
        activeKey = k; pressedKey = k; repeatFired = false
        listener?.onPressed(k)
        when (k.type) {
            KType.CHAR -> { showPreview(k); postDelayed(longPress, 300) }
            KType.DELETE -> { listener?.onKey(k, null); postDelayed(repeat, 350) }
            KType.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                shift = when {
                    now - lastShiftTap < 300 -> Shift.LOCK
                    shift == Shift.OFF -> Shift.ON
                    else -> Shift.OFF
                }
                lastShiftTap = now
                listener?.onKey(k, null)
            }
            KType.MODE, KType.EMOJI -> listener?.onKey(k, null)
            else -> {}
        }
        invalidate()
    }

    private fun finish(k: Key, fire: Boolean) {
        removeCallbacks(longPress); removeCallbacks(repeat)
        if (accents.isShowing) {
            val chosen = accentView.selected()
            accents.dismiss()
            if (fire && chosen != null) listener?.onKey(k, chosen)
        } else if (fire) {
            when (k.type) {
                KType.CHAR -> listener?.onKey(k, if (mode == Mode.LETTERS && shift != Shift.OFF) k.text.uppercase() else k.text)
                KType.SPACE, KType.ENTER -> listener?.onKey(k, k.text)
                else -> {}
            }
        }
        activeKey = null; pressedKey = null; activePointer = -1
        if (preview.isShowing) { removeCallbacks(hidePreview); postDelayed(hidePreview, 60) }
        invalidate()
    }

    private fun onLongPress() {
        val k = activeKey ?: return
        val alts = Accents.of(k.text, mode == Mode.LETTERS && shift != Shift.OFF) ?: return
        // only the alternatives: the key's own letter is what a plain tap already gives
        val chars = alts
        if (preview.isShowing) preview.dismiss()
        accentView.setup(chars.map { it.toString() }, unit, keyH * 1.1f, keyH * 0.5f)
        val pw = accentView.cellW * chars.length + accentView.pad * 2
        accentLeft = min(max(k.x - accentView.pad, side), width - side - pw)
        getLocationInWindow(winPos)
        val px = (winPos[0] + accentLeft).roundToInt()
        val py = (winPos[1] + k.y - keyH * 1.1f - accentView.pad * 2 - 4 * d).roundToInt()
        val ph = (keyH * 1.1f + accentView.pad * 2).roundToInt()
        accentView.select(0)
        if (accents.isShowing) accents.update(px, py, pw.roundToInt(), ph) else { accents.width = pw.roundToInt(); accents.height = ph; accents.contentView = accentView; accents.showAtLocation(this, android.view.Gravity.NO_GRAVITY, px, py) }
    }

    private fun showPreview(k: Key) {
        removeCallbacks(hidePreview)
        val label = if (mode == Mode.LETTERS && shift != Shift.OFF) k.text.uppercase() else k.text
        val pw = (k.w * 1.6f).roundToInt()
        val ph = (k.h * 2.25f).roundToInt()
        getLocationInWindow(winPos)
        val px = (winPos[0] + k.x + k.w / 2 - pw / 2).roundToInt()
        val py = (winPos[1] + k.y + k.h - ph).roundToInt()
        previewView.set(label, k.w, k.h, keyH * 0.95f)
        if (preview.isShowing) preview.update(px, py, pw, ph)
        else { preview.width = pw; preview.height = ph; preview.contentView = previewView; preview.showAtLocation(this, android.view.Gravity.NO_GRAVITY, px, py) }
        previewView.invalidate()
    }

    fun dismissPopups() {
        removeCallbacks(longPress); removeCallbacks(repeat); removeCallbacks(hidePreview)
        if (preview.isShowing) preview.dismiss()
        if (accents.isShowing) accents.dismiss()
        activeKey = null; pressedKey = null
    }

    override fun onDetachedFromWindow() { dismissPopups(); super.onDetachedFromWindow() }

    /** The enlarged key shown above a pressed letter. */
    private inner class BalloonView(ctx: Context) : View(ctx) {
        private var label = ""; private var keyW = 0f; private var keyHt = 0f; private var fontPx = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.SANS_SERIF }
        fun set(l: String, w: Float, h: Float, f: Float) { label = l; keyW = w; keyHt = h; fontPx = f }
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val top = 0f; val bodyBottom = h - keyHt * 1.15f
            val r = 8 * d
            path.reset()
            path.moveTo(r, top); path.lineTo(w - r, top); path.quadTo(w, top, w, top + r)
            path.lineTo(w, bodyBottom - r); path.quadTo(w, bodyBottom, w - r, bodyBottom)
            val kx0 = (w - keyW) / 2; val kx1 = kx0 + keyW
            path.lineTo(kx1 + r, bodyBottom); path.quadTo(kx1, bodyBottom, kx1, bodyBottom + r)
            path.lineTo(kx1, h - radius); path.quadTo(kx1, h, kx1 - radius, h)
            path.lineTo(kx0 + radius, h); path.quadTo(kx0, h, kx0, h - radius)
            path.lineTo(kx0, bodyBottom + r); path.quadTo(kx0, bodyBottom, kx0 - r, bodyBottom)
            path.lineTo(r, bodyBottom); path.quadTo(0f, bodyBottom, 0f, bodyBottom - r)
            path.lineTo(0f, top + r); path.quadTo(0f, top, r, top); path.close()
            p.color = colorShadow; p.style = Paint.Style.STROKE; p.strokeWidth = 1 * d
            c.drawPath(path, p)
            p.color = colorKey; p.style = Paint.Style.FILL
            c.drawPath(path, p)
            t.color = colorText; t.textSize = fontPx
            c.drawText(label, w / 2, bodyBottom / 2 - (t.descent() + t.ascent()) / 2, t)
        }
    }

    /** The row of alternative characters shown on long press. */
    private inner class AccentView(ctx: Context) : View(ctx) {
        private var items: List<String> = emptyList()
        var cellW = 0f; private var cellH = 0f; private var fontPx = 0f
        val pad = 4 * d
        private var sel = 0
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.SANS_SERIF }
        fun setup(list: List<String>, w: Float, h: Float, f: Float) { items = list; cellW = w; cellH = h; fontPx = f; sel = 0 }
        fun select(i: Int) { val n = i.coerceIn(0, items.size - 1); if (n != sel) { sel = n; invalidate() } }
        fun selected(): String? = items.getOrNull(sel)
        override fun onDraw(c: Canvas) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            p.color = colorShadow; p.style = Paint.Style.STROKE; p.strokeWidth = 1 * d
            c.drawRoundRect(rect, 8 * d, 8 * d, p)
            p.color = colorKey; p.style = Paint.Style.FILL
            c.drawRoundRect(rect, 8 * d, 8 * d, p)
            t.textSize = fontPx
            for ((i, s) in items.withIndex()) {
                val x0 = pad + i * cellW
                if (i == sel) {
                    rect.set(x0 + 2 * d, pad, x0 + cellW - 2 * d, pad + cellH)
                    p.color = colorHighlight
                    c.drawRoundRect(rect, 5 * d, 5 * d, p)
                }
                t.color = if (i == sel) Color.WHITE else colorText
                c.drawText(s, x0 + cellW / 2, pad + cellH / 2 - (t.descent() + t.ascent()) / 2, t)
            }
        }
    }
}
