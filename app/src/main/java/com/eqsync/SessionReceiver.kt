package com.eqsync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect

/** Records Spotify's audio session so EqService can attach to it even if it starts later. */
class SessionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) != EqService.SPOTIFY) return
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        val prefs = State.prefs(ctx).edit()
        if (intent.action == AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION) {
            prefs.putInt("spotifySession", session)
        } else {
            prefs.remove("spotifySession")
        }
        prefs.apply()
    }
}
