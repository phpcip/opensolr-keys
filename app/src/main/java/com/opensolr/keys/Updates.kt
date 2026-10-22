package com.opensolr.keys

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Updates without any network access from the keyboard: the store that installed the app, or the
 * GitHub releases page in the browser, does the checking and the installing.
 */
object Updates {
    private const val RELEASES = "https://github.com/phpcip/opensolr-keys/releases/latest"
    private const val PLAY = "com.android.vending"

    private fun fromPlay(ctx: Context): Boolean = try {
        val installer = if (Build.VERSION.SDK_INT >= 30) ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName
        else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(ctx.packageName)
        installer == PLAY
    } catch (_: Exception) { false }

    fun open(ctx: Context) {
        if (BuildConfig.PLAY_BUILD || fromPlay(ctx)) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
        }
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
