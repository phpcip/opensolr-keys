package com.opensolr.keys

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Key click sound (the system's own key tone) and vibration, both with user-set strength. */
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

    private fun vibrate() {
        val v = vibrator ?: return
        val amp = (1 + prefs.hapticStrength * 2.54f).toInt().coerceIn(1, 255)
        if (v.hasAmplitudeControl()) v.vibrate(VibrationEffect.createOneShot(10, amp))
        else v.vibrate(VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun release() = pool.release()
}
