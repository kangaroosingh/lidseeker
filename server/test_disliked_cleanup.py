import contextlib
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest
from disliked_cleanup import run, RETENTION, LIKED, DISLIKED


class CleanupTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.base = Path(self.tmp.name)
        self.root = self.base / 'music'
        self.root.mkdir()
        self.song = self.root / 'song.mp3'
        self.song.write_bytes(b'test music')
        self.database = self.base / 'navidrome.db'
        self.db = sqlite3.connect(self.database)
        self.db.executescript('''CREATE TABLE user(id TEXT, user_name TEXT);
        CREATE TABLE playlist(id TEXT,owner_id TEXT,name TEXT,updated_at TEXT);
        CREATE TABLE library(id INTEGER,path TEXT);
        CREATE TABLE media_file(id TEXT,path TEXT,library_id INTEGER,missing INTEGER);
        CREATE TABLE playlist_tracks(playlist_id TEXT,media_file_id TEXT);''')
        self.db.execute('INSERT INTO user VALUES (?,?)', ('u', 'Harsh'))
        self.db.execute('INSERT INTO library VALUES (?,?)', (1, str(self.root)))
        self.db.execute('INSERT INTO media_file VALUES (?,?,1,0)', ('song', 'song.mp3'))
        self.db.execute('INSERT INTO playlist VALUES (?,?,?,?)', ('d', 'u', DISLIKED, 'initial'))
        self.db.execute('INSERT INTO playlist_tracks VALUES (?,?)', ('d', 'song'))
        self.db.commit()
        self.config = dict(database=str(self.database), state=str(self.base / 'state.json'), username='Harsh', music_root=str(self.root))

    def tearDown(self):
        self.db.close()
        self.tmp.cleanup()

    def check(self, now=100, apply=True):
        with contextlib.redirect_stdout(io.StringIO()):
            return run(self.config, apply, now)

    def test_full_retention_then_only_music_file_deleted(self):
        other = self.root / 'other.mp3'
        other.write_bytes(b'keep')
        self.check()
        self.check(100+RETENTION-1)
        self.assertTrue(self.song.exists())
        self.assertEqual(1, self.check(100+RETENTION)['deleted'])
        self.assertFalse(self.song.exists())
        self.assertTrue(other.exists())
        self.assertEqual(1, self.db.execute('SELECT count(*) FROM media_file').fetchone()[0])

    def test_like_rescues_shared_file(self):
        self.check()
        self.db.execute('INSERT INTO playlist VALUES (?,?,?,?)', ('l','another-user',LIKED,'new'))
        self.db.execute('INSERT INTO playlist_tracks VALUES (?,?)', ('l','song'))
        self.db.commit()
        self.assertEqual(0,self.check(100+RETENTION)['deleted'])
        self.assertTrue(self.song.exists())
        self.assertEqual({},json.loads(Path(self.config['state']).read_text())['pending'])

    def test_remove_dislike_cancels(self):
        self.check()
        self.db.execute('DELETE FROM playlist_tracks');self.db.commit()
        self.assertEqual(0,self.check(100+RETENTION)['pending'])
        self.assertTrue(self.song.exists())

    def test_playlist_edit_restarts_countdown(self):
        self.check()
        self.db.execute("UPDATE playlist SET updated_at='edited'");self.db.commit()
        self.assertEqual(0,self.check(100+RETENTION)['deleted'])
        self.assertTrue(self.song.exists())

    def test_replacement_file_restarts_countdown(self):
        self.check()
        self.song.write_bytes(b'a replacement file')
        self.assertEqual(0,self.check(100+RETENTION)['deleted'])
        self.assertTrue(self.song.exists())

    def test_symlink_and_traversal_refused(self):
        self.song.unlink()
        outside = self.base / 'outside.mp3';outside.write_bytes(b'keep')
        self.song.symlink_to(outside)
        self.assertEqual(1,self.check()['refused'])
        self.assertTrue(outside.exists())
        self.db.execute("UPDATE media_file SET path='../outside.mp3'");self.db.commit()
        self.assertEqual(1,self.check()['refused'])
        self.assertTrue(outside.exists())

    def test_wrong_owner_ignored(self):
        self.db.execute("UPDATE playlist SET owner_id='different'");self.db.commit()
        self.assertEqual(0,self.check()['pending'])

    def test_dry_run_never_deletes_or_changes_state(self):
        self.check()
        before=Path(self.config['state']).read_bytes()
        self.check(100+RETENTION,False)
        self.assertTrue(self.song.exists())
        self.assertEqual(before,Path(self.config['state']).read_bytes())


if __name__ == '__main__':
    unittest.main()
