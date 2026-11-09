package com.lidseeker.app.music

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class MusicConnection(val server: String, val username: String, val token: String, val salt: String)
data class LibraryItem(val id: String, val title: String, val artist: String = "", val cover: String = "", val kind: String = "song", val duration: Int = 0)

object SubsonicAuth {
    fun token(password: String, salt: String): String = MessageDigest.getInstance("MD5")
        .digest((password + salt).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun url(connection: MusicConnection, endpoint: String, params: Map<String, String> = emptyMap()): String {
        val base = connection.server.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Enter a valid server address.")
        require(base.isHttps) { "Use your server's HTTPS address." }
        require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null) { "Enter the server address without credentials or query parameters." }
        val builder = base.newBuilder().addPathSegment("rest").addPathSegment("$endpoint.view")
            .addQueryParameter("u", connection.username).addQueryParameter("t", connection.token)
            .addQueryParameter("s", connection.salt).addQueryParameter("v", "1.16.1")
            .addQueryParameter("c", "MusicAndroid").addQueryParameter("f", "json")
        params.forEach { (key,value) -> builder.addQueryParameter(key,value) }
        return builder.build().toString()
    }
}

class Navidrome(context: Context) {
    private val prefs by lazy {
        EncryptedSharedPreferences.create(context.applicationContext, "music_navidrome",
            MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }
    private val client = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
        .callTimeout(25,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    @Volatile var connection: MusicConnection? = null
        private set
    suspend fun load() = withContext(Dispatchers.IO) {
        val server = prefs.getString("server",null)
        if (server != null) connection = MusicConnection(server,prefs.getString("username","").orEmpty(),prefs.getString("token","").orEmpty(),prefs.getString("salt","").orEmpty())
    }
    suspend fun login(server: String, username: String, password: String) = withContext(Dispatchers.IO) {
        require(username.isNotBlank() && password.isNotBlank()) { "Enter your Navidrome username and password." }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val candidate = MusicConnection(server.trim().trimEnd('/'),username.trim(),SubsonicAuth.token(password,salt),salt)
        call("ping", credentials=candidate)
        prefs.edit().putString("server",candidate.server).putString("username",candidate.username)
            .putString("token",candidate.token).putString("salt",candidate.salt).commit()
        connection = candidate
    }
    suspend fun logout() = withContext(Dispatchers.IO) { prefs.edit().clear().commit(); connection = null }
    fun url(endpoint: String, params: Map<String,String> = emptyMap()): String = SubsonicAuth.url(connection ?: error("Connect Navidrome first."),endpoint,params)
    private suspend fun call(endpoint: String, params: Map<String,String> = emptyMap(), credentials: MusicConnection? = connection): JsonObject = withContext(Dispatchers.IO) {
        val url = SubsonicAuth.url(credentials ?: error("Connect Navidrome first."),endpoint,params)
        val response = try { client.newCall(Request.Builder().url(url).build()).execute() }
            catch (error: java.io.IOException) { throw ConnectionProblem(ConnectionDiagnostics.classify(error)) }
        response.use {
            if(!it.isSuccessful) throw ConnectionProblem(if(it.code==401 || it.code==403) ConnectionIssue.AUTH else ConnectionIssue.SERVER)
            val root = try { Json.parseToJsonElement(it.body.string()).jsonObject["subsonic-response"]!!.jsonObject }
                catch (_: Exception) { throw ConnectionProblem(ConnectionIssue.ADDRESS) }
            if (root["status"]?.jsonPrimitive?.content != "ok") {
                val code = root["error"]?.jsonObject?.get("code")?.jsonPrimitive?.intOrNull
                throw ConnectionProblem(if(code==40 || code==50) ConnectionIssue.AUTH else ConnectionIssue.SERVER)
            }
            root
        }
    }
    private fun items(root: JsonObject, group: String, key: String, kind: String): List<LibraryItem> = root[group]?.jsonObject?.get(key)?.jsonArray?.map { item(it.jsonObject,kind) }.orEmpty()
    private fun item(row: JsonObject, kind: String): LibraryItem {
        fun value(key: String) = row[key]?.jsonPrimitive?.contentOrNull.orEmpty()
        return LibraryItem(value("id"),value("title").ifEmpty { value("name") },value("artist"),value("coverArt"),kind,value("duration").toIntOrNull() ?: 0)
    }
    suspend fun albums(offset: Int = 0): List<LibraryItem> = items(call("getAlbumList2",mapOf("type" to "newest","size" to "100","offset" to "$offset")),"albumList2","album","album")
    suspend fun ping() { call("ping") }
    suspend fun artists(): List<LibraryItem> = call("getArtists")["artists"]?.jsonObject?.get("index")?.jsonArray?.flatMap { index -> index.jsonObject["artist"]?.jsonArray?.map { item(it.jsonObject,"artist") }.orEmpty() }.orEmpty()
    suspend fun artistAlbums(id: String): List<LibraryItem> = items(call("getArtist",mapOf("id" to id)),"artist","album","album")
    suspend fun playlists(): List<LibraryItem> = items(call("getPlaylists"),"playlists","playlist","playlist")
    suspend fun songs(item: LibraryItem): List<LibraryItem> = if (item.kind == "playlist") items(call("getPlaylist",mapOf("id" to item.id)),"playlist","entry","song") else items(call("getAlbum",mapOf("id" to item.id)),"album","song","song")
    suspend fun search(query: String): List<LibraryItem> {
        val root = call("search3",mapOf("query" to query,"songCount" to "100","albumCount" to "30","artistCount" to "20"))
        return items(root,"searchResult3","artist","artist") + items(root,"searchResult3","album","album") + items(root,"searchResult3","song","song")
    }
}
