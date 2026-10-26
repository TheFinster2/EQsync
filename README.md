# EQ Sync

Android app (Galaxy S10 / Android 9–12+) that watches what Spotify is playing, looks up the
song's genre, and applies a matching 10-band EQ, tuned for open-ear buds like the Shokz OpenFit 2+.

## How it works

1. **Song detection:** Spotify's "Device broadcast status" setting broadcasts each track change.
2. **Genre:** Last.fm per-track tags (free API key), or MusicBrainz artist tags when there's no key.
   Results are cached per song.
3. **EQ:** Android's `DynamicsProcessing` effect attaches to Spotify's audio session (falling back to
   the whole phone's output), fades to the new curve over ~1 s, and runs a limiter so bass boosts don't clip.

Presets: Hip-Hop, Electronic, R&B, Rock, Pop, Latin, Jazz & Soul, Acoustic, Classical, Spoken, Balanced.
Every curve has a low-end lift, because open-ear buds lose bass. Edit `Presets.kt` to tune them.

### Why the EQ isn't written to the earbuds

The OpenFit 2+ stores its EQ on the buds, but the Shokz app uses a private, undocumented Bluetooth
protocol. Shokz has no API that other apps can use. EQ on the phone gives the same sound, and it
works with any headphones.

## Build

- **No computer:** push this folder to a GitHub repo. The included Action builds the APK.
  Open the repo's Actions tab → latest run → download **EqSync-apk**, unzip it, and install it on the phone
  (allow "Install unknown apps" for your browser/files app).
- **With Android Studio:** open the folder → Run, or `./gradlew assembleRelease`.

## Setup on the phone

1. Spotify → Settings → **Device broadcast status: ON**.
2. Shokz app → EQ → **Standard**, so the two EQs don't stack.
3. Open EQ Sync → **Start**. Settings → Apps → EQ Sync → Battery → **Unrestricted**.
4. Optional: paste a Last.fm API key (https://www.last.fm/api/account/create) for more accurate genres.

If "Applied to" says it couldn't attach, open Spotify and play a song. Spotify announces its audio
session when playback starts.
