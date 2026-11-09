package com.lidseeker.app.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.lidseeker.app.ui.CoverArt
import com.lidseeker.app.ui.search.SearchScreen
import com.lidseeker.app.ui.requests.RequestsScreen
import com.lidseeker.app.ui.discover.DiscoverScreen
import com.lidseeker.app.ui.login.LoginScreen
import com.lidseeker.app.ui.settings.SettingsScreen
import com.lidseeker.app.ui.detail.ArtistDetailScreen
import com.lidseeker.app.data.SearchResult
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicRoot() {
    val vm: MusicViewModel=viewModel()
    var requestTab by remember { mutableStateOf("Search") }
    var requestArtist by remember { mutableStateOf<SearchResult?>(null) }
    if(!vm.ready) { Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { CircularProgressIndicator() };return }
    if(vm.requestSettings) { SettingsScreen(onBack={vm.requestSettings=false});return }
    Scaffold(containerColor=Color(0xFF121212), topBar={
        TopAppBar(title={ Column { Text("Music",fontWeight=FontWeight.ExtraBold,style=MaterialTheme.typography.headlineLarge);Text("YOUR MUSIC. YOUR WORLD.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary) } },
            colors=TopAppBarDefaults.topAppBarColors(containerColor=Color(0xFF121212)),actions={
                IconButton(onClick={vm.showSettings=true}) { Icon(Icons.Default.Settings,contentDescription="Connections") }
            })
    },bottomBar={Column {
        vm.now?.let { item ->
            Surface(color=Color(0xFF263E34),shape=RoundedCornerShape(12.dp),modifier=Modifier.padding(horizontal=8.dp).fillMaxWidth().clickable { vm.showPlayer=true }) {
                Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
                    CoverArt(item.mediaMetadata.artworkUri?.toString(),Modifier.size(44.dp))
                    Column(Modifier.weight(1f).padding(horizontal=12.dp)) { Text(item.mediaMetadata.title?.toString().orEmpty(),maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.Bold);Text(item.mediaMetadata.artist?.toString().orEmpty(),style=MaterialTheme.typography.bodySmall,maxLines=1) }
                    IconButton(onClick=vm::toggle) { Icon(if(vm.playing) Icons.Default.Pause else Icons.Default.PlayArrow,if(vm.playing) "Pause" else "Play") }
                    IconButton(onClick={vm.controller?.seekToNextMediaItem()}) { Icon(Icons.Default.SkipNext,"Next song") }
                }
            }
        }
        NavigationBar(containerColor=Color(0xFF121212)) {
            listOf("Home" to Icons.Default.Home,"Search" to Icons.Default.Search,"Library" to Icons.Default.LibraryMusic,"Requests" to Icons.Default.AddCircleOutline).forEach { (name,icon) ->
                NavigationBarItem(selected=vm.tab==name,onClick={vm.tab=name;vm.items=emptyList();vm.detail=null;if(vm.connected && name!="Requests" && name!="Search") vm.browse()},icon={Icon(icon,null)},label={Text(name)},colors=NavigationBarItemDefaults.colors(indicatorColor=Color(0xFF263E34),selectedIconColor=MaterialTheme.colorScheme.primary,selectedTextColor=Color.White))
            }
        }
    }}) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if(vm.message.isNotBlank()) Surface(color=Color(0xFF2B2925),modifier=Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) { Text(vm.message,modifier=Modifier.weight(1f),style=MaterialTheme.typography.bodySmall);TextButton(onClick={vm.showDiagnostics=true}) { Text("Diagnose") };TextButton(onClick={vm.message=""}) { Text("Dismiss") } } }
            if(vm.tab=="Requests") {
                if(!vm.requestsConnected) Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text("One sign-in. More music.",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                    Text("Use the same username and password to connect your library and requests.")
                    Button(onClick={vm.showSettings=true}) { Text("Connect your accounts") }
                }
                else {
                    Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        listOf("Discover","Search","History").forEach { tab -> FilterChip(selected=requestTab==tab,onClick={requestTab=tab},label={Text(tab)}) }
                    }
                    val artist=requestArtist
                    if(artist!=null) ArtistDetailScreen(foreignId=artist.foreignId,artistName=artist.title,onBack={requestArtist=null})
                    else when(requestTab) {
                        "Discover" -> DiscoverScreen(Modifier.weight(1f))
                        "History" -> RequestsScreen(Modifier.weight(1f))
                        else -> SearchScreen(Modifier.weight(1f),onArtistClick={requestArtist=it},library=vm.navidrome)
                    }
                }
            } else if(vm.tab=="Search" && vm.detail==null) {
                SmartSearchScreen(vm,Modifier.weight(1f))
            } else if(!vm.connected) {
                Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF183A29),Color(0xFF121212)))).padding(28.dp),verticalArrangement=Arrangement.Center) {
                    Icon(Icons.Default.Headphones,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(64.dp))
                    Spacer(Modifier.height(24.dp));Text("All your music.\nOne place.",style=MaterialTheme.typography.displaySmall,fontWeight=FontWeight.ExtraBold)
                    Spacer(Modifier.height(16.dp));Text("Connect Navidrome to listen to your albums, artists and playlists. Request something new whenever inspiration strikes.",color=Color.LightGray)
                    Spacer(Modifier.height(28.dp));Button(onClick={vm.showSettings=true},modifier=Modifier.fillMaxWidth()) { Text("Connect your music") }
                    TextButton(onClick={vm.tab="Requests"}) { Text("Open music requests") }
                }
            } else {
                if(vm.tab=="Search") OutlinedTextField(value=vm.query,onValueChange={vm.query=it},placeholder={Text("What do you want to listen to?")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(16.dp),leadingIcon={Icon(Icons.Default.Search,null)},trailingIcon={IconButton(onClick={vm.browse()}) { Icon(Icons.Default.ArrowForward,"Search library") }},keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={vm.browse()}))
                if(vm.tab=="Library" && vm.detail==null) Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("Albums","Artists","Playlists").forEach { kind -> FilterChip(selected=vm.libraryType==kind,onClick={vm.libraryType=kind;vm.items=emptyList();vm.browse()},label={Text(kind)}) } }
                Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(vm.detail!=null) IconButton(onClick={vm.browse()}) { Icon(Icons.Default.ArrowBack,"Back to library") }
                    Text(vm.heading,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
                    if(vm.items.any { it.kind=="song" }) FilledIconButton(onClick={vm.play(vm.items.filter { it.kind=="song" })}) { Icon(Icons.Default.PlayArrow,"Play all") }
                    else IconButton(onClick=vm::browse) { Icon(Icons.Default.Refresh,"Refresh library") }
                }
                if(vm.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val grid=vm.items.isNotEmpty() && vm.items.all { it.kind=="album" }
                if(grid) LazyVerticalGrid(columns=GridCells.Adaptive(145.dp),modifier=Modifier.weight(1f),contentPadding=PaddingValues(16.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalArrangement=Arrangement.spacedBy(22.dp)) {
                    items(vm.items,key={it.kind+it.id}) { item -> Column(Modifier.clickable { vm.open(item) }) {
                        Cover(item,vm,Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)))
                        Spacer(Modifier.height(8.dp));Text(item.title,maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.Bold)
                        Text(item.artist,style=MaterialTheme.typography.bodySmall,color=Color.LightGray,maxLines=1,overflow=TextOverflow.Ellipsis)
                    } }
                    if(vm.detail==null && vm.items.size%100==0) item { TextButton(onClick=vm::moreAlbums) { Text("Load more albums") } }
                } else LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp)) {
                    if(vm.items.isEmpty() && !vm.loading) item { Text(if(vm.tab=="Search") "Search your library above. Can’t find it? Open Requests to add something new." else "No music here yet. Refresh, or add music through Requests.",color=Color.LightGray,modifier=Modifier.padding(vertical=20.dp)) }
                    items(vm.items,key={it.kind+it.id}) { item -> Row(Modifier.fillMaxWidth().clickable { vm.open(item) }.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        Cover(item,vm,Modifier.size(56.dp).clip(RoundedCornerShape(if(item.kind=="artist") 28.dp else 6.dp)))
                        Column(Modifier.weight(1f).padding(horizontal=12.dp)) { Text(item.title,maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.SemiBold);Text(listOf(item.kind.replaceFirstChar { it.uppercase() },item.artist).filter { it.isNotBlank() }.joinToString(" · "),style=MaterialTheme.typography.bodySmall,color=Color.LightGray,maxLines=1,overflow=TextOverflow.Ellipsis) }
                        Icon(if(item.kind=="song") Icons.Default.PlayArrow else Icons.Default.ChevronRight,null,tint=Color.LightGray)
                    } }
                }
            }
        }
    }
    if(vm.showSettings) Connections(vm)
    if(vm.showDiagnostics) DiagnosticsSheet(vm)
    if(vm.showPlayer) NowPlaying(vm)
    if(vm.showQueue) QueueSheet(vm)
}

