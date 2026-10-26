package com.eqsync

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Finds genre tags for a track. Spotify retired its "audio features" API for new apps in 2024,
 * so this uses Last.fm (better per-track tags, free API key) or MusicBrainz (no key, artist-level).
 */
object GenreLookup {

    fun tags(artist: String, track: String, lastFmKey: String?): List<Pair<String, Int>> {
        val cleanTrack = cleanTitle(track)
        val mainArtist = artist.split(",", " & ", " feat", " ft.").first().trim()
        if (!lastFmKey.isNullOrBlank()) {
            runCatching { lastFm("track.gettoptags", lastFmKey, mainArtist, cleanTrack) }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
            runCatching { lastFm("artist.gettoptags", lastFmKey, mainArtist, null) }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return runCatching { musicBrainz(mainArtist) }.getOrDefault(emptyList())
    }

    /** Drops "- Remastered 2011", "(feat. X)", "[Live]" etc. that confuse lookups. */
    private fun cleanTitle(t: String) = t
        .replace(Regex("\\s*[(\\[].*?[)\\]]"), "")
        .replace(Regex("\\s+-\\s+.*$"), "")
        .trim()

    private fun lastFm(method: String, key: String, artist: String, track: String?): List<Pair<String, Int>> {
        var url = "https://ws.audioscrobbler.com/2.0/?method=$method&format=json&autocorrect=1" +
            "&api_key=${enc(key)}&artist=${enc(artist)}"
        if (track != null) url += "&track=${enc(track)}"
        val arr = JSONObject(get(url)).optJSONObject("toptags")?.optJSONArray("tag") ?: return emptyList()
        return (0 until minOf(arr.length(), 15)).map {
            val o = arr.getJSONObject(it)
            o.getString("name") to o.optInt("count", 0)
        }
    }

    private fun musicBrainz(artist: String): List<Pair<String, Int>> {
        val q = enc("artist:\"$artist\"")
        val json = JSONObject(get("https://musicbrainz.org/ws/2/artist/?query=$q&fmt=json&limit=1"))
        val a = json.optJSONArray("artists")?.optJSONObject(0) ?: return emptyList()
        val tags = a.optJSONArray("tags") ?: return emptyList()
        val raw = (0 until tags.length()).map {
            val o = tags.getJSONObject(it)
            o.getString("name") to o.optInt("count", 1)
        }
        // Normalise MusicBrainz vote counts to Last.fm's 0..100 scale.
        val max = raw.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
        return raw.map { it.first to (it.second * 100 / max) }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "EqSync/1.0 (personal Android app)")
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
