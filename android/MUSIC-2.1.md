# Music 2.1 — smart search and connection checks

The main Search tab combines Navidrome library results with Lidseeker's song
catalog. Library results come first and can be played or opened immediately.
The two lookups fail independently, so a request-server outage does not discard
successful library results. YouTube is searched only when selected or when a
video URL is submitted. Playlist URLs still use Requests → Search → YouTube.

Before a Smart Search request is submitted, the app checks the library, catalog
request history and the latest YouTube request history. It compares normalized
titles and artist credits, drops common promotional suffixes, and preserves
live/remix distinctions. These are possible-match warnings, not guaranteed audio
fingerprints. Users can explicitly request another copy. Editing title/artist
invalidates the previous check. Failed checks are disclosed rather than reported
as successful no-duplicate results. YouTube song requests in the existing
Requests screen also check for likely library matches before proceeding.

Existing catalog/video ID duplicate checks remain in the backend. Playlist
imports continue using video-ID checks; per-track fuzzy library matching is not
performed for bulk playlist imports. Search and recent-history limits mean the
app cannot guarantee that every duplicate or alternate recording is detected.

Connections → Test connections / diagnose checks the active phone network,
Navidrome authentication and the request service's health endpoint and session.
DNS, timeout, TLS, address and sign-in failures have distinct next steps. A VPN
transport is described only as a VPN, not proof of Tailscale connectivity. The
app does not claim to detect or fix server-side key expiry. Browser and Tailscale
shortcuts help distinguish phone/app issues from unavailable servers.

No extra AI service, telemetry upload or password collection is introduced.
Checks contact only the user's configured music/request servers. The app retains
the existing encrypted authentication storage, signing key and package ID.

Build/test through the Gradle wrapper. Tests cover matching boundaries,
partial-source failures, explicit confirmation before submission, edited-metadata
invalidation, diagnostic classification and sanitized errors. Device tests cover
screen rendering without saved credentials and the existing screen-off player.
