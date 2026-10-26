"""Opt-in, authenticated single-video requests; no arbitrary URLs or shell commands."""
import asyncio
import contextlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import sqlite3
import tempfile
from urllib.parse import parse_qs, urlsplit

from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel, Field

from . import auth, config
from .ratelimit import SlidingWindowLimiter

router = APIRouter(prefix="/api/youtube", tags=["YouTube requests"])
_wake = asyncio.Event()
_preview_slots = asyncio.Semaphore(2)
_preview_limit = SlidingWindowLimiter(20, 60)


def video_id(url: str) -> str:
    """Normalize only known HTTPS YouTube video forms, dropping tracking/playlist params."""
    try:
        p = urlsplit(url.strip())
        if p.scheme != "https" or p.username or p.password or p.port not in (None, 443):
            raise ValueError
        if p.hostname == "youtu.be":
            candidate = p.path.removeprefix("/")
        elif p.hostname in ("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com"):
            if p.path == "/watch":
                values = parse_qs(p.query).get("v", [])
                candidate = values[0] if len(values) == 1 else ""
            else:
                parts = p.path.split("/")
                candidate = parts[2] if len(parts) == 3 and parts[1] in ("shorts", "live") else ""
        else:
            raise ValueError
        if not re.fullmatch(r"[A-Za-z0-9_-]{11}", candidate):
            raise ValueError
        return candidate
    except (ValueError, IndexError):
        raise ValueError("Paste an HTTPS YouTube video link, not a playlist or another website.") from None


@contextlib.contextmanager
def connection():
    c = sqlite3.connect(config.DB_PATH, timeout=10)
    c.row_factory = sqlite3.Row
    try:
        yield c
        c.commit()
    finally:
        c.close()


def init():
    global _wake, _preview_slots
    _wake = asyncio.Event()
    _preview_slots = asyncio.Semaphore(2)
    with connection() as c:
        c.execute("""CREATE TABLE IF NOT EXISTS youtube_requests (
            id INTEGER PRIMARY KEY, user_id INTEGER NOT NULL, video_id TEXT NOT NULL,
            title TEXT NOT NULL DEFAULT '', artist TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT 'queued', error TEXT,
            created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
            UNIQUE(user_id, video_id))""")
        # systemd/container shutdown kills children; interrupted jobs can safely resume.
        c.execute("UPDATE youtube_requests SET status='queued' WHERE status='downloading'")


def configured() -> bool:
    return bool(config.YOUTUBE_DOWNLOAD_DIR)


def enqueue(user_id: int, source: str, title: str = "", artist: str = "") -> dict:
    vid = video_id(source)
    with connection() as c:
        c.execute("BEGIN IMMEDIATE")
        row = c.execute("SELECT * FROM youtube_requests WHERE user_id=? AND video_id=?", (user_id, vid)).fetchone()
        if row and row["status"] != "failed":
            return dict(row)
        if c.execute("SELECT COUNT(*) FROM youtube_requests WHERE status IN ('queued','downloading')").fetchone()[0] >= 200:
            raise HTTPException(429, "The download queue is full. Try again later.")
        c.execute("""INSERT INTO youtube_requests(user_id,video_id,title,artist) VALUES(?,?,?,?)
            ON CONFLICT(user_id,video_id) DO UPDATE SET status='queued',error=NULL,title=excluded.title,artist=excluded.artist""",
                  (user_id, vid, title.strip(), artist.strip()))
        row = dict(c.execute("SELECT * FROM youtube_requests WHERE user_id=? AND video_id=?", (user_id, vid)).fetchone())
    _wake.set()
    return row


def update(job_id: int, **values):
    # Keys are internal constants, never request data.
    with connection() as c:
        c.execute("UPDATE youtube_requests SET " + ",".join(k + "=?" for k in values) + " WHERE id=?", [*values.values(), job_id])


class LinkRequest(BaseModel):
    url: str = Field(min_length=1, max_length=2048)
    title: str = Field(default="", max_length=200, pattern=r"^[^\x00-\x1f]*$")
    artist: str = Field(default="", max_length=200, pattern=r"^[^\x00-\x1f]*$")


