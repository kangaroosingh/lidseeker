package com.lidseeker.app.music

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.*
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.lidseeker.app.MainActivity
import kotlinx.coroutines.*

class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var server: Navidrome
    companion object {
        const val LIKE="music.LIKE"
        const val DISLIKE="music.DISLIKE"
        const val CLEAR="music.CLEAR"
        val commands=listOf(LIKE,DISLIKE,CLEAR)
    }
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val result=SettableFuture.create<T>()
        val job=scope.launch { try { result.set(block()) } catch(e: CancellationException) { result.cancel(false) } catch(e: Exception) { result.setException(e) } }
        result.addListener({if(result.isCancelled) job.cancel()},java.util.concurrent.Executor { it.run() })
        return result
    }
    private suspend fun connected() { server.load();check(server.connection!=null) { "Open Music on your phone and connect Navidrome." } }
    private suspend fun children(parent: String): List<MediaItem> {
        if(parent==CarLibrary.ROOT) return CarLibrary.roots()
        connected()
        val rows=when(parent) {
            CarLibrary.RECENT -> server.albums()
            CarLibrary.ARTISTS -> server.artists()
            CarLibrary.PLAYLISTS -> server.playlists()
            CarLibrary.LIKED -> LikedSongs.forServer(server).songs()
            else -> { val (kind,id)=CarLibrary.parse(parent)
                if(kind=="artist") server.artistAlbums(id) else {
                    require(kind=="album" || kind=="playlist")
                    server.songs(LibraryItem(id,"",kind=kind))
                }
            }
        }
        return rows.map { CarLibrary.media(it,server) }
    }
    private suspend fun <T: Any> library(block: suspend () -> LibraryResult<T>): LibraryResult<T> = try { block() }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { LibraryResult.ofError<T>(SessionError(if(server.connection==null) SessionError.ERROR_SESSION_SETUP_REQUIRED else SessionError.ERROR_IO,"Open Music on your phone to check Navidrome and Tailscale.",Bundle.EMPTY)) }
    private val callback=object: MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if(controller.uid!=android.os.Process.myUid() && !controller.isTrusted && !session.isAutoCompanionController(controller) && !session.isAutomotiveController(controller)) return MediaSession.ConnectionResult.reject()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().apply { commands.forEach { add(SessionCommand(it,Bundle.EMPTY)) } }.build())
                .setCustomLayout(buttons()).build()
        }
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
            future { LibraryResult.ofItem(CarLibrary.folder(CarLibrary.ROOT,"Music"),params) }
        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?) =
            future { library { LibraryResult.ofItemList(CarLibrary.page(children(parentId),page,pageSize),params) } }
        override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, params: LibraryParams?) =
            future { library { session.notifyChildrenChanged(browser,parentId,children(parentId).size,params);LibraryResult.ofVoid(params) } }
        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String) = future {
            library {
                val known=CarLibrary.roots().firstOrNull { it.mediaId==mediaId }
                if(mediaId==CarLibrary.ROOT) LibraryResult.ofItem(CarLibrary.folder(CarLibrary.ROOT,"Music"),null)
                else if(known!=null) LibraryResult.ofItem(known,null)
                else { connected();val (kind,id)=CarLibrary.parse(mediaId)
                    LibraryResult.ofItem(if(kind=="song") CarLibrary.media(server.song(id),server) else CarLibrary.folder(mediaId,kind.replaceFirstChar { it.uppercase() }),null)
                }
            }
        }
        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?) = future {
            library {
                connected();require(query.isNotBlank())
                val count=server.search(query.take(200)).size
                session.notifySearchResultChanged(browser,query,count,params)
                LibraryResult.ofVoid(params)
            }
        }
        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?) = future {
            library { connected();LibraryResult.ofItemList(CarLibrary.page(server.search(query.take(200)),page,pageSize).map { CarLibrary.media(it,server) },params) }
        }
        override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> = future {
            mediaItems.map { item ->
                // Phone-supplied local audio is permitted only to our own controller (instrumentation included).
                if(controller.uid==android.os.Process.myUid() && item.localConfiguration!=null) item
                else { connected();val (kind,id)=CarLibrary.parse(item.mediaId);require(kind=="song");CarLibrary.media(server.song(id),server,true) }
            }.toMutableList()
        }
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> = future {
            if(command.customAction !in commands) return@future SessionResult(SessionError.ERROR_NOT_SUPPORTED)
            try {
                connected()
                val mediaId=args.getString("mediaId") ?: session.player.currentMediaItem?.mediaId ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)
                val id=if(mediaId.startsWith("song:")) CarLibrary.parse(mediaId).second else {
                    require(controller.uid==android.os.Process.myUid());mediaId
                }
                server.song(id) // Verify this is a real library song.
                SongFeedback.forServer(server).set(id,when(command.customAction) { LIKE -> Feedback.LIKED;DISLIKE -> Feedback.DISLIKED;else -> Feedback.NONE })
                (session as MediaLibrarySession).notifyChildrenChanged(CarLibrary.LIKED,0,null)
                SessionResult(SessionResult.RESULT_SUCCESS)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { SessionResult(SessionError(SessionError.ERROR_IO,"Could not update song feedback. Open Music on your phone to check the connection.",Bundle.EMPTY)) }
        }
    }
    private fun buttons()=listOf(
        CommandButton.Builder(CommandButton.ICON_THUMB_UP_UNFILLED).setDisplayName("Like").setSessionCommand(SessionCommand(LIKE,Bundle.EMPTY)).build(),
        CommandButton.Builder(CommandButton.ICON_THUMB_DOWN_UNFILLED).setDisplayName("Dislike · delete after 30 days").setSessionCommand(SessionCommand(DISLIKE,Bundle.EMPTY)).build(),
        CommandButton.Builder(CommandButton.ICON_MINUS).setDisplayName("Clear feedback / keep song").setSessionCommand(SessionCommand(CLEAR,Bundle.EMPTY)).build())
    override fun onCreate() {
        super.onCreate();server=Navidrome(this)
        val player=ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true)
            setHandleAudioBecomingNoisy(true);setWakeMode(C.WAKE_MODE_LOCAL)
        }
        val activity=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session=MediaLibrarySession.Builder(this,player,callback).setSessionActivity(activity).setCustomLayout(buttons()).build()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session
    override fun onDestroy() { scope.cancel();session?.run { player.release();release() };session=null;super.onDestroy() }
}
