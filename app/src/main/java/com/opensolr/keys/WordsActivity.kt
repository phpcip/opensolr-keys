package com.opensolr.keys

import android.app.Activity
import android.os.Bundle
import android.view.WindowInsets
import android.widget.EditText

/** The words kept as typed, one per line, edited as plain text. */
class WordsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        setContentView(R.layout.activity_words)
        val prefs = Prefs(this)
        val root = findViewById<android.view.View>(R.id.words_root)
        val pad = (20 * resources.displayMetrics.density).toInt()
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(pad, pad + bars.top, pad, pad + bars.bottom)
            insets
        }
        val box = findViewById<EditText>(R.id.words)
        box.setText(prefs.learned.sorted().joinToString("\n"))
        findViewById<android.view.View>(R.id.btn_save).setOnClickListener {
            prefs.learned = box.text.toString().lines().map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
            finish()
        }
    }
}
