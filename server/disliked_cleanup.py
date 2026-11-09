#!/usr/bin/env python3
"""Delete continuously disliked music after 30 days. Navidrome DB is read-only.

A changed playlist resets all its pending clocks, so an unobserved remove/re-add
can never inherit an earlier deletion deadline. Only configured owner is allowed.
"""
import argparse
import fcntl
import json
import os
from pathlib import Path
import sqlite3
import stat
import time

LIKED = 'Music — Liked Songs'
DISLIKED = 'Music — Disliked Songs (delete after 30 days)'
RETENTION = 30 * 24 * 60 * 60
EXTENSIONS = {'.mp3', '.m4a', '.flac', '.ogg', '.opus', '.wav', '.aac', '.wma', '.aiff', '.alac'}


def snapshot(db, username):
    owner = db.execute('SELECT id FROM user WHERE user_name=?', (username,)).fetchone()
    if not owner:
        raise RuntimeError('Configured user not found; no deletions performed')
    playlists = db.execute('SELECT id,updated_at FROM playlist WHERE owner_id=? AND name=?', (owner[0], DISLIKED)).fetchall()
    if len(playlists) > 1:
        raise RuntimeError('Ambiguous Disliked Songs playlists; no deletions performed')
    if not playlists:
        return {}, ''
    pid, updated = playlists[0]
    # Any user's explicit Music like protects the shared file.
    liked = {r[0] for r in db.execute('SELECT pt.media_file_id FROM playlist_tracks pt JOIN playlist p ON p.id=pt.playlist_id WHERE p.name=?', (LIKED,))}
    rows = db.execute('''SELECT m.id,m.path,l.path FROM playlist_tracks pt
        JOIN media_file m ON m.id=pt.media_file_id JOIN library l ON l.id=m.library_id
        WHERE pt.playlist_id=? AND m.missing=0''', (pid,)).fetchall()
    return {r[0]: (r[1], r[2]) for r in rows if r[0] not in liked}, f'{pid}:{updated}'


def fingerprint(root, relative, allowed_root):
    root = Path(root).resolve(strict=True)
    allowed_root = Path(allowed_root).resolve(strict=True)
    if root != allowed_root:
        raise ValueError('Library root is not explicitly allowed')
    rel = Path(relative)
    if rel.is_absolute() or '..' in rel.parts:
        raise ValueError('Unsafe media path')
    path = root / rel
    # Refuse symlinks in every component, even ones pointing back into the root.
    cursor = root
    for part in rel.parts:
        cursor = cursor / part
        if cursor.is_symlink():
            raise ValueError('Symlink media path')
    resolved = path.resolve(strict=True)
    if not resolved.is_relative_to(root) or resolved == root or resolved.suffix.lower() not in EXTENSIONS:
        raise ValueError('Not an allowed music file within the library')
    info = resolved.stat()
    if not stat.S_ISREG(info.st_mode):
        raise ValueError('Not a regular file')
    return resolved, [info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns]


def run(config, apply=False, now=None):
    now = time.time() if now is None else now
    state_path = Path(config['state'])
    state_path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with open(str(state_path)+'.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        state = json.loads(state_path.read_text()) if state_path.exists() else {'version': 1, 'pending': {}, 'stamp': ''}
        if state.get('version') != 1:
            raise RuntimeError('Unknown state version')
        db = sqlite3.connect('file:'+config['database']+'?mode=ro', uri=True)
        try:
            active, stamp = snapshot(db, config['username'])
            # Fail closed if an edit could have removed and re-added a song.
            previous = state['pending'] if state['stamp'] == stamp else {}
            pending, deleted, refused = {}, [], 0
            for song, (relative, root) in active.items():
                try:
                    path, identity = fingerprint(root, relative, config['music_root'])
                except (OSError, ValueError):
                    refused += 1
                    continue
                old = previous.get(song)
                entry = old if old and old['identity'] == identity and old['path'] == str(path) else {'since': now, 'identity': identity, 'path': str(path)}
                pending[song] = entry
                if now - entry['since'] < RETENTION:
                    continue
                # Re-read current membership just before deleting; never modify the live DB.
                current, current_stamp = snapshot(db, config['username'])
                if current_stamp != stamp or current.get(song) != (relative, root):
                    pending[song] = dict(entry, since=now)
                    continue
                checked, identity_now = fingerprint(root, relative, config['music_root'])
                if identity_now != identity:
                    pending[song] = dict(entry, since=now, identity=identity_now)
                    continue
                if apply:
                    checked.unlink()
                    deleted.append(song)
                    del pending[song]
            if apply:
                payload = {'version': 1, 'stamp': stamp, 'pending': pending, 'checked_at': now}
                temp = state_path.with_suffix('.tmp')
                temp.write_text(json.dumps(payload, ensure_ascii=False))
                temp.chmod(0o600)
                os.replace(temp, state_path)
            result = {'pending': len(pending), 'deleted': len(deleted), 'refused': refused, 'apply': apply}
            print(json.dumps(result))
            return result
        finally:
            db.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True)
    parser.add_argument('--apply', action='store_true', help='Enable deletion and persist countdowns. Default is dry run.')
    args = parser.parse_args()
    run(json.loads(Path(args.config).read_text()), args.apply)
