package com.lidseeker.app.music

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

object CarLibrary {
    const val ROOT="music:root"
    const val RECENT="music:recent"
    const val ARTISTS="music:artists"
    const val PLAYLISTS="music:playlists"
    const val LIKED="music:liked"
    fun folder(id: String, title: String) = MediaItem.Builder().setMediaId(id).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build()
    fun roots() = listOf(folder(LIKED,"Liked Songs"),folder(RECENT,"Recently added"),folder(ARTISTS,"Artists"),folder(PLAYLISTS,"Playlists"))
    fun id(item: LibraryItem)="${item.kind}:${item.id}"
    fun parse(id: String): Pair<String,String> {
        val parts=id.split(':',limit=2)
        require(parts.size==2 && parts[0] in setOf("song","album","artist","playlist") && parts[1].isNotBlank()) { "Unknown library item." }
        return parts[0] to parts[1]
    }
    fun media(item: LibraryItem, server: Navidrome, playableUri: Boolean=false): MediaItem {
        val song=item.kind=="song"
        val builder=MediaItem.Builder().setMediaId(id(item)).setMediaMetadata(MediaMetadata.Builder()
            .setTitle(item.title).setArtist(item.artist).setIsBrowsable(!song).setIsPlayable(song)
            .setMediaType(if(song) MediaMetadata.MEDIA_TYPE_MUSIC else MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
            .setArtworkUri(if(item.cover.isBlank()) null else Uri.parse(server.url("getCoverArt",mapOf("id" to item.cover,"size" to "400"))))
            .build())
        if(song && playableUri) builder.setUri(server.url("stream",mapOf("id" to item.id)))
        return builder.build()
    }
    fun <T> page(items: List<T>, page: Int, size: Int): List<T> {
        require(page>=0 && size>0)
        val start=page.toLong()*size
        if(start>=items.size) return emptyList()
        return items.subList(start.toInt(),minOf(items.size.toLong(),start+size).toInt())
    }
}
