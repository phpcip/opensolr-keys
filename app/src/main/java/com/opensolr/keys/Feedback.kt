package com.opensolr.keys

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Key click sound (the system's own key tone) and the key tap felt under the finger, both with user-set strength. */
class Feedback(private val ctx: Context, private val prefs: Prefs) {
    enum class Kind { STANDARD, DELETE, SPACE, RETURN }

    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    private val ids = HashMap<Kind, Int>()

    init {
        // the Pixel tap sound, bundled so it is the same on every phone
        val id = pool.load(ctx, R.raw.tick, 1)
        for (k in Kind.entries) ids[k] = id
    }

    fun key(kind: Kind) {
        if (prefs.sound) {
            val vol = prefs.volume / 100f
            val id = ids[kind]
            if (id != null) pool.play(id, vol, vol, 1, 0, 1f)
            else audio.playSoundEffect(AudioManager.FX_KEY_CLICK, vol)
        }
        if (prefs.haptic) vibrate()
    }

    /**
     * One short pulse per key, its force following the strength setting all the way: a faint, very short tick at the bottom,
     * longer and harder towards the top. Motors that take scaled primitives get a scaled tick, then a scaled click.
     */
    fun vibrate() {
        val v = vibrator ?: return
        val s = prefs.hapticStrength.coerceIn(1, 100)
        if (Build.VERSION.SDK_INT >= 30 &&
            v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK, VibrationEffect.Composition.PRIMITIVE_CLICK)) {
            val effect = VibrationEffect.startComposition()
            if (s <= 50) effect.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.1f + 0.9f * s / 50f)
            else effect.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.3f + 0.7f * (s - 50) / 50f)
            v.vibrate(effect.compose())
            return
        }
        val ms = 4L + s / 12
        val amp = (25 + s * 2.3f).toInt().coerceIn(1, 255)
        v.vibrate(VibrationEffect.createOneShot(ms, if (v.hasAmplitudeControl()) amp else VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** The tap alone, without the sound: one for every character a held delete removes. */
    fun tick() { if (prefs.haptic) vibrate() }

    fun release() = pool.release()
}