class PlaylistRequest(BaseModel):
    url: str = Field(min_length=1, max_length=2048)
    limit: int = Field(default=25, ge=1, le=50)


def playlist_url(url: str) -> str:
    try:
        p = urlsplit(url.strip())
        valid_path = p.path in ("/playlist", "/watch") or (p.hostname == "youtu.be" and re.fullmatch(r"/[A-Za-z0-9_-]{11}", p.path))
        if (p.scheme != "https" or p.hostname not in ("youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com", "youtu.be")
                or p.username or p.password or p.port not in (None, 443) or not valid_path):
            raise ValueError
        values = parse_qs(p.query).get("list", [])
        if len(values) != 1 or not re.fullmatch(r"[A-Za-z0-9_-]{10,100}", values[0]):
            raise ValueError
        return "https://www.youtube.com/playlist?list=" + values[0]
    except ValueError:
        raise ValueError("Paste an HTTPS YouTube playlist link containing list=.") from None


async def preview_playlist(body: PlaylistRequest) -> dict:
    url = playlist_url(body.url)
    info = await metadata(url, body.limit + 1)
    songs, seen = [], set()
    entries = info.get("entries") or []
    for entry in entries[:body.limit]:
        vid = entry.get("id", "") if entry else ""
        if not re.fullmatch(r"[A-Za-z0-9_-]{11}", vid) or vid in seen:
            continue
        if entry.get("availability") in ("private", "needs_auth", "subscriber_only", "premium_only"):
            continue
        seen.add(vid)
        songs.append({"video_id": vid, "title": entry.get("title") or vid})
    if not songs:
        raise RuntimeError("No public videos found. Private playlists and sign-in-only videos are not supported.")
    return {"title": info.get("title") or "YouTube playlist", "url": url, "songs": songs,
            "truncated": len(entries) > body.limit, "limit": body.limit}


class PlaylistSubmit(BaseModel):
    # Submit exactly the IDs shown by preview, avoiding playlist changes between preview and confirmation.
    video_ids: list[str] = Field(min_length=1, max_length=50)


@router.post("/playlist/preview", dependencies=[Depends(_preview_limit)])
async def playlist_preview(body: PlaylistRequest, user: auth.CurrentUser = Depends(auth.require_user)):
    if not configured():
        raise HTTPException(503, "YouTube requests have not been enabled by the administrator.")
    try:
        return await preview_playlist(body)
    except ValueError as e:
        raise HTTPException(400, str(e)) from None
    except RuntimeError as e:
        raise HTTPException(502, str(e)) from None


async def metadata(source: str, count: int) -> dict:
    downloader = shutil.which(config.YOUTUBE_YTDLP)
    if not downloader:
        raise RuntimeError("The administrator must install yt-dlp.")
    if _preview_slots.locked():
        raise HTTPException(429, "YouTube search is busy. Try again in a moment.")
    async with _preview_slots:
        with tempfile.TemporaryDirectory(prefix="lidseeker-preview-") as staging:
            path = Path(staging) / "metadata.json"
            try:
                await run_command([downloader, "--ignore-config", *runtime_args(), "--flat-playlist", "--skip-download",
                                   "--socket-timeout", "15", "--retries", "1", "--playlist-end", str(count),
                                   "--dump-single-json", "--", source], 60, output=path)
                if path.stat().st_size > 8 * 1024 * 1024:
                    raise RuntimeError("YouTube metadata is too large. Try a smaller request.")
                return json.loads(path.read_text(encoding="utf-8"))
            except (TimeoutError, json.JSONDecodeError):
                raise RuntimeError("YouTube search timed out or returned invalid data. Try again later.") from None


