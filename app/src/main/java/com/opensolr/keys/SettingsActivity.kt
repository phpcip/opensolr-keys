package com.opensolr.keys

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.SeekBar
import android.widget.Switch

class SettingsActivity : Activity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        setContentView(R.layout.activity_settings)
        prefs = Prefs(this)
        val scroll = findViewById<android.view.View>(R.id.scroll)
        var imeShown = false
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(0, bars.top, 0, bars.bottom)
            // The field you type in is brought above the keyboard once, when the keyboard comes up; a keyboard that
            // only changes height (the size and offset sliders) leaves the page where it is
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom > 0
            val field = findViewById<android.view.View>(R.id.try_it)
            if (ime && !imeShown && field.isFocused) v.post { field.requestRectangleOnScreen(android.graphics.Rect(0, 0, field.width, field.height), false) }
            imeShown = ime
            insets
        }

        findViewById<android.view.View>(R.id.btn_enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<TextView>(R.id.version).text = getString(R.string.s_version, BuildConfig.VERSION_NAME)
        findViewById<android.view.View>(R.id.btn_update).setOnClickListener { Updates.open(this) }
        findViewById<android.view.View>(R.id.btn_defaults).setOnClickListener { v ->
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            prefs.restoreDefaults()
            // in place: a recreate() would hide the keyboard and give the controls back their old state
            bindValues()
        }
        findViewById<android.view.View>(R.id.btn_learned).setOnClickListener {
            startActivity(Intent(this, WordsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.btn_choose).setOnClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }

        val en = findViewById<CheckBox>(R.id.lang_en)
        val ro = findViewById<CheckBox>(R.id.lang_ro)
        val saveLangs = { _: Any ->
            val set = LinkedHashSet<String>()
            if (en.isChecked) set.add("en")
            if (ro.isChecked) set.add("ro")
            prefs.langs = set
        }
        en.setOnCheckedChangeListener { b, _ -> if (!binding) saveLangs(b) }
        ro.setOnCheckedChangeListener { b, _ -> if (!binding) saveLangs(b) }

        slider(R.id.size, R.id.size_value, { prefs.sizePercent - 80 }, { "${prefs.sizePercent}%" }) { prefs.sizePercent = it + 80 }
        slider(R.id.offset, R.id.offset_value, { prefs.bottomOffsetDp }, { "${prefs.bottomOffsetDp} dp" }) { prefs.bottomOffsetDp = it }
        slider(R.id.longpress, R.id.longpress_value, { prefs.longPressMs / 50 - 1 }, { "${prefs.longPressMs} ms" }) { prefs.longPressMs = (it + 1) * 50 }
        slider(R.id.preview, R.id.preview_value, { prefs.previewMs / 10 - 1 }, { "${prefs.previewMs} ms" }) { prefs.previewMs = (it + 1) * 10 }
        slider(R.id.haptic_strength, R.id.haptic_strength_value, { prefs.hapticStrength - 1 }, { getString(R.string.s_strength) + ": ${prefs.hapticStrength}%" }) { prefs.hapticStrength = it + 1 }
        slider(R.id.volume, R.id.volume_value, { prefs.volume - 1 }, { getString(R.string.s_volume) + ": ${prefs.volume}%" }) { prefs.volume = it + 1 }

        val haptic = findViewById<Switch>(R.id.haptic)
        haptic.setOnCheckedChangeListener { _, on -> if (!binding) prefs.haptic = on }
        val sound = findViewById<Switch>(R.id.sound)
        sound.setOnCheckedChangeListener { _, on -> if (!binding) prefs.sound = on }
        bindValues()
    }

    private var binding = false

    /** Every control shows what is saved; only the user's own touches write back. */
    private fun bindValues() {
        binding = true
        val langs = prefs.langs
        findViewById<CheckBox>(R.id.lang_en).isChecked = "en" in langs
        findViewById<CheckBox>(R.id.lang_ro).isChecked = "ro" in langs
        for (r in sliderRefresh) r()
        findViewById<Switch>(R.id.haptic).isChecked = prefs.haptic
        findViewById<Switch>(R.id.sound).isChecked = prefs.sound
        binding = false
    }

    override fun onRestoreInstanceState(state: Bundle) {
        // the saved view state is older than the prefs; the prefs win
        binding = true
        super.onRestoreInstanceState(state)
        bindValues()
    }

    /** Puts every slider and its exact value back from the saved prefs. */
    private val sliderRefresh = ArrayList<() -> Unit>()

    private fun slider(id: Int, labelId: Int, position: () -> Int, label: () -> String, onChange: (Int) -> Unit) {
        val s = findViewById<SeekBar>(id)
        val l = findViewById<TextView>(labelId)
        sliderRefresh.add { s.progress = position(); l.text = label() }
        s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                // a tick at every step, so the slider is felt as well as seen
                sb.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                onChange(p)
                l.text = label()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }
}
