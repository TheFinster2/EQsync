package com.eqsync

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
        val prefs = State.prefs(this)
        val pad = dp(16)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun heading(t: String) = col.addView(TextView(this).apply {
            text = t
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, pad, 0, dp(6))
        })
        fun body(t: String) = col.addView(TextView(this).apply { text = t; textSize = 14f })

        heading("Now playing")
        status = TextView(this).apply { textSize = 15f }
        col.addView(status)
        toggle = Button(this).apply {
            setOnClickListener {
                val svc = Intent(this@MainActivity, EqService::class.java)
                if (State.running) stopService(svc) else startForegroundService(svc)
                postDelayed({ render() }, 300)
            }
        }
        col.addView(toggle)

        heading("Preset")
        val names = listOf("Auto (by genre)") + Presets.ALL.map { it.name } + Presets.DEFAULT.name
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
            setSelection(names.indexOf(prefs.getString("forcedPreset", "")).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val value = if (pos == 0) "" else names[pos]
                    if (value != prefs.getString("forcedPreset", "")) {
                        prefs.edit().putString("forcedPreset", value).apply()
                        EqService.refresh(this@MainActivity)
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        col.addView(spinner)

        val bassLabel = TextView(this)
        fun bassText(v: Int) { bassLabel.text = "Extra bass: ${if (v > 0) "+" else ""}$v dB" }
        bassText(prefs.getInt("bassOffset", 0))
        col.addView(bassLabel)
        col.addView(SeekBar(this).apply {
            max = 10 // maps to -4..+6 dB
            progress = prefs.getInt("bassOffset", 0) + 4
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = bassText(p - 4)
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    prefs.edit().putInt("bassOffset", progress - 4).apply()
                    EqService.refresh(this@MainActivity)
                }
            })
        })

        heading("Genre lookup")
        body("Optional: a free Last.fm API key gives per-song genres (last.fm/api/account/create). " +
            "Without one, genres come from MusicBrainz per artist.")
        val keyField = EditText(this).apply {
            hint = "Last.fm API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(prefs.getString("lastFmKey", ""))
        }
        col.addView(keyField)
        col.addView(Button(this).apply {
            text = "Save key"
            setOnClickListener {
                prefs.edit().putString("lastFmKey", keyField.text.toString().trim()).apply()
                Toast.makeText(this@MainActivity, "Saved", Toast.LENGTH_SHORT).show()
                EqService.refresh(this@MainActivity)
            }
        })

        heading("One-time setup")
        body("1. Spotify → Settings → turn ON \"Device broadcast status\" (lets this app see the song).\n\n" +
            "2. Shokz app → set the OpenFit 2+ EQ to Standard, so the two EQs don't stack.\n\n" +
            "3. Let this app run in the background, or the S10 will kill it:")
        col.addView(Button(this).apply {
            text = "Open battery settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        State.listener = { render() }
        render()
    }

    override fun onPause() {
        State.listener = null
        super.onPause()
    }

    private fun render() {
        toggle.text = if (State.running) "Stop" else "Start"
        status.text = if (!State.running) "Not running. Tap Start." else buildString {
            if (State.track.isEmpty()) append("Play something in Spotify…\n")
            else {
                append("${State.track}\n${State.artist}\n\n")
                append("Genres: ${State.tags.joinToString().ifEmpty { "looking up…" }}\n")
                append("EQ: ${State.preset}\n")
            }
            append("Applied to: ${State.target}")
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
