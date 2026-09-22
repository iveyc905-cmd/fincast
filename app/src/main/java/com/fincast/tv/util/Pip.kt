package com.fincast.tv.util

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Picture-in-picture plumbing shared between the activity and the player page.
 *
 * The page says whether PiP is wanted (a channel is up); the activity acts on
 * that when the user leaves and reports back when the window shrinks or grows.
 */
object Pip {
    /** True while the activity is displayed as a floating PiP window. */
    var inPip by mutableStateOf(false)

    /** Set by the player page while a channel is playing. */
    @Volatile var wanted = false

    fun applyParams(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ can shrink the window itself on a home swipe, which
            // looks far smoother than the onUserLeaveHint fallback.
            builder.setAutoEnterEnabled(wanted)
        }
        runCatching { activity.setPictureInPictureParams(builder.build()) }
    }

    /** Fallback for Android 8–11, where the app must enter PiP itself. */
    fun enterIfWanted(activity: Activity) {
        if (!wanted || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return // auto-enter handles it
        runCatching {
            activity.enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        }
    }
}
