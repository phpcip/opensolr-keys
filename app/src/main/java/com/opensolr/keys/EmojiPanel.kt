package com.opensolr.keys

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Emoji picker shown in place of the keys: category tabs, grid, ABC and delete. */
class EmojiPanel(
    ctx: Context, private val kb: KeyboardView, private val heightPx: Int,
    private val recent: () -> List<String>,
    private val onEmoji: (String) -> Unit, private val onTone: (String, String) -> Unit, private val onAbc: () -> Unit, private val onDelete: () -> Unit
) : LinearLayout(ctx) {
    private val d = resources.displayMetrics.density
    private val grid = GridView(ctx)
    private val tabs = LinearLayout(ctx)
    private var current = if (recent().isNotEmpty()) Emoji.RECENT else Emoji.CATEGORIES[1].first

    init {
        orientation = VERTICAL
        setBackgroundColor(kb.colorBg)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx)

        val scroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        tabs.orientation = HORIZONTAL
        tabs.setPadding((6 * d).toInt(), (6 * d).toInt(), (6 * d).toInt(), 0)
        for ((cat, glyph) in Emoji.CATEGORIES) {
            val t = TextView(ctx).apply {
                text = glyph; textSize = 20f; gravity = Gravity.CENTER
                setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
                setOnClickListener { show(cat) }
            }
            tabs.addView(t, LayoutParams(0, LayoutParams.WRAP_CONTENT).apply { weight = 1f })
        }
        scroll.addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        grid.numColumns = 8
        grid.stretchMode = GridView.STRETCH_COLUMN_WIDTH
        grid.setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
        grid.isVerticalScrollBarEnabled = false
        grid.setOnItemClickListener { _, _, pos, _ -> onEmoji(shown(grid.adapter.getItem(pos) as String)) }
        // a long press on an emoji that comes in skin tones opens them; the one picked is remembered for that emoji
        grid.setOnItemLongClickListener { _, cell, pos, _ -> showTones(cell, grid.adapter.getItem(pos) as String) }
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0).apply { weight = 1f })

        val bottom = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        bottom.setPadding((3 * d).toInt(), (8 * d).toInt(), (3 * d).toInt(), (5 * d).toInt())
        val abc = keyView(context.getString(R.string.s_abc)).apply { setOnClickListener { onAbc() } }
        val del = keyView("⌫")
        var repeating = false
        val repeat = object : Runnable { override fun run() { onDelete(); repeating = true; del.postDelayed(this, 50) } }
        del.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { repeating = false; onDelete(); v.postDelayed(repeat, 350) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.removeCallbacks(repeat)
            }
            true
        }
        bottom.addView(abc, LayoutParams(0, (48 * d).roundToInt()).apply { weight = 1f; marginEnd = (6 * d).toInt() })
        bottom.addView(View(ctx), LayoutParams(0, 1).apply { weight = 3f })
        bottom.addView(del, LayoutParams(0, (48 * d).roundToInt()).apply { weight = 1f; marginStart = (6 * d).toInt() })
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        show(current)
    }

    /** A category shows each emoji in the skin tone chosen for it; Recent shows exactly what was used. */
    private fun shown(item: String) = if (current == Emoji.RECENT) item else Emoji.toned(item)

    private var tonePopup: android.widget.PopupWindow? = null

    private fun showTones(cell: View, item: String): Boolean {
        val base = Emoji.plain(item)
        val forms = Emoji.variantsOf(base)
        if (forms.isEmpty()) return false
        tonePopup?.dismiss()
        val side = (44 * d).roundToInt()
        val pad = (4 * d).roundToInt()
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { cornerRadius = 8 * d; setColor(kb.colorKey); setStroke((1 * d).roundToInt(), kb.colorShadow) }
        }
        val popup = android.widget.PopupWindow(context).apply {
            isClippingEnabled = false; isTouchable = true; isFocusable = false; isOutsideTouchable = true
            inputMethodMode = android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED; animationStyle = 0
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        fun cellFor(e: String) = TextView(context).apply {
            text = e; textSize = 26f; gravity = Gravity.CENTER
            if (e == Emoji.toned(base)) background = GradientDrawable().apply { cornerRadius = 5 * d; setColor(kb.colorSpecial) }
            setOnClickListener { onTone(base, e); onEmoji(e); popup.dismiss(); (grid.adapter as? BaseAdapter)?.notifyDataSetChanged() }
        }
        // one tone per person: the emoji and its five tones in a row; pairs of people: the emoji, then five rows of five
        val rows = if (forms.size <= 5) listOf(listOf(base) + forms) else listOf(listOf(base)) + forms.chunked(5)
        for (r in rows) {
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (e in r) line.addView(cellFor(e), LayoutParams(side, side))
            box.addView(line)
        }
        val w = rows.maxOf { it.size } * side + 2 * pad
        val h = rows.size * side + 2 * pad
        val at = IntArray(2); cell.getLocationInWindow(at)
        val x = (at[0] + cell.width / 2 - w / 2).coerceIn(pad, (resources.displayMetrics.widthPixels - w - pad).coerceAtLeast(pad))
        popup.contentView = box; popup.width = w; popup.height = h
        popup.showAtLocation(this, Gravity.NO_GRAVITY, x, at[1] - h - pad)
        tonePopup = popup
        return true
    }

    override fun onDetachedFromWindow() { tonePopup?.dismiss(); super.onDetachedFromWindow() }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) tonePopup?.dismiss()
    }

    private fun keyView(label: String) = TextView(context).apply {
        text = label; textSize = 16f; gravity = Gravity.CENTER; setTextColor(kb.colorText)
        typeface = Typeface.SANS_SERIF
        background = GradientDrawable().apply { cornerRadius = 5 * d; setColor(kb.colorSpecial) }
    }

    /** The Recent tab is rebuilt when the panel opens, never under the finger. */
    fun refreshRecent() { if (current == Emoji.RECENT) show(current) }

    fun show(cat: String) {
        current = cat
        for ((i, v) in (0 until tabs.childCount).map { it to tabs.getChildAt(it) }) {
            val sel = Emoji.CATEGORIES[i].first == cat
            v.background = if (sel) GradientDrawable().apply { cornerRadius = 5 * d; setColor(kb.colorKey) } else null
        }
        val items = if (cat == Emoji.RECENT) recent() else Emoji.byCategory[cat] ?: emptyList()
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val t = (cv as? TextView) ?: TextView(context).apply {
                    textSize = 26f; gravity = Gravity.CENTER
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * d).roundToInt())
                }
                t.text = shown(items[p])
                return t
            }
        }
    }
}
