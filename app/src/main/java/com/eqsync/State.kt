package com.eqsync

import android.content.Context

/** In-process state shared between the service and the screen. */
object State {
    @Volatile var running = false
    @Volatile var artist = ""
    @Volatile var track = ""
    @Volatile var tags: List<String> = emptyList()
    @Volatile var preset = ""
    @Volatile var target = "Waiting for Spotify"
    var listener: (() -> Unit)? = null

    fun prefs(ctx: Context) = ctx.getSharedPreferences("eqsync", Context.MODE_PRIVATE)
}
