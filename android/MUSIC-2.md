# Music 2.0

Music combines a native Navidrome player with the existing Lidseeker requests.
It retains application ID `com.kangaroosingh.musicrequests` and the owner's signing
key, so the signed 2.0 APK upgrades Harsh Music 1.0 without uninstalling it.

Features:
- Dark charcoal interface with green accents, album grids and bottom navigation.
- Navidrome albums, artists, playlists, library search and album pagination.
- Foreground Media3 playback service, notification and lock-screen controls,
  audio-focus handling, headphone-disconnect pause and screen-off playback.
- Mini-player, now-playing view, seeking, shuffle, repeat and editable play queue.
- Existing Lidseeker discovery, song/album/artist requests, YouTube video and
  playlist imports, request history and server settings.
- One connection form sends the same entered username/password to both services.
  Both accounts must already accept those credentials; the app does not change
  server-side account passwords. The two HTTPS server addresses remain separate.
  If Navidrome succeeds but requests fail, the app states the partial result.

Navidrome authentication uses Subsonic salted MD5 tokens over verified HTTPS.
The password is not persisted. Tokens and salts are stored with Android Keystore-
backed EncryptedSharedPreferences; Lidseeker keeps its existing encrypted JWT.
The app does not bypass certificate validation or collect YouTube cookies.
No personal server addresses or credentials are compiled into the public APK.

This release streams music. Offline downloads, Android Auto library browsing,
playlist editing, casting and playback queue restoration after process death
are not implemented. The playback service survives closing the activity while
music is playing, subject to Android's normal service lifecycle.

Build with the existing Gradle wrapper and Android SDK 36. Release signing uses
HARSH_MUSIC_KEYSTORE and HARSH_MUSIC_KEY_PASSWORD as documented in PERSONAL-APP.md.
Run `:app:testDebugUnitTest` and `:app:connectedDebugAndroidTest`. The device test
uses generated local WAV audio to verify progression with the screen switched off;
it does not claim to validate a real user's server credentials or network.

Based on Lidseeker; retain the original repository license and attribution.
