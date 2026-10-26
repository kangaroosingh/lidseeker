import asyncio
import json

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest

from app import auth, config, db, youtube


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "DB_PATH", str(tmp_path / "test.db"))
    monkeypatch.setattr(config, "APP_PASS_HASH", "")
    monkeypatch.setattr(config, "YOUTUBE_DOWNLOAD_DIR", str(tmp_path / "music"))
    db.init()
    youtube.init()
    youtube._preview_limit._hits.clear()
    app = FastAPI()
    app.include_router(youtube.router)
    app.dependency_overrides[auth.require_user] = lambda: auth.CurrentUser(1, "alice", "user")
    return TestClient(app)


@pytest.mark.parametrize("url", [
    "https://youtu.be/5hG9r9NI7i0?si=tracking",
    "https://www.youtube.com/watch?v=5hG9r9NI7i0&list=PL1234567890",
    "https://music.youtube.com/watch?v=5hG9r9NI7i0",
    "https://www.youtube.com/shorts/5hG9r9NI7i0",
])
def test_canonical_video(url):
    assert youtube.video_id(url) == "5hG9r9NI7i0"


@pytest.mark.parametrize("url", [
    "http://youtu.be/5hG9r9NI7i0", "https://youtube.com.evil.test/watch?v=5hG9r9NI7i0",
    "https://127.0.0.1/watch?v=5hG9r9NI7i0", "https://user@youtube.com/watch?v=5hG9r9NI7i0",
    "https://youtube.com:8080/watch?v=5hG9r9NI7i0", "file:///etc/passwd",
    "https://youtube.com/playlist?list=PL1234567890", "https://youtu.be/../../etc/passwd",
    "https://youtube.com/watch?v=5hG9r9NI7i0&v=xxxxxxxxxxx",
])
def test_reject_non_video_urls(url):
    with pytest.raises(ValueError):
        youtube.video_id(url)


def test_opt_in_auth_ownership_and_duplicates(client, monkeypatch):
    monkeypatch.setattr(config, "YOUTUBE_DOWNLOAD_DIR", "")
    assert client.post("/api/youtube", json={"url": "https://youtu.be/5hG9r9NI7i0"}).status_code == 503
    monkeypatch.setattr(config, "YOUTUBE_DOWNLOAD_DIR", "/music")
    a = client.post("/api/youtube", json={"url": "https://youtu.be/5hG9r9NI7i0"}).json()
    b = client.post("/api/youtube", json={"url": "https://www.youtube.com/watch?v=5hG9r9NI7i0&si=x"}).json()
    assert a["id"] == b["id"]
    assert len(client.get("/api/youtube").json()["requests"]) == 1
    client.app.dependency_overrides[auth.require_user] = lambda: auth.CurrentUser(2, "bob", "user")
    assert client.get("/api/youtube").json()["requests"] == []
    client.app.dependency_overrides.clear()
    assert client.get("/api/youtube").status_code == 401
    assert client.post("/api/youtube/playlist", json={"video_ids": ["5hG9r9NI7i0"]}).status_code == 401


def test_failed_retry_and_restart_recovery(client):
    row = youtube.enqueue(1, "https://youtu.be/5hG9r9NI7i0")
    youtube.update(row["id"], status="failed", error="test")
    assert youtube.enqueue(1, "https://youtu.be/5hG9r9NI7i0")["status"] == "queued"
    youtube.update(row["id"], status="downloading")
    youtube.init()
    assert client.get("/api/youtube").json()["requests"][0]["status"] == "queued"


def test_playlist_is_bounded_atomic_and_deduplicated(client):
    ids = ["5hG9r9NI7i0", "abcdefghijk"]
    result = client.post("/api/youtube/playlist", json={"video_ids": ids + ids}).json()
    assert result == {"queued": 2, "skipped": 0}
    assert client.post("/api/youtube/playlist", json={"video_ids": ids}).json() == {"queued": 0, "skipped": 2}
    assert client.post("/api/youtube/playlist", json={"video_ids": ids * 26}).status_code == 422
    assert client.post("/api/youtube/playlist", json={"video_ids": ["--exec=bad"]}).status_code == 400
    with youtube.connection() as c:
        for i in range(197):
            c.execute("INSERT INTO youtube_requests(user_id,video_id) VALUES(?,?)", (9, str(i)))
    response = client.post("/api/youtube/playlist", json={"video_ids": ["newvideo001", "newvideo002"]})
    assert response.status_code == 429
    assert len(client.get("/api/youtube").json()["requests"]) == 2


