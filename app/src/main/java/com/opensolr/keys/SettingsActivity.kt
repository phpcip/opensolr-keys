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
            insets
        }

        findViewById<android.view.View>(R.id.btn_enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
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
        slider(R.id.haptic_strength, prefs.hapticStrength - 1) { prefs.hapticStrength = it + 1 }
        slider(R.id.volume, prefs.volume - 1) { prefs.volume = it + 1 }

        val haptic = findViewById<Switch>(R.id.haptic)
        haptic.isChecked = prefs.haptic
        haptic.setOnCheckedChangeListener { _, on -> prefs.haptic = on }
        val sound = findViewById<Switch>(R.id.sound)
        sound.isChecked = prefs.sound
        sound.setOnCheckedChangeListener { _, on -> prefs.sound = on }
    }

    private val onPrefChange = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "learned") renderLearned()
    }

    override fun onResume() {
        super.onResume()
        renderLearned()
        getSharedPreferences("keys", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(onPrefChange)
    }

    override fun onPause() {
        getSharedPreferences("keys", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(onPrefChange)
        super.onPause()
    }

    /** Words kept as typed; each row has an × that forgets the word. */
    private fun renderLearned() {
        val box = findViewById<LinearLayout>(R.id.learned)
        box.removeAllViews()
        val d = resources.displayMetrics.density
        val words = prefs.learned.sorted()
        if (words.isEmpty()) {
            box.addView(TextView(this).apply {
                text = getString(R.string.s_learned_empty); textSize = 14f; setTextColor(getColor(R.color.fg_muted))
                typeface = resources.getFont(R.font.space_grotesk_medium)
            })
            return
        }
        for (w in words) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = (44 * d).toInt() }
            row.addView(TextView(this).apply {
                text = w; textSize = 16f; setTextColor(getColor(R.color.fg)); typeface = resources.getFont(R.font.space_grotesk_medium)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply { weight = 1f })
            row.addView(TextView(this).apply {
                text = "×"; textSize = 22f; gravity = Gravity.CENTER; setTextColor(getColor(R.color.fg))
                setPadding((16 * d).toInt(), 0, (8 * d).toInt(), 0)
                setOnClickListener { prefs.forget(w); renderLearned() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (44 * d).toInt()))
            box.addView(row)
        }
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