@router.get("/search", dependencies=[Depends(_preview_limit)])
async def search_youtube(term: str = Query(min_length=1, max_length=200),
                         user: auth.CurrentUser = Depends(auth.require_user)):
    if not configured():
        raise HTTPException(503, "YouTube requests have not been enabled by the administrator.")
    term = term.strip()
    if not term or any(ord(c) < 32 for c in term):
        raise HTTPException(400, "Enter a song or artist name.")
    try:
        source = "https://www.youtube.com/watch?v=" + video_id(term) if "://" in term else "ytsearch10:" + term
        info = await metadata(source, 10)
        entries = info.get("entries") if info.get("_type") == "playlist" else [info]
        results, seen = [], set()
        for entry in entries or []:
            if not entry:
                continue
            vid = entry.get("id", "")
            if not re.fullmatch(r"[A-Za-z0-9_-]{11}", vid) or vid in seen:
                continue
            if entry.get("is_live") or (entry.get("duration") or 0) > 1800:
                continue
            seen.add(vid)
            results.append({"video_id": vid, "title": entry.get("title") or vid,
                            "channel": entry.get("channel") or entry.get("uploader") or "",
                            "artist": entry.get("artist") or "", "duration": entry.get("duration")})
        return results
    except ValueError as e:
        raise HTTPException(400, str(e)) from None
    except RuntimeError as e:
        raise HTTPException(502, str(e)) from None


@router.post("/playlist", status_code=202)
def request_playlist(body: PlaylistSubmit, user: auth.CurrentUser = Depends(auth.require_user)):
    if not configured():
        raise HTTPException(503, "YouTube requests have not been enabled by the administrator.")
    ids = list(dict.fromkeys(body.video_ids))
    if any(not re.fullmatch(r"[A-Za-z0-9_-]{11}", vid) for vid in ids):
        raise HTTPException(400, "Invalid video ID in playlist.")
    # One transaction: queue-capacity failure must not partially submit a playlist.
    with connection() as c:
        c.execute("BEGIN IMMEDIATE")
        existing = {r["video_id"]: r["status"] for r in c.execute("SELECT video_id,status FROM youtube_requests WHERE user_id=?", (user.id,))}
        new = [vid for vid in ids if vid not in existing or existing[vid] == "failed"]
        pending = c.execute("SELECT COUNT(*) FROM youtube_requests WHERE status IN ('queued','downloading')").fetchone()[0]
        if pending + len(new) > 200:
            raise HTTPException(429, "The queue cannot fit this playlist. Try fewer songs or wait.")
        for vid in new:
            c.execute("""INSERT INTO youtube_requests(user_id,video_id) VALUES(?,?)
                ON CONFLICT(user_id,video_id) DO UPDATE SET status='queued',error=NULL""", (user.id, vid))
    _wake.set()
    return {"queued": len(new), "skipped": len(ids) - len(new)}


@router.get("")
def list_jobs(user: auth.CurrentUser = Depends(auth.require_user)):
    with connection() as c:
        rows = c.execute("SELECT * FROM youtube_requests ORDER BY id DESC LIMIT 100") if user.is_admin else c.execute(
            "SELECT * FROM youtube_requests WHERE user_id=? ORDER BY id DESC LIMIT 100", (user.id,))
        return {"enabled": configured(), "requests": [dict(r) for r in rows]}


@router.post("", status_code=202)
def request_link(body: LinkRequest, user: auth.CurrentUser = Depends(auth.require_user)):
    if not configured():
        raise HTTPException(503, "YouTube requests have not been enabled by the administrator.")
    try:
        return enqueue(user.id, body.url, body.title, body.artist)
    except ValueError as e:
        raise HTTPException(400, str(e)) from None


async def run_command(args: list[str], timeout: int, output: Path | None = None):
    # Drop process output: it may contain signed CDN URLs, cookies or proxy credentials.
    handle = output.open("wb") if output else None
    try:
        proc = await asyncio.create_subprocess_exec(*args, stdout=handle or asyncio.subprocess.DEVNULL,
                                                stderr=asyncio.subprocess.DEVNULL,
                                                start_new_session=os.name == "posix")
    finally:
        if handle:
            handle.close()
    try:
        code = await asyncio.wait_for(proc.wait(), timeout)
        if code:
            raise RuntimeError("Download failed. The video may be unavailable, restricted, or require a downloader update.")
    except BaseException:
        if proc.returncode is None:
            if os.name == "posix":
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(proc.pid, signal.SIGKILL)
            else:
                proc.kill()
            await proc.wait()
        raise