def test_search_and_playlist_preview_do_not_download(client, monkeypatch):
    calls = []
    monkeypatch.setattr(youtube.shutil, "which", lambda _: "/bin/yt-dlp")

    async def run(args, timeout, output=None):
        calls.append(args)
        output.write_text(json.dumps({"_type": "playlist", "title": "My playlist", "entries": [
            {"id": "5hG9r9NI7i0", "title": "Pateeli Chah Di", "channel": "S Adeeb Music"},
            {"id": "5hG9r9NI7i0", "title": "duplicate"},
            {"id": "abcdefghijk", "title": "private", "availability": "private"},
            {"id": "klmnopqrstu", "title": "extra"},
        ]}))
    monkeypatch.setattr(youtube, "run_command", run)
    result = client.get("/api/youtube/search", params={"term": "Sajjan Adeeb"})
    assert result.status_code == 200
    assert result.json()[0]["video_id"] == "5hG9r9NI7i0"
    assert calls[0][-1] == "ytsearch10:Sajjan Adeeb"
    result = client.post("/api/youtube/playlist/preview", json={"url": "https://youtube.com/playlist?list=PL1234567890&si=secret", "limit": 3})
    assert result.status_code == 200
    assert len(result.json()["songs"]) == 1
    assert result.json()["truncated"] is True
    assert calls[-1][-1] == "https://www.youtube.com/playlist?list=PL1234567890"
    assert all("--skip-download" in args for args in calls)
    assert client.get("/api/youtube").json()["requests"] == []


def test_download_tags_and_publishes_atomically(client, monkeypatch, tmp_path):
    monkeypatch.setattr(youtube.shutil, "which", lambda value: "/bin/" + value)
    calls = []

    async def run(args, timeout, output=None):
        calls.append(args)
        if "--extract-audio" in args:
            target = args[args.index("--output") + 1]
            from pathlib import Path
            Path(target.replace("%(ext)s", "mp3")).write_bytes(b"ID3audio")
            Path(target.replace("%(ext)s", "info.json")).write_text(json.dumps({"title": "Video title", "uploader": "Label"}))
        else:
            from pathlib import Path
            Path(args[-1]).write_bytes(b"ID3tagged")
    monkeypatch.setattr(youtube, "run_command", run)
    row = youtube.enqueue(1, "https://youtu.be/5hG9r9NI7i0", "Pateeli Chah Di", "Sajjan Adeeb")
    asyncio.run(youtube.download(row))
    assert (tmp_path / "music/5hG9r9NI7i0.mp3").read_bytes() == b"ID3tagged"
    assert "--no-playlist" in calls[0]
    assert calls[0][-1] == "https://www.youtube.com/watch?v=5hG9r9NI7i0"
    assert "album=Singles - Sajjan Adeeb" in calls[1]
    asyncio.run(youtube.download(row))
    assert len(calls) == 2  # Existing audio is never overwritten.


def test_worker_failure_and_cancellation_do_not_report_success(client, monkeypatch):
    row = youtube.enqueue(1, "https://youtu.be/5hG9r9NI7i0")

    async def fail(_):
        raise RuntimeError("Video unavailable")
    monkeypatch.setattr(youtube, "download", fail)

    async def run():
        task = asyncio.create_task(youtube.worker())
        await asyncio.sleep(0.01)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
    asyncio.run(run())
    job = client.get("/api/youtube").json()["requests"][0]
    assert job["id"] == row["id"] and job["status"] == "failed"
    assert job["error"] == "Video unavailable"


def test_worker_cancel_during_download_requeues_without_success(client, monkeypatch):
    youtube.enqueue(1, "https://youtu.be/5hG9r9NI7i0")

    async def run():
        started = asyncio.Event()

        async def blocked(_):
            started.set()
            await asyncio.Event().wait()
        monkeypatch.setattr(youtube, "download", blocked)
        task = asyncio.create_task(youtube.worker())
        await started.wait()
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
    asyncio.run(run())
    assert client.get("/api/youtube").json()["requests"][0]["status"] == "queued"


def test_preview_rejects_unknown_hosts_and_queue_errors(client, monkeypatch):
    assert client.post("/api/youtube/playlist/preview", json={"url": "https://evil.test/playlist?list=PL1234567890"}).status_code == 400
    assert client.get("/api/youtube/search", params={"term": "https://127.0.0.1/video"}).status_code == 400

    async def unavailable(*args, **kwargs):
        raise RuntimeError("YouTube is unavailable. Try later.")
    monkeypatch.setattr(youtube, "metadata", unavailable)
    response = client.get("/api/youtube/search", params={"term": "Pateeli Chah Di"})
    assert response.status_code == 502
    assert "unavailable" in response.json()["detail"]
