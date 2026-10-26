# Optional YouTube search and requests

The web Search page has a **YouTube** tab. Search by song/artist or paste a video
URL. Select Request, check the song title and artist, and confirm. A playlist URL
shows a preview (default first 25 entries, maximum 50) before importing exactly
the displayed video IDs. Repeating a request skips queued/downloaded songs;
failed songs can be retried. This imports once, not an automatic playlist sync.

YouTube imports bypass Lidarr/MusicBrainz and use a separate queue. The native
Android client is unchanged; use the web page on a phone for this feature.
Users should only download content they are entitled to download.

## Enable on a native installation

Install current `yt-dlp[default]`, ffmpeg and a supported JavaScript runtime such
as Node 24 or Deno. Set these backend environment variables and restart:

```dotenv
YOUTUBE_DOWNLOAD_DIR=/music/youtube-requests
YOUTUBE_YTDLP=/path/to/venv/bin/yt-dlp
YOUTUBE_FFMPEG=/usr/bin/ffmpeg
YOUTUBE_JS_RUNTIME=node
```

The directory must be absolute, writable by the service user and inside the
music server's scanned library. Do not point it into a Lidarr-managed artist
directory. With systemd ProtectSystem=strict, allow this directory using
ReadWritePaths. Temporary audio stays beside the application database, outside the scanned library.

Leaving YOUTUBE_DOWNLOAD_DIR empty disables downloads and YouTube search.
The disabled tab explains that an administrator must enable it. Legacy API
clients continue using the existing Lidarr request endpoints.

## Docker

From backend/, build the normal image, then the optional extension:

```sh
docker build -t lidseeker-local -f Dockerfile .
docker build -t lidseeker-youtube --build-arg BASE_IMAGE=lidseeker-local -f Dockerfile.youtube .
```

Use `image: lidseeker-youtube` in compose, add a volume
`/host/music/youtube-requests:/music/youtube-requests`, and set
`YOUTUBE_DOWNLOAD_DIR=/music/youtube-requests`. Match the host folder's owner to
the configured container UID/GID. The optional image includes ffmpeg, yt-dlp and
Node; rebuild it periodically to keep the extractor current. The base image
and default deployment acquire no new media-tool dependencies.

## Behaviour and limits

- Authenticated endpoints: GET/POST `/api/youtube`, GET `/api/youtube/search?term=`,
  POST `/api/youtube/playlist/preview` (`url`, `limit`), POST
  `/api/youtube/playlist` (`video_ids`, at most 50). Ordinary users see only their
  jobs; admins see all. Search/preview is limited to 20 calls/minute and two
  simultaneous subprocesses.
- Only HTTPS YouTube URLs on exact known hosts are accepted. Playlist/tracking
  parameters are removed from video requests. Downloads always use one
  canonical video URL with `--no-playlist`; arbitrary executable paths,
  download arguments, filesystem paths and URLs cannot be supplied by users.
- One worker per backend process, one backend process per database. Jobs are
  durable in SQLite; interrupted jobs requeue on restart. Queue capacity is 200.
  Shutdown cancels the worker and kills its process group.
- Output is MP3 up to 192 kbps, capped at 30 minutes/100 MB per source and a
  15-minute download deadline. Live, private, account-only, DRM and otherwise
  inaccessible videos are not supported. No browser cookies or account tokens
  are collected. Provider availability may change.
- Files are atomically published as `<video-id>.mp3`; existing audio is not
  overwritten. Tags use the requested title/artist or video metadata, with
  album `Singles - Artist`. A channel name may be a label; correct the artist
  in the confirmation form when necessary. Playlist entries use video metadata.
- `downloaded` means an audio file was written, **not** that a music server has
  indexed it. Navidrome uses its normal scheduled scan. No Navidrome credentials
  are needed. YouTube history is separate from Lidarr request statistics.
- This does not create a named playlist in Navidrome, mirror removals or poll
  YouTube for later playlist changes. It adds the selected tracks to the library.
- Temporary folders beside the database, or `.youtube-*.tmp` files in the music
  folder, may need administrator cleanup after an abrupt power loss. They are
  never treated as completed imports.

Validation: `ruff check app tests`, `pytest -q`, and in web/
`npx tsc --noEmit && npm run build`. Tests mock YouTube/download tools; a real
provider test is separate and may depend on region/network access.
