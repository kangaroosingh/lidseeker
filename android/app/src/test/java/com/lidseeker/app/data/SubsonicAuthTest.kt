package com.lidseeker.app.data

import com.lidseeker.app.music.MusicConnection
import com.lidseeker.app.music.SubsonicAuth
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class SubsonicAuthTest {
    @Test fun matchesPublishedSubsonicTokenExample() {
        assertEquals("26719a1196d2a940705a59634eb18eab",SubsonicAuth.token("sesame","c19b2d"))
    }
    @Test fun preservesServerPrefixAndEscapesCredentials() {
        val connection=MusicConnection("https://example.com/music/","name & user","hashed-token","random-salt")
        val url=SubsonicAuth.url(connection,"stream",mapOf("id" to "a/b & c")).toHttpUrl()
        assertEquals("/music/rest/stream.view",url.encodedPath)
        assertEquals("name & user",url.queryParameter("u"))
        assertEquals("a/b & c",url.queryParameter("id"))
        assertNull(url.queryParameter("p"))
        assertEquals("hashed-token",url.queryParameter("t"))
    }
    @Test fun refusesCleartextAndEmbeddedCredentials() {
        for(server in listOf("http://example.com","https://user:password@example.com","https://example.com/?token=secret")) {
            try { SubsonicAuth.url(MusicConnection(server,"u","t","s"),"ping");fail("Unsafe URL accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
