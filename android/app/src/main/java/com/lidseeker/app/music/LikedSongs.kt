package com.lidseeker.app.music

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The server playlist is authoritative. Never rewrite its full contents. */
interface LikesApi {
    suspend fun playlists(): List<LibraryItem>
    suspend fun songs(playlist: LibraryItem): List<LibraryItem>
    suspend fun create(name: String): LibraryItem
    suspend fun add(playlist: String, song: String)
    suspend fun remove(playlist: String, index: Int)
}

class LikedSongs(private val api: LikesApi, private val username: String, private val name: String = NAME) {
    companion object {
        const val NAME = "Music — Liked Songs"
        // Serialize read-modify-write operations across phone and car in this process.
        private val mutex = Mutex()
        val revision = MutableStateFlow(0L)
        fun forServer(server: Navidrome, name: String = NAME) = LikedSongs(object : LikesApi {
            override suspend fun playlists() = server.playlists()
            override suspend fun songs(playlist: LibraryItem) = server.songs(playlist)
            override suspend fun create(name: String) = server.createFeedbackPlaylist(name)
            override suspend fun add(playlist: String, song: String) = server.addToPlaylist(playlist,song)
            override suspend fun remove(playlist: String, index: Int) = server.removeFromPlaylist(playlist,index)
        },server.connection?.username ?: error("Connect Navidrome first."),name)
    }
    private suspend fun find(): LibraryItem? {
        val matches=api.playlists().filter { it.title==name && it.owner==username }
        check(matches.size<=1) { "More than one $name playlist exists. Rename the extra playlist in Navidrome first." }
        return matches.singleOrNull()
    }
    suspend fun songs(): List<LibraryItem> = mutex.withLock { find()?.let { api.songs(it) }.orEmpty() }
    suspend fun set(song: String, liked: Boolean): List<LibraryItem> = mutex.withLock {
        require(song.isNotBlank())
        val playlist=find() ?: if(liked) api.create(name) else return@withLock emptyList()
        val before=api.songs(playlist)
        if(liked && before.none { it.id==song }) api.add(playlist.id,song)
        // Descending indices preserve the positions of every unrelated entry.
        if(!liked) before.indices.filter { before[it].id==song }.reversed().forEach { api.remove(playlist.id,it) }
        val after=api.songs(playlist)
        check(after.any { it.id==song }==liked) { "Could not verify the playlist update. Refresh and try again." }
        revision.value=revision.value+1
        after
    }
}