@Composable private fun Cover(item: LibraryItem,vm: MusicViewModel,modifier: Modifier) {
    if(item.cover.isNotBlank()) AsyncImage(model=vm.navidrome.url("getCoverArt",mapOf("id" to item.cover,"size" to "400")),contentDescription=item.title,contentScale=ContentScale.Crop,modifier=modifier)
    else Box(modifier.background(Color(0xFF263E34)),contentAlignment=Alignment.Center) { Icon(Icons.Default.MusicNote,null,tint=Color(0xFF1DB954)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Connections(vm: MusicViewModel) {
    var server by remember { mutableStateOf(vm.navidrome.connection?.server.orEmpty()) }
    var user by remember { mutableStateOf(vm.navidrome.connection?.username.orEmpty()) }
    var requestServer by remember { mutableStateOf(vm.requests.currentServerUrl()) }
    var password by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest={vm.showSettings=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Your connections",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Text("One sign-in for listening and requests",color=MaterialTheme.colorScheme.primary)
            if(vm.connected) Text("Connected as ${vm.navidrome.connection?.username}")
            OutlinedTextField(server,{server=it;if(requestServer.isBlank()) { val parsed=it.toHttpUrlOrNull();if(parsed?.port==8443) requestServer=parsed.newBuilder().port(8450).build().toString().trimEnd('/') }},label={Text("Navidrome HTTPS address")},placeholder={Text("https://music.example.com")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(requestServer,{requestServer=it},label={Text("Lidseeker HTTPS address")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(user,{user=it},label={Text("Username")},singleLine=true,modifier=Modifier.fillMaxWidth())
            OutlinedTextField(password,{password=it},label={Text("Password")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
            if(vm.message.isNotBlank()) Text(vm.message,color=MaterialTheme.colorScheme.error)
            Button(onClick={vm.login(server,requestServer,user,password);password=""},enabled=!vm.loading,modifier=Modifier.fillMaxWidth()) { Text(if(vm.loading) "Connecting…" else "Connect both accounts") }
            if(vm.connected) TextButton(onClick=vm::disconnect) { Text("Disconnect Navidrome") }
            HorizontalDivider();Text("Lidseeker · requests",color=MaterialTheme.colorScheme.primary)
            Text(if(vm.requestsConnected) "Connected to your request server" else "Sign in from the Requests tab",style=MaterialTheme.typography.bodyMedium)
            TextButton(onClick={vm.showSettings=false;vm.tab="Requests"}) { Text("Open requests") }
            TextButton(onClick={vm.showSettings=false;vm.showDiagnostics=true}) { Text("Test connections / diagnose") }
            if(vm.requestsConnected) {
                TextButton(onClick={vm.showSettings=false;vm.requestSettings=true}) { Text("Request server settings") }
                TextButton(onClick={vm.action { vm.requests.logout();vm.requestsConnected=false }}) { Text("Disconnect requests") }
            }
            Text("Music 2.1 · Based on the open-source Lidseeker app",style=MaterialTheme.typography.bodySmall,color=Color.Gray)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun NowPlaying(vm: MusicViewModel) {
    val player=vm.controller ?: return
    val item=vm.now ?: return
    var position by remember { mutableLongStateOf(player.currentPosition) }
    var duration by remember { mutableLongStateOf(player.duration.coerceAtLeast(0)) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(player,item) { while(true) { if(!dragging) position=player.currentPosition;duration=player.duration.let { if(it==C.TIME_UNSET) 0 else it.coerceAtLeast(0) };delay(500) } }
    ModalBottomSheet(onDismissRequest={vm.showPlayer=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=Color(0xFF182A23)) {
        Column(Modifier.padding(horizontal=28.dp,vertical=12.dp).fillMaxWidth(),horizontalAlignment=Alignment.CenterHorizontally) {
            Text("PLAYING FROM YOUR LIBRARY",style=MaterialTheme.typography.labelSmall,color=Color.LightGray)
            Spacer(Modifier.height(24.dp));CoverArt(item.mediaMetadata.artworkUri?.toString(),Modifier.fillMaxWidth().aspectRatio(1f))
            Spacer(Modifier.height(24.dp));Text(item.mediaMetadata.title?.toString().orEmpty(),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Text(item.mediaMetadata.artist?.toString().orEmpty(),color=Color.LightGray)
            Spacer(Modifier.height(16.dp));Slider(value=position.toFloat().coerceIn(0f,duration.coerceAtLeast(1).toFloat()),onValueChange={dragging=true;position=it.toLong()},onValueChangeFinished={player.seekTo(position);dragging=false},valueRange=0f..duration.coerceAtLeast(1).toFloat(),enabled=duration>0)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(clock(position),style=MaterialTheme.typography.bodySmall);Text(clock(duration),style=MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth().padding(vertical=16.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={player.shuffleModeEnabled=!player.shuffleModeEnabled}) { Icon(Icons.Default.Shuffle,"Toggle shuffle",tint=if(player.shuffleModeEnabled) MaterialTheme.colorScheme.primary else Color.LightGray) }
                IconButton(onClick={player.seekToPreviousMediaItem()}) { Icon(Icons.Default.SkipPrevious,"Previous song") }
                FilledIconButton(onClick=vm::toggle,modifier=Modifier.size(64.dp)) { Icon(if(vm.playing) Icons.Default.Pause else Icons.Default.PlayArrow,if(vm.playing) "Pause" else "Play",modifier=Modifier.size(36.dp)) }
                IconButton(onClick={player.seekToNextMediaItem()}) { Icon(Icons.Default.SkipNext,"Next song") }
                IconButton(onClick={player.repeatMode=if(player.repeatMode==Player.REPEAT_MODE_OFF) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF}) { Icon(Icons.Default.Repeat,"Toggle repeat",tint=if(player.repeatMode!=Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else Color.LightGray) }
            }
            TextButton(onClick={vm.showPlayer=false;vm.showQueue=true}) { Icon(Icons.Default.QueueMusic,null);Spacer(Modifier.width(8.dp));Text("Playing queue · ${vm.queue.size} songs") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
private fun clock(ms: Long): String { val seconds=ms.coerceAtLeast(0)/1000;return "${seconds/60}:${(seconds%60).toString().padStart(2,'0')}" }

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun QueueSheet(vm: MusicViewModel) {
    ModalBottomSheet(onDismissRequest={vm.showQueue=false}) {
        Text("Playing queue",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,modifier=Modifier.padding(20.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=500.dp)) { items(vm.queue.size) { index -> val item=vm.queue[index]
            Row(Modifier.fillMaxWidth().clickable { vm.controller?.seekToDefaultPosition(index);vm.controller?.play() }.padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(item.mediaMetadata.title?.toString().orEmpty(),color=if(item.mediaId==vm.now?.mediaId) MaterialTheme.colorScheme.primary else Color.White,maxLines=1,overflow=TextOverflow.Ellipsis);Text(item.mediaMetadata.artist?.toString().orEmpty(),style=MaterialTheme.typography.bodySmall,color=Color.LightGray) }
                IconButton(onClick={vm.controller?.removeMediaItem(index)}) { Icon(Icons.Default.Close,"Remove from queue") }
            }
        } }
    }
}
