import { useEffect, useState } from "react";
import { api, type YouTubeRequest, type YouTubeVideo, type YouTubePlaylist } from "../api";

export function YouTubeRequests({ historyOnly = false }: { historyOnly?: boolean }) {
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [term, setTerm] = useState("");
  const [title, setTitle] = useState("");
  const [artist, setArtist] = useState("");
  const [limit, setLimit] = useState(25);
  const [results, setResults] = useState<YouTubeVideo[]>([]);
  const [selected, setSelected] = useState<YouTubeVideo | null>(null);
  const [playlist, setPlaylist] = useState<YouTubePlaylist | null>(null);
  const [jobs, setJobs] = useState<YouTubeRequest[]>([]);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  useEffect(() => {
    let alive = true;
    const refresh = () => api.youtube().then(data => {
      if (alive) { setEnabled(data.enabled); setJobs(data.requests); }
    }).catch(() => { if (alive) setEnabled(false); });
    refresh();
    const timer = setInterval(refresh, 5000);
    return () => { alive = false; clearInterval(timer); };
  }, []);
  const search = async () => {
    setBusy(true); setMessage(""); setSelected(null); setPlaylist(null); setResults([]);
    try {
      if (/^https:\/\//i.test(term.trim()) && /[?&]list=/.test(term)) {
        setPlaylist(await api.previewYouTubePlaylist(term.trim(), limit));
      } else {
        const found = await api.searchYouTube(term.trim());
        setResults(found);
        if (!found.length) setMessage("No videos found. Try the artist and song title, or paste a direct link.");
      }
    } catch (e) { setMessage(e instanceof Error ? e.message : "YouTube search failed."); }
    finally { setBusy(false); }
  };
  const submit = async (source: string, name: string, performer: string) => {
    setBusy(true); setMessage("");
    try {
      const job = await api.requestYouTube(source, name, performer);
      setJobs(items => [job, ...items.filter(item => item.id !== job.id)]);
      setMessage(job.status === "downloaded" ? "Already downloaded. Your music server may still be scanning it." : "Request queued. You can close this page.");
      setSelected(null);
    } catch (e) { setMessage(e instanceof Error ? e.message : "Could not submit the request."); }
    finally { setBusy(false); }
  };
  const submitPlaylist = async () => {
    if (!playlist) return;
    setBusy(true); setMessage("");
    try {
      const result = await api.requestYouTubePlaylist(playlist.songs.map(song => song.video_id));
      setMessage(`${result.queued} songs queued; ${result.skipped} already requested. You can close this page.`);
      setPlaylist(null);
      const data = await api.youtube(); setJobs(data.requests);
    } catch (e) { setMessage(e instanceof Error ? e.message : "Could not request playlist."); }
    finally { setBusy(false); }
  };
  if (historyOnly && jobs.length === 0) return null;
  return <section id="youtube" className="card" style={{ marginBottom: 18 }} aria-label="YouTube requests">
    {!historyOnly && <>
      <h3>YouTube</h3>
      {enabled === false ? <p className="muted">YouTube requests are unavailable. Ask the administrator to enable them, or try again if the server is offline.</p> : <form onSubmit={e => { e.preventDefault(); search(); }}>
        <label>Search songs or paste a video / playlist link<input required maxLength={2048} placeholder="Song title and artist, or https://youtu.be/…" value={term} onChange={e => setTerm(e.target.value)} style={{ width: "100%" }} /></label>
        <label>Playlist track limit <select value={limit} onChange={e => setLimit(Number(e.target.value))}>{[5, 10, 25, 50].map(n => <option key={n} value={n}>{n}</option>)}</select></label>
        <div><button className="btn primary" disabled={busy || !term.trim() || enabled !== true}>{busy ? "Working…" : "Search / Preview"}</button></div>
      </form>}
      {results.map(video => <div key={video.video_id} style={{ padding: "12px 0", borderBottom: "1px solid var(--border)" }}>
        <strong>{video.title}</strong><div className="muted">{video.channel}{video.duration ? ` · ${Math.floor(video.duration / 60)}:${String(Math.floor(video.duration % 60)).padStart(2, "0")}` : ""}</div>
        <button className="btn ghost" disabled={busy} onClick={() => { setSelected(video); setTitle(video.title); setArtist(video.artist); setMessage(""); }}>Request</button>
      </div>)}
      {selected && <form onSubmit={e => { e.preventDefault(); submit("https://youtu.be/" + selected.video_id, title, artist); }} className="card">
        <h3>Request this song?</h3>
        <label>Song title<input maxLength={200} value={title} onChange={e => setTitle(e.target.value)} style={{ width: "100%" }} /></label>
        <label>Artist (optional)<input maxLength={200} value={artist} onChange={e => setArtist(e.target.value)} style={{ width: "100%" }} /></label>
        <p className="muted">Check the artist: a YouTube channel can be a record label. Saved as MP3 in Singles - Artist.</p>
        <button className="btn primary" disabled={busy}>Request song</button> <button type="button" className="btn ghost" onClick={() => setSelected(null)}>Cancel</button>
      </form>}
      {playlist && <div className="card">
        <h3>{playlist.title}</h3><p>{playlist.songs.length} songs in this import{playlist.truncated ? ` (limited to the first ${playlist.limit} entries)` : ""}. Already requested songs are skipped. This is a one-time import.</p>
        <ol>{playlist.songs.map(song => <li key={song.video_id}>{song.title}</li>)}</ol>
        <button className="btn primary" disabled={busy} onClick={submitPlaylist}>Request {playlist.songs.length} songs</button> <button className="btn ghost" onClick={() => setPlaylist(null)}>Cancel</button>
      </div>}
    </>}
    {message && <p role="status">{message}</p>}
    {jobs.length > 0 && <><h3>YouTube requests</h3>{jobs.slice(0, historyOnly ? 100 : 5).map(job => <div key={job.id} style={{ padding: "10px 0", borderTop: "1px solid var(--border)" }}>
      <strong>{job.title || job.video_id}</strong>{job.artist && <span> · {job.artist}</span>}
      <div className="muted">{job.status === "downloaded" ? "Downloaded · allow time for your music server’s library scan" : job.status}</div>
      {job.error && <p role="status">{job.error}</p>}
      {job.status === "failed" && enabled && <button className="btn ghost" disabled={busy} onClick={() => submit("https://youtu.be/" + job.video_id, job.title, job.artist)}>Retry</button>}
    </div>)}</>}
  </section>;
}
