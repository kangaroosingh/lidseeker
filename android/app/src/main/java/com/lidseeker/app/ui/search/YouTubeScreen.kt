package com.lidseeker.app.ui.search

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.lidseeker.app.data.*
import com.lidseeker.app.ui.AppCard
import com.lidseeker.app.ui.MediaRow
import com.lidseeker.app.ui.repoFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException
import kotlinx.serialization.json.*

class YouTubeViewModel(private val repo: Repository) : ViewModel() {
    var query by mutableStateOf("")
    var limit by mutableStateOf(25)
    var enabled by mutableStateOf<Boolean?>(null)
    var busy by mutableStateOf(false)
    var searched by mutableStateOf(false)
    var message by mutableStateOf("")
    var results by mutableStateOf<List<YouTubeVideo>>(emptyList())
    var jobs by mutableStateOf<List<YouTubeJob>>(emptyList())
    var playlist by mutableStateOf<YouTubePlaylist?>(null)
    var selected by mutableStateOf<YouTubeVideo?>(null)
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    val isPlaylist get() = query.trim().startsWith("https://", true) && Regex("[?&]list=").containsMatchIn(query)

    suspend fun refresh() {
        try { val data = repo.youtube(); enabled = data.enabled; jobs = data.requests }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (enabled == null) message = "Could not load YouTube requests. Try again." }
    }
    private fun perform(action: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true; message = ""
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                message = if (e is HttpException) {
                    runCatching { Json.parseToJsonElement(e.response()?.errorBody()?.string().orEmpty()).jsonObject["detail"]?.jsonPrimitive?.content }.getOrNull() ?: "Request failed (${e.code()}). Try again."
                } else "Could not reach the server. Check your connection and Tailscale."
            } finally { busy = false }
        }
    }
    fun search() = perform {
        if (query.isBlank()) return@perform
        searched = true; results = emptyList(); playlist = null
        if (isPlaylist) playlist = repo.youtubePreview(query.trim(), limit)
        else { results = repo.youtubeSearch(query.trim()); if (results.isEmpty()) message = "No songs found. Try another search or paste a video link." }
    }
    fun request(url: String, name: String, performer: String) = perform {
        val job = repo.youtubeRequest(YouTubeLink(url, name, performer))
        message = if (job.status == "downloaded") "Already downloaded." else "Song queued. You can close the app."
        selected = null; refresh()
    }
    fun importPlaylist() = perform {
        val current = playlist ?: return@perform
        val result = repo.youtubePlaylist(current.songs.map { it.video_id })
        message = "${result.queued} songs queued; ${result.skipped} already requested."
        playlist = null; refresh()
    }
}

@Composable
fun YouTubeScreen(modifier: Modifier = Modifier) {
    val vm: YouTubeViewModel = viewModel(factory = repoFactory { YouTubeViewModel(it) })
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { vm.refresh(); delay(5000) }
        }
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            OutlinedTextField(value = vm.query, onValueChange = { vm.query = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Search YouTube or paste a link") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { vm.search() }))
        }
        if (vm.isPlaylist) item {
            Text("Playlist track limit", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 25, 50).forEach { n -> FilterChip(selected = vm.limit == n, onClick = { vm.limit = n }, label = { Text("$n") }) }
            }
        }
        item {
            Button(onClick = vm::search, enabled = !vm.busy && vm.query.isNotBlank() && vm.enabled == true, modifier = Modifier.fillMaxWidth()) {
                Text(if (vm.busy) "Working…" else if (vm.isPlaylist) "Preview playlist" else "Search")
            }
        }
        if (vm.enabled == false) item { Text("YouTube requests are disabled on this server.") }
        if (vm.busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (vm.message.isNotBlank()) item { Text(vm.message, color = MaterialTheme.colorScheme.primary) }
        if (!vm.searched && vm.enabled == true) item { Text("Find songs by title and artist, or paste a YouTube video or playlist link.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(vm.results, key = { it.video_id }) { video ->
            AppCard { MediaRow(imageUrl = null, title = video.title, subtitle = video.channel, trailing = {
                TextButton(enabled = !vm.busy, onClick = { vm.selected = video; vm.title = video.title; vm.artist = video.artist; vm.message = "" }) { Text("Request") }
            }) }
        }
        vm.playlist?.let { playlist ->
            item { Text(playlist.title, style = MaterialTheme.typography.titleMedium); Text("${playlist.songs.size} songs${if (playlist.truncated) " · first ${playlist.limit} entries" else ""}. One-time import; existing requests are skipped.") }
            items(playlist.songs, key = { it.video_id }) { Text(it.title, style = MaterialTheme.typography.bodyMedium) }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !vm.busy, onClick = { vm.playlist = null }) { Text("Cancel") }
                Button(enabled = !vm.busy, onClick = vm::importPlaylist) { Text("Request ${playlist.songs.size} songs") }
            } }
        }
        if (vm.jobs.isNotEmpty()) item { Text("YouTube requests", style = MaterialTheme.typography.titleMedium) }
        items(vm.jobs, key = { it.id }) { job ->
            AppCard { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(job.title.ifBlank { job.video_id }, style = MaterialTheme.typography.titleSmall)
                Text(job.artist, style = MaterialTheme.typography.bodySmall)
                Text(job.status.replaceFirstChar { it.uppercase() }, color = if (job.status == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                job.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (job.status == "failed") TextButton(enabled = !vm.busy, onClick = { vm.request("https://youtu.be/${job.video_id}", job.title, job.artist) }) { Text("Retry") }
            } }
        }
    }
    vm.selected?.let { video ->
        AlertDialog(onDismissRequest = { if (!vm.busy) vm.selected = null }, title = { Text("Request this song?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = vm.title, onValueChange = { if (it.length <= 200) vm.title = it }, label = { Text("Song title") })
                OutlinedTextField(value = vm.artist, onValueChange = { if (it.length <= 200) vm.artist = it }, label = { Text("Artist (optional)") })
                Text("Check the artist: the channel may be a record label. Singles are grouped by artist.")
                if (vm.message.isNotBlank()) Text(vm.message, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(enabled = !vm.busy, onClick = { vm.request("https://youtu.be/${video.video_id}", vm.title, vm.artist) }) { Text(if (vm.busy) "Requesting…" else "Request song") } },
            dismissButton = { TextButton(enabled = !vm.busy, onClick = { vm.selected = null }) { Text("Cancel") } })
    }
}
