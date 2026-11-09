package com.lidseeker.app.music

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DiagnosticsSheet(vm: MusicViewModel) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var checks by remember { mutableStateOf<List<ConnectionCheck>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    fun test() { if(running) return;scope.launch { running=true;try { checks=ConnectionDiagnostics.check(context,vm.navidrome,vm.requests,vm.requestsConnected) } finally { running=false } } }
    LaunchedEffect(Unit) { test() }
    ModalBottomSheet(onDismissRequest={vm.showDiagnostics=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Text("Connection diagnostics",style=MaterialTheme.typography.headlineSmall)
            Text("Checks run on your phone. No passwords or diagnostic reports are uploaded to another service.",style=MaterialTheme.typography.bodySmall)
            if(running) LinearProgressIndicator(Modifier.fillMaxWidth())
            checks.forEach { check -> Surface(color=MaterialTheme.colorScheme.surfaceContainer,shape=MaterialTheme.shapes.medium) { Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(check.name+if(check.ok) " · OK" else " · Needs attention",color=if(check.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,style=MaterialTheme.typography.titleMedium)
                Text(check.detail,style=MaterialTheme.typography.bodyMedium)
            } } }
            if(note.isNotBlank()) Text(note)
            Button(enabled=!running,onClick={test()},modifier=Modifier.fillMaxWidth()) { Text("Run checks again") }
            OutlinedButton(onClick={val launch=context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn");if(launch!=null) context.startActivity(launch) else note="Open Tailscale manually and confirm it says Connected. If it is missing, install it from its official store listing."},modifier=Modifier.fillMaxWidth()) { Text("Open Tailscale") }
            listOf("Navidrome" to vm.navidrome.connection?.server,"Requests" to vm.requests.currentServerUrl()).forEach { (name,url) ->
                if(!url.isNullOrBlank() && url.startsWith("https://")) TextButton(onClick={runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url))) }.onFailure { note="No browser is available to open this address." }}) { Text("Open $name in browser") }
            }
            TextButton(onClick={vm.showDiagnostics=false;vm.showSettings=true}) { Text("Edit connections / sign in again") }
            Spacer(Modifier.height(12.dp))
        }
    }
}
