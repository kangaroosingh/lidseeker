package com.lidseeker.app.music

import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lidseeker.app.data.*
import kotlinx.coroutines.*

class SmartSearchViewModel(val library: Navidrome,val repo: Repository): ViewModel() {
    var query by mutableStateOf("")
    var songs by mutableStateOf<List<LibraryItem>>(emptyList())
    var catalog by mutableStateOf<List<SearchResult>>(emptyList())
    var videos by mutableStateOf<List<YouTubeVideo>>(emptyList())
    var notes by mutableStateOf<List<String>>(emptyList())
    var loading by mutableStateOf(false)
    var searched by mutableStateOf(false)
    var catalogSelection by mutableStateOf<SearchResult?>(null)
    var videoSelection by mutableStateOf<YouTubeVideo?>(null)
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var duplicates by mutableStateOf<List<String>>(emptyList())
    var checked by mutableStateOf(false)
    var checking by mutableStateOf(false)
    var sending by mutableStateOf(false)
    var reviewNote by mutableStateOf("")
    var status by mutableStateOf("")
    private var searchJob: Job?=null
    private var youtubeJob: Job?=null
    private var checkJob: Job?=null
    private var revision=0
    fun search(online: Boolean=true) {
        if(query.isBlank()) return
        searchJob?.cancel()
        youtubeJob?.cancel()
        val term=query.trim()
        searchJob=viewModelScope.launch {
            loading=true;searched=true;notes=emptyList();songs=emptyList();catalog=emptyList();videos=emptyList();status=""
            try { supervisorScope {
                val local=async { if(library.connection==null) { emptyList<LibraryItem>() to "Connect Navidrome to check your library." } else try { library.search(term) to "" } catch(e: CancellationException) { throw e } catch(e: Exception) { emptyList<LibraryItem>() to ConnectionDiagnostics.explain(ConnectionDiagnostics.classify(e)) } }
                val remote=async { if(!online) { emptyList<SearchResult>() to "Sign in to Requests to search for missing music." } else if(term.contains("://")) { emptyList<SearchResult>() to "" } else try { repo.search(term,"track") to "" } catch(e: CancellationException) { throw e } catch(e: Exception) { emptyList<SearchResult>() to ("Requests: "+ConnectionDiagnostics.explain(ConnectionDiagnostics.classify(e))) } }
                val a=local.await();val b=remote.await()
                songs=a.first.sortedWith(compareBy<LibraryItem>({ if(it.kind=="song") 0 else 1 },{MusicMatching.rank(term,it)}))
                catalog=b.first
                notes=listOf(a.second,b.second).filter { it.isNotBlank() }
            }
                if(term.contains("://") && online) searchYouTube()
            } finally { loading=false }
        }
    }
    fun searchYouTube() {
        if(query.isBlank()) return
        youtubeJob?.cancel()
        val term=query.trim()
        youtubeJob=viewModelScope.launch {
            loading=true;status=""
            try {
                if(term.contains(Regex("[?&]list="))) status="For playlist previews, open Requests → Search → YouTube."
                else videos=repo.youtubeSearch(term)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { status="YouTube search failed. Check Connections, or try again later; the provider may be unavailable." }
            finally { loading=false }
        }
    }
    fun choose(item: SearchResult) { cancelReview();catalogSelection=item;title=item.title;artist=item.artist.orEmpty();checkDuplicates() }
    fun choose(item: YouTubeVideo) { cancelReview();videoSelection=item;title=item.title;artist=item.artist;checkDuplicates() }
    fun cancelReview() { checkJob?.cancel();revision++;catalogSelection=null;videoSelection=null;duplicates=emptyList();checked=false;checking=false;reviewNote="" }
    fun edited() { revision++;checkJob?.cancel();checked=false;checking=false;duplicates=emptyList();reviewNote="" }
    fun checkDuplicates() {
        checkJob?.cancel()
        val current=++revision;val name=title;val performer=artist
        val selectedCatalog=catalogSelection;val selectedVideo=videoSelection
        checkJob=viewModelScope.launch {
            checking=true;checked=false;duplicates=emptyList();reviewNote=""
            try {
                val matches=mutableListOf<String>();val incomplete=mutableListOf<String>()
                supervisorScope {
                    val owned=async {
                        if(library.connection==null) { incomplete.add("Library is not connected.");emptyList() }
                        else try { library.search(name) } catch(e: CancellationException) { throw e } catch(_: Exception) { incomplete.add("Library check unavailable.");emptyList() }
                    }
                    val requested=async {
                        try { repo.requests() } catch(e: CancellationException) { throw e } catch(_: Exception) { incomplete.add("Request history check unavailable.");emptyList() }
                    }
                    val youtube=async {
                        try { repo.youtube().requests } catch(e: CancellationException) { throw e } catch(_: Exception) { incomplete.add("YouTube history check unavailable.");emptyList() }
                    }
                    owned.await().filter { it.kind==(if(selectedCatalog?.type=="album") "album" else "song") && MusicMatching.likelySame(name,performer,it.title,it.artist) }.forEach { matches.add("In library: ${it.title} · ${it.artist}") }
                    requested.await().filter { it.status!="error" && it.status!="failed" && (it.foreignId==selectedCatalog?.foreignId || MusicMatching.likelySame(name,performer,it.title,it.artist.orEmpty())) }.forEach { matches.add("Already requested: ${it.title} · ${it.artist.orEmpty()}") }
                    youtube.await().filter { it.status!="failed" && (it.video_id==selectedVideo?.video_id || MusicMatching.likelySame(name,performer,it.title,it.artist)) }.forEach { matches.add("YouTube request: ${it.title.ifBlank { it.video_id }} · ${it.status}") }
                }
                if(current==revision) { duplicates=matches.distinct();reviewNote=incomplete.joinToString(" ");checked=true }
            } finally { if(current==revision) checking=false }
        }
    }
    fun submit() {
        if(!checked || checking || sending) return
        val item=catalogSelection;val video=videoSelection
        if(item==null && video==null) return
        viewModelScope.launch {
            sending=true
            try {
                if(video!=null) {
                    val job=repo.youtubeRequest(YouTubeLink("https://youtu.be/${video.video_id}",title,artist))
                    status=if(job.status=="downloaded") "This video was already downloaded. Allow time for the library scan." else "Song queued. Follow it in Requests → Search → YouTube."
                } else if(item!=null) {
                    val result=repo.request(item.type,item.foreignId,albumForeignId=item.albumForeignId)
                    if(result.status=="error") { reviewNote="The server could not submit this request. Check Requests for details.";return@launch }
                    status="Request submitted. Follow its progress in Requests → History."
                    catalog=catalog.map { if(it.foreignId==item.foreignId) it.copy(requested=true) else it }
                }
                cancelReview()
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { reviewNote="Request failed. "+ConnectionDiagnostics.explain(ConnectionDiagnostics.classify(e)) }
            finally { sending=false }
        }
    }
}
