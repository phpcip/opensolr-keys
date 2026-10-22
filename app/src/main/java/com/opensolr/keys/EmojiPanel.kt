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
    private val onEmoji: (String) -> Unit, private val onAbc: () -> Unit, private val onDelete: () -> Unit
) : LinearLayout(ctx) {
    private val d = resources.displayMetrics.density
    private val grid = GridView(ctx)
    private val tabs = LinearLayout(ctx)
    private var current = Emoji.CATEGORIES[0].first

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
        grid.setOnItemClickListener { _, _, pos, _ -> onEmoji(grid.adapter.getItem(pos) as String) }
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
        bottom.addView(abc, LayoutParams(0, (42 * d).roundToInt()).apply { weight = 1f; marginEnd = (6 * d).toInt() })
        bottom.addView(View(ctx), LayoutParams(0, 1).apply { weight = 3f })
        bottom.addView(del, LayoutParams(0, (42 * d).roundToInt()).apply { weight = 1f; marginStart = (6 * d).toInt() })
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        show(current)
    }

    private fun keyView(label: String) = TextView(context).apply {
        text = label; textSize = 16f; gravity = Gravity.CENTER; setTextColor(kb.colorText)
        typeface = Typeface.SANS_SERIF
        background = GradientDrawable().apply { cornerRadius = 5 * d; setColor(kb.colorSpecial) }
    }

    fun show(cat: String) {
        current = cat
        for ((i, v) in (0 until tabs.childCount).map { it to tabs.getChildAt(it) }) {
            val sel = Emoji.CATEGORIES[i].first == cat
            v.background = if (sel) GradientDrawable().apply { cornerRadius = 5 * d; setColor(kb.colorKey) } else null
        }
        val items = Emoji.byCategory[cat] ?: emptyList()
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
                val t = (cv as? TextView) ?: TextView(context).apply {
                    textSize = 26f; gravity = Gravity.CENTER
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (46 * d).roundToInt())
                }
                t.text = items[p]
                return t
            }
        }
    }
}
