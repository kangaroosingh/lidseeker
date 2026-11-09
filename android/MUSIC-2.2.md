# Music 2.2 — Android Auto and song feedback

Music now exposes a Media3 library service for Android Auto. Browse Liked Songs,
recent albums (latest 100), artists and playlists; search the Navidrome library;
play music with the existing background player. Phone credentials are loaded by
the service without opening an Activity. Requests remain on the phone.

Like and Dislike are available in the phone library/search/Now Playing and as
car media custom actions (button placement is controlled by the host). Clear
feedback cancels either choice. The playlists are created on first use, owned
by the signed-in user:

- `Music — Liked Songs`
- `Music — Disliked Songs (delete after 30 days)`

The playlists are the source of truth; this does not modify Navidrome stars.
The app uses server IDs, never fuzzy matching, to update these lists. It never
rewrites a complete playlist to add/remove one song. Duplicate owned playlist
names fail without guessing, and another user's same-name playlist is ignored.
Cross-device simultaneous edits are subject to the server's playlist API; local
phone/car changes are serialized and the result is re-read before success.

## Server deletion companion

`server/disliked_cleanup.py` is required for deletion. The APK alone cannot
delete files through the Subsonic API. Configure the companion with the local
Navidrome database path, an explicitly allowed music root, an authorized
username and a private persistent state path. It reads Navidrome's DB in
read-only mode and stores countdowns separately. Default mode is dry run.
`--apply` persists timers and enables deletion. Use a systemd oneshot/timer.
Do not infer permission for additional users from a deployment for one account.

The companion checks every minute in the personal deployment. A music file is
eligible only after 30 full days from its first confirmed observation in the
Disliked Songs playlist. Any playlist edit resets that playlist's pending clocks
so a missed remove/re-add cannot inherit an old deadline. Replacing a file also
resets its clock. Clearing the dislike or liking again cancels deletion. Any
user's Music Liked Songs playlist protects the shared file. Keep the playlist
names unchanged. Counts may include songs awaiting the next Navidrome scan.

Only matching regular audio files inside the configured library can be unlinked.
Symlinks, traversal paths, other library roots and ambiguous playlist names are
refused. Album folders, artwork and unrelated files are never removed. Navidrome's
existing scheduled scan refreshes its library after deletion. There is no recycle
bin; source-playlist downloaders may download a track again independently.

## Install and enable Android Auto

Install the signed `Music-2.2.0.apk` over the previous Music app. Existing saved
connections remain. Open Music once and verify Navidrome connectivity, then
connect the phone to an Android Auto-compatible car. Keep Tailscale connected
when the server requires it.

For this sideloaded media APK, Android Auto may require its own developer setting:
open Android Auto settings, tap Version / Version and permission info 10 times,
accept developer mode, then overflow menu > Developer settings > Unknown sources.
Reconnect the car and enable Music in Customize launcher if needed.

Official guidance: https://developer.android.com/training/cars/testing
This supports projected Android Auto, not a standalone Android Automotive OS,
Tesla or arbitrary Geely head-unit APK installation. Real car compatibility must
be confirmed on the target phone/head unit. The APK is a personal prerelease.
