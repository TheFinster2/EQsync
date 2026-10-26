package com.eqsync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Listens for Spotify track changes, looks up the genre, and applies a matching EQ curve
 * to Spotify's audio output (or to the whole phone's output as a fallback).
 */
class EqService : Service() {

    companion object {
        const val SPOTIFY = "com.spotify.music"
        private const val TAG = "EqSync"
        private const val CHANNEL = "eqsync"
        private const val GLOBAL_SESSION = 0

        fun refresh(ctx: Context) {
            if (State.running) ctx.startService(Intent(ctx, EqService::class.java).setAction("refresh"))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var effect: DynamicsProcessing? = null
    private var effectSession = -1
    private var current = FloatArray(Presets.FREQS.size)
    private var target = Presets.DEFAULT.gains
    private var lastTrackKey = ""
    private val rampToken = Any()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                "com.spotify.music.metadatachanged" -> onTrack(
                    intent.getStringExtra("artist") ?: return,
                    intent.getStringExtra("track") ?: return,
                )
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                    if (intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) == SPOTIFY) {
                        attach(intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, GLOBAL_SESSION))
                    }
                }
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                    val s = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
                    if (s == effectSession && s != GLOBAL_SESSION) attach(GLOBAL_SESSION)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notification("Waiting for Spotify…"))
        val filter = IntentFilter().apply {
            addAction("com.spotify.music.metadatachanged")
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        else registerReceiver(receiver, filter)

        State.running = true
        attach(State.prefs(this).getInt("spotifySession", GLOBAL_SESSION))
        applyPreset(forcedPreset() ?: Presets.DEFAULT)
    }

    private fun forcedPreset() = Presets.byName(State.prefs(this).getString("forcedPreset", "")!!)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "refresh") {
            // Settings changed: re-pick the preset for the current track.
            lastTrackKey = ""
            if (State.track.isNotEmpty()) onTrack(State.artist, State.track)
            else applyPreset(forcedPreset() ?: Presets.DEFAULT)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        main.removeCallbacksAndMessages(null)
        effect?.release()
        effect = null
        io.shutdownNow()
        State.running = false
        State.target = "Stopped"
        notifyUi()
        super.onDestroy()
    }

    // ---- Track handling ----

    private fun onTrack(artist: String, track: String) {
        val key = "$artist|$track"
        if (key == lastTrackKey) return
        lastTrackKey = key
        State.artist = artist
        State.track = track
        State.tags = emptyList()
        notifyUi()

        val prefs = State.prefs(this)
        forcedPreset()?.let { applyPreset(it); return }
        io.execute {
            val tags = cachedTags(key) ?: GenreLookup.tags(artist, track, prefs.getString("lastFmKey", null))
                .also { if (it.isNotEmpty()) cacheTags(key, it) }
            val preset = Presets.pick(tags)
            main.post {
                if (key != lastTrackKey) return@post // track changed while we were looking up
                State.tags = tags.take(5).map { it.first }
                applyPreset(preset)
            }
        }
    }

    private fun cachedTags(key: String): List<Pair<String, Int>>? {
        val raw = State.prefs(this).getString("tags:$key", null) ?: return null
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getJSONArray(it).let { p -> p.getString(0) to p.getInt(1) } }
    }

    private fun cacheTags(key: String, tags: List<Pair<String, Int>>) {
        val arr = JSONArray()
        tags.forEach { arr.put(JSONArray().put(it.first).put(it.second)) }
        State.prefs(this).edit().putString("tags:$key", arr.toString()).apply()
    }

    // ---- EQ ----

    private fun applyPreset(preset: Presets.Preset) {
        val bass = State.prefs(this).getInt("bassOffset", 0).toFloat()
        // Extra bass goes fully into the lowest two bands and half into 125 Hz.
        target = preset.gains.mapIndexed { i, g ->
            g + when (i) { 0, 1 -> bass; 2 -> bass / 2; else -> 0f }
        }.toFloatArray()
        State.preset = preset.name
        notifyUi()
        updateNotification()
        rampTo(target)
    }

    /** Fades between curves over about a second so changes aren't jarring. */
    private fun rampTo(goal: FloatArray) {
        main.removeCallbacksAndMessages(rampToken)
        val steps = 12
        val start = current.copyOf()
        fun tick(n: Int) {
            val t = n.toFloat() / steps
            current = FloatArray(goal.size) { start[it] + (goal[it] - start[it]) * t }
            writeGains(current)
            if (n < steps) main.postAtTime({ tick(n + 1) }, rampToken, SystemClock.uptimeMillis() + 80)
        }
        tick(1)
    }

    private fun attach(session: Int) {
        if (session == effectSession && effect != null) return
        effect?.release()
        effect = null
        effectSession = -1
        try {
            val bands = Presets.FREQS.size
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,            // stereo
                true, bands,  // pre-EQ: our 10 bands
                false, 0,     // no multiband compressor
                false, 0,     // no post-EQ
                true,         // limiter, to stop boosted bass from clipping
            ).build()
            val dp = DynamicsProcessing(Int.MAX_VALUE, session, config)
            dp.setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f)
            )
            dp.enabled = true
            effect = dp
            effectSession = session
            State.target = if (session == GLOBAL_SESSION) "Whole-phone output" else "Spotify (session $session)"
            writeGains(current)
        } catch (e: Exception) {
            Log.w(TAG, "Could not attach EQ to session $session", e)
            State.target = "Couldn't attach EQ: ${e.message}. Play a song in Spotify to retry."
        }
        notifyUi()
        updateNotification()
    }

    private fun writeGains(gains: FloatArray) {
        val dp = effect ?: return
        val freqs = Presets.FREQS
        try {
            for (i in freqs.indices) {
                // Each band's cutoff is the geometric midpoint up to the next band's centre.
                val cutoff = if (i < freqs.size - 1) sqrt(freqs[i].toFloat() * freqs[i + 1]) else 20000f
                dp.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, cutoff, gains[i]))
            }
            // Pull overall level down a little when boosting hard, leaving the limiter less to do.
            val headroom = (gains.maxOrNull() ?: 0f) - 3f
            dp.setInputGainAllChannelsTo(if (headroom > 0) -headroom else 0f)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write EQ", e)
        }
    }

    // ---- UI plumbing ----

    private fun notifyUi() = main.post { State.listener?.invoke() }

    private fun updateNotification() {
        val text = if (State.track.isEmpty()) State.target else "${State.preset} · ${State.track}"
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "EQ Sync", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("EQ Sync")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
