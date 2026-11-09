package com.lidseeker.app.music

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.lidseeker.app.repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MusicViewModel(app: Application) : AndroidViewModel(app) {
    val navidrome = Navidrome(app)
    val requests = app.repository
    var ready by mutableStateOf(false)
    var connected by mutableStateOf(false)
    var requestsConnected by mutableStateOf(false)
    var tab by mutableStateOf("Home")
    var libraryType by mutableStateOf("Albums")
    var query by mutableStateOf("")
    var items by mutableStateOf<List<LibraryItem>>(emptyList())
    var heading by mutableStateOf("Recently added")
    var detail by mutableStateOf<LibraryItem?>(null)
    var loading by mutableStateOf(false)
    var message by mutableStateOf("")
    var now by mutableStateOf<MediaItem?>(null)
    var playing by mutableStateOf(false)
    var queue by mutableStateOf<List<MediaItem>>(emptyList())
    var controller by mutableStateOf<MediaController?>(null)
    var showPlayer by mutableStateOf(false)
    var showQueue by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var showDiagnostics by mutableStateOf(false)
    var requestSettings by mutableStateOf(false)
    private val controllerFuture = MediaController.Builder(app,SessionToken(app,ComponentName(app,PlaybackService::class.java))).buildAsync()
    private var generation = 0
    init {
        controllerFuture.addListener({
            runCatching { controllerFuture.get() }.onSuccess { player ->
                controller = player
                player.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) { syncPlayer() }
                    override fun onPlayerError(error: PlaybackException) { message = "Playback failed. Check your connection, Tailscale and Navidrome sign-in." }
                })
                syncPlayer()
            }.onFailure { message = "The audio service could not start. Reopen Music to try again." }
        },ContextCompat.getMainExecutor(app))
        viewModelScope.launch {
            try { requests.bootstrap(); navidrome.load(); requestsConnected=requests.isLoggedIn(); connected=navidrome.connection!=null }
            catch (_: Exception) { message="Could not load saved connections. Sign in again." }
            ready=true
            if (connected) browse()
        }
        viewModelScope.launch { requests.authExpired.collect { requests.logout(); requestsConnected=false } }
    }
    private fun syncPlayer() {
        controller?.let { p -> now=p.currentMediaItem; playing=p.isPlaying; queue=(0 until p.mediaItemCount).map { p.getMediaItemAt(it) } }
    }
    fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            loading=true;message=""
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message=if(e is IllegalArgumentException || e is IllegalStateException) e.message.orEmpty() else "Could not load music. Check your server and connection." }
            finally { loading=false }
        }
    }
    fun login(server: String, requestServer: String, user: String, password: String) = action {
        navidrome.login(server,user,password)
        connected=true
        try {
            require(requestServer.startsWith("https://")) { "Enter your Lidseeker HTTPS address." }
            requests.saveServerUrl(requestServer)
            requests.login(user,password)
            requestsConnected=true
            showSettings=false
            loadBrowse()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            message="Navidrome connected. Requests sign-in failed: check the request server address and that both accounts use the same username and password."
        }
    }
    fun disconnect() = action {
        controller?.stop();controller?.clearMediaItems();navidrome.logout();connected=false;items=emptyList();detail=null
    }
    fun browse() = action { loadBrowse() }
    private suspend fun loadBrowse() {
        val requestGeneration=++generation
        detail=null
        val result=when {
            tab=="Search" && query.isNotBlank() -> { heading="In your library"; navidrome.search(query.trim()) }
            tab=="Library" && libraryType=="Artists" -> { heading="Artists";navidrome.artists() }
            tab=="Library" && libraryType=="Playlists" -> { heading="Your playlists";navidrome.playlists() }
            else -> { heading=if(tab=="Home") "Recently added" else "Albums";navidrome.albums() }
        }
        if(requestGeneration==generation) items=result
    }
    fun open(item: LibraryItem) {
        if(item.kind=="song") { play(items.filter { it.kind=="song" },items.filter { it.kind=="song" }.indexOf(item));return }
        action {
            val requestGeneration=++generation
            val found=if(item.kind=="artist") navidrome.artistAlbums(item.id) else navidrome.songs(item)
            if(requestGeneration==generation) { detail=item;heading=item.title;items=found }
        }
    }
    fun moreAlbums() = action { items=items+navidrome.albums(items.size) }
    fun play(songs: List<LibraryItem>, index: Int=0) {
        if(songs.isEmpty()) return
        if(navidrome.connection==null) { message="Connect Navidrome before playing library results.";showSettings=true;return }
        val player=controller ?: run { message="Audio service is still connecting. Try again in a moment.";return }
        val media=songs.map { song -> MediaItem.Builder().setMediaId(song.id)
            .setUri(navidrome.url("stream",mapOf("id" to song.id)))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist)
                .setArtworkUri(if(song.cover.isNotBlank()) Uri.parse(navidrome.url("getCoverArt",mapOf("id" to song.cover,"size" to "600"))) else null).build()).build() }
        player.setMediaItems(media,index.coerceIn(media.indices),0);player.prepare();player.play();syncPlayer()
    }
    fun toggle() { controller?.let { if(it.isPlaying) it.pause() else { if(it.playbackState==Player.STATE_IDLE) it.prepare();it.play() } } }
    override fun onCleared() { MediaController.releaseFuture(controllerFuture);super.onCleared() }
}