def runtime_args() -> list[str]:
    return ["--js-runtimes", config.YOUTUBE_JS_RUNTIME] if config.YOUTUBE_JS_RUNTIME else []


async def download(job: dict):
    root = Path(config.YOUTUBE_DOWNLOAD_DIR)
    if not root.is_absolute():
        raise RuntimeError("The administrator must configure an absolute music directory.")
    root.mkdir(parents=True, exist_ok=True)
    final = root / (job["video_id"] + ".mp3")
    if final.is_file() and final.stat().st_size:
        return  # Do not overwrite an existing import, including on restart/retry.
    downloader = shutil.which(config.YOUTUBE_YTDLP)
    ffmpeg = shutil.which(config.YOUTUBE_FFMPEG)
    if not downloader or not ffmpeg:
        raise RuntimeError("The administrator must install yt-dlp and ffmpeg.")
    # Stage outside the scanned library. Publish on the destination filesystem
    # through an unsupported .tmp extension, so scanners never see partial MP3s.
    with tempfile.TemporaryDirectory(prefix="youtube-", dir=Path(config.DB_PATH).parent) as staging:
        path = Path(staging)
        await run_command([
            downloader, "--ignore-config", *runtime_args(), "--no-playlist", "--no-progress", "--no-warnings",
            "--socket-timeout", "30", "--retries", "2", "--max-filesize", "100M",
            "--match-filter", "!is_live & duration <= 1800", "--format", "bestaudio[ext=m4a]/bestaudio",
            "--extract-audio", "--audio-format", "mp3", "--audio-quality", "192K",
            "--ffmpeg-location", str(Path(ffmpeg).parent), "--write-info-json",
            "--output", str(path / "source.%(ext)s"),
            "--", "https://www.youtube.com/watch?v=" + job["video_id"],
        ], 900)
        source = path / "source.mp3"
        if not source.is_file() or source.stat().st_size < 1 or source.stat().st_size > 100 * 1024 * 1024:
            raise RuntimeError("No audio was downloaded. Live videos and videos over 30 minutes or 100 MB are excluded.")
        info = json.loads((path / "source.info.json").read_text())
        artist = str(job["artist"] or info.get("artist") or info.get("uploader") or "Unknown artist").replace("\x00", "")[:200]
        title = str(job["title"] or info.get("track") or info.get("title") or job["video_id"]).replace("\x00", "")[:200]
        tagged = path / "tagged.mp3"
        await run_command([ffmpeg, "-nostdin", "-v", "error", "-i", str(source), "-map", "0:a:0", "-c", "copy",
                           "-metadata", "title=" + title, "-metadata", "artist=" + artist,
                           "-metadata", "album_artist=" + artist, "-metadata", "album=Singles - " + artist,
                           str(tagged)], 60)
        pending = root / (".youtube-" + str(job["id"]) + ".tmp")
        try:
            await asyncio.to_thread(shutil.copyfile, tagged, pending)
            os.replace(pending, final)
        finally:
            with contextlib.suppress(OSError):
                pending.unlink(missing_ok=True)
        update(job["id"], title=title, artist=artist)


async def worker():
    while True:
        _wake.clear()
        with connection() as c:
            row = c.execute("SELECT * FROM youtube_requests WHERE status='queued' ORDER BY id LIMIT 1").fetchone()
        if not row:
            await _wake.wait()
            continue
        job = dict(row)
        update(job["id"], status="downloading", error=None)
        try:
            await download(job)
            # A successful file import is not proof that Navidrome has scanned it yet.
            update(job["id"], status="downloaded")
        except asyncio.CancelledError:
            update(job["id"], status="queued")
            raise
        except Exception as e:
            message = str(e) if isinstance(e, RuntimeError) else "Import failed. Check the server configuration and free space."
            update(job["id"], status="failed", error=message)
