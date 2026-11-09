package com.lidseeker.app.music

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class Feedback { NONE, LIKED, DISLIKED }
class SongFeedback(private val liked: LikedSongs, private val disliked: LikedSongs) {
    companion object {
        const val DISLIKED_NAME="Music — Disliked Songs (delete after 30 days)"
        private val mutex=Mutex()
        fun forServer(server: Navidrome)=SongFeedback(LikedSongs.forServer(server),LikedSongs.forServer(server,DISLIKED_NAME))
    }
    suspend fun states(): Map<String,Feedback> = mutex.withLock {
        disliked.songs().associate { it.id to Feedback.DISLIKED }+liked.songs().associate { it.id to Feedback.LIKED }
    }
    suspend fun set(id: String, state: Feedback) = mutex.withLock {
        // Add the rescue/target first. Cleanup also refuses to delete a liked song.
        when(state) {
            Feedback.LIKED -> { liked.set(id,true);disliked.set(id,false) }
            Feedback.DISLIKED -> { disliked.set(id,true);liked.set(id,false) }
            Feedback.NONE -> { disliked.set(id,false);liked.set(id,false) }
        }
        LikedSongs.revision.value=LikedSongs.revision.value+1
    }
}
