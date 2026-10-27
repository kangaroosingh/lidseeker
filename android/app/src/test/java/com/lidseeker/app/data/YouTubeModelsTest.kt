package com.lidseeker.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class YouTubeModelsTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun decodesBackendSearchAndHistory() {
        val videos = json.decodeFromString<List<YouTubeVideo>>("""[{"video_id":"5hG9r9NI7i0","title":"Pateeli Chah Di","duration":229,"channel":"S Adeeb Music","artist":"","extra":true}]""")
        assertEquals(229.0, videos.single().duration!!, 0.0)
        val history = json.decodeFromString<YouTubeHistory>("""{"enabled":true,"requests":[{"id":1,"video_id":"5hG9r9NI7i0","status":"downloaded","error":null,"user_id":1}]}""")
        assertEquals("downloaded", history.requests.single().status)
    }
    @Test fun submitsExactlyPreviewedIds() {
        val preview = json.decodeFromString<YouTubePlaylist>("""{"title":"Playlist","songs":[{"video_id":"5hG9r9NI7i0","title":"Song"}],"truncated":true,"limit":5,"url":"https://youtube.com/playlist"}""")
        assertEquals("""{"video_ids":["5hG9r9NI7i0"]}""", json.encodeToString(YouTubePlaylistBody(preview.songs.map { it.video_id })))
    }
}
