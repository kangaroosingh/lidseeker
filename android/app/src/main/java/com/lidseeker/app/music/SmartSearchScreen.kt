package com.lidseeker.app.music

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lidseeker.app.ui.AppCard
import com.lidseeker.app.ui.MediaRow

@Composable
fun SmartSearchScreen(music: MusicViewModel,modifier: Modifier=Modifier) {
    val vm: SmartSearchViewModel=viewModel(factory=object: ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T: ViewModel> create(modelClass: Class<T>): T = SmartSearchViewModel(music.navidrome,music.requests) as T
    })
    LaunchedEffect(music.connected,music.requestsConnected) {
        if(!music.connected) vm.songs=emptyList()
        if(!music.requestsConnected) {vm.catalog=emptyList();vm.videos=emptyList()}
    }
    LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("Find it. Play it. Request it.",style=MaterialTheme.typography.headlineSmall) }
        item { OutlinedTextField(vm.query,{vm.query=it},label={Text("Song, artist, or YouTube video link")},leadingIcon={Icon(Icons.Default.Search,null)},singleLine=true,modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={vm.search(music.requestsConnected)})) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(enabled=!vm.loading && vm.query.isNotBlank(),onClick={vm.search(music.requestsConnected)}) { Text("Search") }
            OutlinedButton(enabled=!vm.loading && music.requestsConnected && vm.query.isNotBlank(),onClick=vm::searchYouTube) { Text("Find on YouTube") }
        } }
        if(vm.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if(vm.status.isNotBlank()) item { Text(vm.status,color=MaterialTheme.colorScheme.primary) }
        items(vm.notes) { note -> Text(note,style=MaterialTheme.typography.bodySmall) }
        if(vm.notes.isNotEmpty()) item { TextButton(onClick={music.showDiagnostics=true}) { Text("Diagnose connections") } }
        if(vm.searched || vm.songs.isNotEmpty()) item { Text("In your library",style=MaterialTheme.typography.titleLarge) }
        if(vm.searched && vm.songs.isEmpty() && !vm.loading) item { Text("No library matches returned. Check any connection warning above before requesting.",style=MaterialTheme.typography.bodySmall) }
        items(vm.songs,key={"library-"+it.kind+it.id}) { item ->
            AppCard { MediaRow(imageUrl=if(!music.connected || item.cover.isBlank()) null else music.navidrome.url("getCoverArt",mapOf("id" to item.cover,"size" to "200")),title=item.title,subtitle="${item.kind.replaceFirstChar { it.uppercase() }} · ${item.artist}",onClick={if(item.kind=="song") music.play(vm.songs.filter { it.kind=="song" },vm.songs.filter { it.kind=="song" }.indexOf(item)) else music.open(item)},trailing={Text(if(item.kind=="song") "Play" else "Open",color=MaterialTheme.colorScheme.primary)}) }
        }
        if(vm.catalog.isNotEmpty()) item { Text("Available to request",style=MaterialTheme.typography.titleLarge);Text("Song requests through Lidseeker download the parent album.",style=MaterialTheme.typography.bodySmall) }
        items(vm.catalog,key={"catalog-"+it.type+it.foreignId}) { item ->
            val matched=vm.songs.any { it.kind=="song" && MusicMatching.likelySame(item.title,item.artist.orEmpty(),it.title,it.artist) }
            AppCard { MediaRow(imageUrl=item.imageUrl,title=item.title,subtitle=listOfNotNull(item.artist,item.albumTitle).joinToString(" · "),trailing={
                if(item.inLibrary) Text("In library",color=MaterialTheme.colorScheme.primary)
                else if(item.requested) Text("Requested",color=MaterialTheme.colorScheme.primary)
                else TextButton(onClick={vm.choose(item)}) { Text(if(matched) "Check match" else "Request") }
            }) }
        }
        if(vm.videos.isNotEmpty()) item { Text("YouTube results",style=MaterialTheme.typography.titleLarge);Text("Check title and artist before requesting.",style=MaterialTheme.typography.bodySmall) }
        items(vm.videos,key={"video-"+it.video_id}) { item -> AppCard { MediaRow(imageUrl=null,title=item.title,subtitle=item.channel,trailing={TextButton(onClick={vm.choose(item)}) { Text("Request") }}) } }
        if(!vm.searched && vm.videos.isEmpty()) item { Text("Search checks your library and request catalog together. YouTube is queried only when you choose it or paste a video link.",style=MaterialTheme.typography.bodyMedium) }
    }
    if(vm.catalogSelection!=null || vm.videoSelection!=null) AlertDialog(onDismissRequest={if(!vm.sending) vm.cancelReview()},title={Text(if(vm.duplicates.isNotEmpty()) "Possible duplicate" else "Review request")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(vm.videoSelection!=null) {
                OutlinedTextField(vm.title,{if(it.length<=200) {vm.title=it;vm.edited()}},label={Text("Song title")},enabled=!vm.sending)
                OutlinedTextField(vm.artist,{if(it.length<=200) {vm.artist=it;vm.edited()}},label={Text("Artist")},enabled=!vm.sending)
                Text("A channel can be a label. Correct these details before checking.",style=MaterialTheme.typography.bodySmall)
            } else {
                Text(vm.title,style=MaterialTheme.typography.titleMedium);Text(vm.artist)
                Text("This requests the full album${vm.catalogSelection?.albumTitle?.let { ": $it" }.orEmpty()}.",style=MaterialTheme.typography.bodySmall)
            }
            if(vm.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            vm.duplicates.take(8).forEach { Text(it,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary) }
            if(vm.checked && vm.duplicates.isEmpty()) Text(if(vm.reviewNote.isBlank()) "No likely duplicate found." else "Duplicate checks were incomplete. Review the warning below.",style=MaterialTheme.typography.bodySmall)
            if(vm.duplicates.isNotEmpty()) Text("These may be different recordings. Only request another copy if that is what you want.",style=MaterialTheme.typography.bodySmall)
            if(vm.reviewNote.isNotBlank()) Text(vm.reviewNote,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
        }
    },confirmButton={TextButton(enabled=!vm.checking && !vm.sending,onClick={if(vm.checked) vm.submit() else vm.checkDuplicates()}) { Text(if(vm.sending) "Submitting…" else if(!vm.checked) "Check duplicates" else if(vm.duplicates.isNotEmpty() || vm.reviewNote.isNotBlank()) "Request anyway" else "Request") }},dismissButton={TextButton(enabled=!vm.sending,onClick=vm::cancelReview) { Text("Cancel") }})
}
