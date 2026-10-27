package com.lidseeker.app.data

import kotlinx.serialization.Serializable

@Serializable data class YouTubeVideo(val video_id: String, val title: String, val channel: String = "", val artist: String = "", val duration: Double? = null)
@Serializable data class YouTubeJob(val id: Int, val video_id: String, val title: String = "", val artist: String = "", val status: String, val error: String? = null)
@Serializable data class YouTubeHistory(val enabled: Boolean, val requests: List<YouTubeJob>)
@Serializable data class YouTubeLink(val url: String, val title: String = "", val artist: String = "")
@Serializable data class YouTubePreviewBody(val url: String, val limit: Int = 25)
@Serializable data class YouTubeSong(val video_id: String, val title: String)
@Serializable data class YouTubePlaylist(val title: String, val songs: List<YouTubeSong>, val truncated: Boolean = false, val limit: Int = 25)
@Serializable data class YouTubePlaylistBody(val video_ids: List<String>)
@Serializable data class YouTubeQueued(val queued: Int, val skipped: Int)
