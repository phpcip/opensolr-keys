package com.opensolr.keys

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/** Key click sound (the system's own key tone) and the key tap felt under the finger, both with user-set strength. */
class Feedback(private val ctx: Context, private val prefs: Prefs) {
    /** The view on screen (the keys, or the strength slider): the system plays its own tick through it. */
    var view: View? = null
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
     * The key tap is one of the phone's own tuned effects, never a raw pulse (a motor without braking turns that into a buzz).
     * Four levels, lightest first: the system tick (the one sliders give), tick, click, heavy click; motors that take
     * scaled primitives play a soft or full tick, then a soft or full click.
     */
    fun vibrate() {
        val v = vibrator ?: return
        val level = prefs.hapticLevel
        if (Build.VERSION.SDK_INT >= 30 &&
            v.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK, VibrationEffect.Composition.PRIMITIVE_CLICK)) {
            val primitive = if (level < 2) VibrationEffect.Composition.PRIMITIVE_TICK else VibrationEffect.Composition.PRIMITIVE_CLICK
            v.vibrate(VibrationEffect.startComposition().addPrimitive(primitive, if (level % 2 == 0) 0.45f else 1f).compose())
            return
        }
        if (level == 0 && view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) == true) return
        if (Build.VERSION.SDK_INT >= 29) {
            v.vibrate(VibrationEffect.createPredefined(when (level) {
                0, 1 -> VibrationEffect.EFFECT_TICK
                2 -> VibrationEffect.EFFECT_CLICK
                else -> VibrationEffect.EFFECT_HEAVY_CLICK
            }))
            return
        }
        v.vibrate(VibrationEffect.createOneShot(10, if (v.hasAmplitudeControl()) 60 + level * 65 else VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** The tap alone, without the sound: one for every character a held delete removes. */
    fun tick() { if (prefs.haptic) vibrate() }

    fun release() = pool.release()
}
