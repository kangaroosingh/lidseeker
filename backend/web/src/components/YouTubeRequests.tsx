import { useEffect, useState } from "react";
import { MediaRow } from "./MediaRow";
import { EmptyState, Spinner } from "./States";
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
  const [searched, setSearched] = useState(false);
  const isPlaylist = /^https:\/\//i.test(term.trim()) && /[?&]list=/.test(term);
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
    setSearched(true); setBusy(true); setMessage(""); setSelected(null); setPlaylist(null); setResults([]);
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
  return <section id="youtube" className="youtube-section" aria-label="YouTube requests">
    {!historyOnly && <>
      {enabled === false ? <EmptyState icon="☁️" title="YouTube unavailable" message="Try again if the server is offline, or ask the administrator to enable YouTube requests." /> : <>
        <form className="searchbar" onSubmit={e => { e.preventDefault(); search(); }}>
          <input aria-label="Search YouTube" required maxLength={2048} placeholder="Search songs or paste a YouTube link…" value={term} onChange={e => setTerm(e.target.value)} />
          <button className="btn primary" disabled={busy || !term.trim() || enabled !== true}>{busy ? "Searching…" : isPlaylist ? "Preview" : "Search"}</button>
        </form>
        {isPlaylist && <div className="youtube-options">
          <label htmlFor="youtube-limit">Playlist track limit</label>
          <select id="youtube-limit" className="input" value={limit} onChange={e => setLimit(Number(e.target.value))}>{[5, 10, 25, 50].map(n => <option key={n} value={n}>{n}</option>)}</select>
          <span className="muted">Preview the songs before requesting.</span>
        </div>}
        {busy && !selected && <Spinner />}
        {!searched && !busy && <EmptyState icon="🔎" title="Search for music" message="Find songs on YouTube, or paste a video or playlist link to request." />}
      </>}
      {results.length > 0 && <div className="card youtube-results">{results.map(video => <MediaRow key={video.video_id}
        title={video.title}
        subtitle={[video.channel, video.duration ? `${Math.floor(video.duration / 60)}:${String(Math.floor(video.duration % 60)).padStart(2, "0")}` : ""].filter(Boolean).join(" · ")}
        trailing={<button className="btn primary small" disabled={busy} onClick={() => { setSelected(video); setTitle(video.title); setArtist(video.artist); setMessage(""); }}>Request</button>}
      />)}</div>}
      {selected && <div className="backdrop" onClick={() => !busy && setSelected(null)}>
        <form className="dialog" role="dialog" aria-modal="true" aria-labelledby="youtube-confirm-title" onClick={e => e.stopPropagation()} onSubmit={e => { e.preventDefault(); submit("https://youtu.be/" + selected.video_id, title, artist); }}>
          <h3 id="youtube-confirm-title">Request this song?</h3>
          <div className="field"><label htmlFor="youtube-title">Song title</label><input id="youtube-title" className="input" autoFocus maxLength={200} value={title} onChange={e => setTitle(e.target.value)} /></div>
          <div className="field"><label htmlFor="youtube-artist">Artist (optional)</label><input id="youtube-artist" className="input" maxLength={200} value={artist} onChange={e => setArtist(e.target.value)} /></div>
          <p className="muted">Check the artist: a YouTube channel can be a record label. Singles are grouped by artist in your library.</p>
          <div className="actions"><button type="button" className="btn ghost" disabled={busy} onClick={() => setSelected(null)}>Cancel</button><button className="btn primary" disabled={busy}>{busy ? "Requesting…" : "Request song"}</button></div>
        </form>
      </div>}
      {playlist && <div className="card youtube-playlist">
        <div className="youtube-panel-heading"><h3>{playlist.title}</h3><p className="muted">{playlist.songs.length} songs{playlist.truncated ? ` · first ${playlist.limit} entries` : ""}. Already requested songs are skipped. This is a one-time import.</p></div>
        <ol>{playlist.songs.map(song => <li key={song.video_id}>{song.title}</li>)}</ol>
        <div className="youtube-panel-actions"><button className="btn ghost" disabled={busy} onClick={() => setPlaylist(null)}>Cancel</button><button className="btn primary" disabled={busy} onClick={submitPlaylist}>Request {playlist.songs.length} songs</button></div>
      </div>}
    </>}
    {message && <p className="youtube-message" role="status">{message}</p>}
    {jobs.length > 0 && <div className="youtube-history"><h3>{historyOnly ? "YouTube requests" : "Recent YouTube requests"}</h3><div className="card youtube-results">{jobs.slice(0, historyOnly ? 100 : 5).map(job => <MediaRow key={job.id}
      title={job.title || job.video_id} subtitle={job.artist || "YouTube"}
      trailing={<div className="youtube-job-status"><span className={`statuschip ${job.status === "downloaded" ? "available" : job.status === "queued" ? "pending" : job.status}`}>{job.status === "downloaded" ? "Downloaded" : job.status === "downloading" ? "Downloading" : job.status === "failed" ? "Failed" : "Queued"}</span>{job.status === "failed" && enabled && <button className="btn ghost small" disabled={busy} onClick={() => submit("https://youtu.be/" + job.video_id, job.title, job.artist)}>Retry</button>}{job.error && <span className="youtube-job-error" role="status">{job.error}</span>}</div>}
    />)}</div></div>}
  </section>;
}
