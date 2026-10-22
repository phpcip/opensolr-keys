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
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(0, bars.top, 0, bars.bottom)
            // keep the field you type in visible above the keyboard
            val field = findViewById<android.view.View>(R.id.try_it)
            if (field.isFocused) v.post { field.requestRectangleOnScreen(android.graphics.Rect(0, 0, field.width, field.height), false) }
            insets
        }

        findViewById<android.view.View>(R.id.btn_enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<android.view.View>(R.id.btn_learned).setOnClickListener {
            startActivity(Intent(this, WordsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.btn_choose).setOnClickListener {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }

        val en = findViewById<CheckBox>(R.id.lang_en)
        val ro = findViewById<CheckBox>(R.id.lang_ro)
        val langs = prefs.langs
        en.isChecked = "en" in langs
        ro.isChecked = "ro" in langs
        val saveLangs = { _: Any ->
            val set = LinkedHashSet<String>()
            if (en.isChecked) set.add("en")
            if (ro.isChecked) set.add("ro")
            prefs.langs = set
        }
        en.setOnCheckedChangeListener { b, _ -> saveLangs(b) }
        ro.setOnCheckedChangeListener { b, _ -> saveLangs(b) }

        slider(R.id.size, prefs.sizePercent - 80) { prefs.sizePercent = it + 80 }
        slider(R.id.offset, prefs.bottomOffsetDp) { prefs.bottomOffsetDp = it }
        val lpValue = findViewById<TextView>(R.id.longpress_value)
        lpValue.text = "${prefs.longPressMs} ms"
        slider(R.id.longpress, prefs.longPressMs / 50 - 1) { prefs.longPressMs = (it + 1) * 50; lpValue.text = "${prefs.longPressMs} ms" }
        slider(R.id.haptic_strength, prefs.hapticStrength - 1) { prefs.hapticStrength = it + 1 }
        slider(R.id.volume, prefs.volume - 1) { prefs.volume = it + 1 }

        val haptic = findViewById<Switch>(R.id.haptic)
        haptic.isChecked = prefs.haptic
        haptic.setOnCheckedChangeListener { _, on -> prefs.haptic = on }
        val sound = findViewById<Switch>(R.id.sound)
        sound.isChecked = prefs.sound
        sound.setOnCheckedChangeListener { _, on -> prefs.sound = on }
    }

    private fun slider(id: Int, value: Int, onChange: (Int) -> Unit) {
        val s = findViewById<SeekBar>(id)
        s.progress = value
        s.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) onChange(p) }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }
}
