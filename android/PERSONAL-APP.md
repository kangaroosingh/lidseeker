# Harsh Music Android fork

Native Android fork of Lidseeker, licensed under the repository's existing license.
Application ID: `com.kangaroosingh.musicrequests`; version 1.0.0.
It installs alongside the original application.

Sign in using your Lidseeker server URL and existing account. No server credentials
are embedded. The server must have the optional YouTube endpoints enabled.
Search → YouTube supports text search, video links, bounded playlist previews,
editable title/artist confirmation, request status and failed-job retry.
Playlist imports add tracks once; they do not mirror future playlist changes.
Downloads run on the server and become available after Navidrome scans them.

Build with JDK 17 or newer, Android SDK 36 and the Gradle wrapper:

```
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

For a signed release, set HARSH_MUSIC_KEYSTORE to a private keystore path and
HARSH_MUSIC_KEY_PASSWORD to its password. The key alias is `harsh-music`.
Then run `./gradlew :app:assembleRelease`. Keep the signing key private and retain
it for future updates. Never commit the keystore, password or local.properties.

The standalone application requires Android 8 or newer and access to your server
(including Tailscale when the server is private). It requests music; it is not
a music player or Navidrome streaming client.
