package com.evanna.player.sources

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.evanna.player.EvannaDesign
import com.evanna.player.ScreenBackHandler
import com.evanna.player.ScreenHeader
import com.evanna.player.SettingsLink
import com.evanna.player.network.CancelScope
import com.evanna.player.network.RangeReader
import com.evanna.player.playback.PlaybackService
import com.evanna.player.sources.jellyfin.JellyfinClient
import com.evanna.player.sources.plex.*
import com.evanna.player.sources.radio.RadioClient
import com.evanna.player.sources.smb.SmbClient
import com.evanna.player.sources.subsonic.SubsonicClient
import com.evanna.player.sources.upnp.*
import com.evanna.player.sources.webdav.WebDavClient
import com.evanna.player.ui.*
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject

private class PlexSelection(val clientId:String,val account:PlexAccount,val servers:List<PlexServer>,val allowLanHttp:Boolean)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun SourcesScreen(onBack:()->Unit,bottomInset:Dp,onPlay:(SourceReference,String,(Boolean)->Unit)->Unit) {
    val context=LocalContext.current
    val catalog=remember(context){SourceCatalog(context.applicationContext)}
    val store=catalog.store
    val scope=rememberCoroutineScope()
    val revision by store.changes.collectAsState()
    var connections by remember {mutableStateOf(emptyList<SourceConnection>())}
    var connection by remember {mutableStateOf<SourceConnection?>(null)}
    var parents by remember {mutableStateOf(emptyList<SourceItem>())}
    var page by remember {mutableStateOf(CatalogPage(emptyList(),null))}
    var menu by remember {mutableStateOf<SourceItem?>(null)}
    var kind by remember {mutableStateOf<SourceKind?>(null)}
    var editing by remember {mutableStateOf<SourceConnection?>(null)}
    var name by remember {mutableStateOf("")}
    var address by remember {mutableStateOf("")}
    var username by remember {mutableStateOf("")}
    var password by remember {mutableStateOf("")}
    var apiKey by remember {mutableStateOf("")}
    var legacyPassword by remember {mutableStateOf(false)}
    var domain by remember {mutableStateOf("")}
    var guest by remember {mutableStateOf(false)}
    var lan by remember {mutableStateOf(false)}
    var discovery by remember {mutableStateOf(emptyList<DiscoveredUpnpServer>())}
    var plex by remember {mutableStateOf<PlexSelection?>(null)}
    var plexServer by remember {mutableStateOf<PlexServer?>(null)}
    var query by remember {mutableStateOf("")}
    var submittedQuery by remember {mutableStateOf<String?>(null)}
    var request by remember {mutableIntStateOf(0)}
    var operation by remember {mutableStateOf<CancelScope?>(null)}
    var message by remember {mutableStateOf<String?>(null)}
    var forgetting by remember {mutableStateOf<SourceConnection?>(null)}
    var showDownloads by remember {mutableStateOf(false)}

    DisposableEffect(Unit){onDispose {operation?.cancel()}}
    LaunchedEffect(revision) {
        try {connections=withContext(Dispatchers.IO){store.connections().filterNot {it.kind==SourceKind.WEB}}}
        catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){message="Saved sources could not be read."}
    }

    fun run(error:String,action:suspend (CancelScope)->Unit) {
        if(operation!=null)return
        val cancel=CancelScope()
        operation=cancel
        message=null
        scope.launch {
            try {action(cancel)}
            catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){if(!cancel.cancelled)message=error}
            finally {if(operation===cancel)operation=null}
        }
    }

    fun open(server:SourceConnection) {
        connection=server;parents=emptyList();page=CatalogPage(emptyList(),null)
        submittedQuery=null;query="";menu=null;message=null;showDownloads=!server.enabled;request++
    }
    fun setup(source:SourceKind,previous:SourceConnection?=null) {
        kind=source;editing=previous;name=previous?.name.orEmpty()
        address=previous?.config?.optString("url").orEmpty()
        username=previous?.config?.optString("username").orEmpty()
        password="";apiKey="";legacyPassword=false;domain="";guest=false
        lan=previous?.config?.optBoolean("allowLanHttp") ?: false
        discovery=emptyList();plex=null;plexServer=null;message=null
    }
    fun back() {
        if(operation!=null){operation?.cancel();return}
        message=null
        when {
            menu!=null->menu=null
            plexServer!=null->plexServer=null
            plex!=null->plex=null
            kind!=null->{kind=null;editing=null;password="";apiKey=""}
            showDownloads && connection?.enabled==true->{showDownloads=false;request++}
            submittedQuery!=null->{submittedQuery=null;query="";request++}
            parents.isNotEmpty()->{parents=parents.dropLast(1);page=CatalogPage(emptyList(),null);request++}
            connection!=null->connection=null
            else->onBack()
        }
    }

    ScreenBackHandler(connection!=null || kind!=null) {back()}

    LaunchedEffect(request) {
        val server=connection ?: return@LaunchedEffect
        val downloads=showDownloads
        run("Could not load this folder. Check your connection and try again.") {cancel ->
            val loaded=withContext(Dispatchers.IO){
                if(downloads)CatalogPage(catalog.downloaded(server.id),null)
                else catalog.browse(server.id,parents.lastOrNull()?.ref,query=submittedQuery,cancel=cancel)
            }
            cancel.checkActive()
            page=loaded
        }
    }

    fun saveSource(source:SourceKind) {
        val input=JSONObject().put("url",address.trim()).put("allowLanHttp",lan)
            .put("username",username.trim()).put("password",password)
        if(apiKey.isNotBlank())input.put("apiKey",apiKey.trim())
        if(legacyPassword)input.put("authMode","password")
        if(domain.isNotBlank())input.put("domain",domain.trim())
        if(guest)input.put("allowGuest",true)
        val displayName=name.trim()
        val previous=editing
        run("Could not connect. Check the address, credentials and server settings.") {cancel ->
            val saved=withContext(Dispatchers.IO) {
                var label=displayName
                val config=when(source) {
                    SourceKind.HTTP-> {
                        val clean=JSONObject().put("url",input.getString("url")).put("allowLanHttp",input.getBoolean("allowLanHttp"))
                        val candidate=SourceConnection("",source,"",clean,true)
                        val child=CancelScope()
                        val unlink=cancel.watch {child.cancel()}
                        try {RangeReader(clean.getString("url"),emptyMap(),candidate.networkPolicy(),child).use {check(it.length>0)}}
                        finally {unlink()}
                        clean
                    }
                    SourceKind.UPNP-> {
                        val clean=JSONObject().put("url",input.getString("url")).put("allowLanHttp",input.getBoolean("allowLanHttp"))
                        val client=UpnpClient()
                        val candidate=SourceConnection("",source,"",clean,true)
                        val info=client.describe(candidate,cancel)
                        if(label.isBlank())label=info.name
                        clean.put("serverId",info.serverId).put("titleSearch",client.supportsTitleSearch(candidate,info,cancel))
                    }
                    SourceKind.JELLYFIN->JellyfinClient.authenticate(input,cancel)
                    SourceKind.SUBSONIC->SubsonicClient.authenticate(input,cancel)
                    SourceKind.WEBDAV->WebDavClient.validate(input,cancel)
                    SourceKind.SMB->SmbClient.validate(input,cancel)
                    SourceKind.RADIO->RadioClient.validate(input,cancel)
                    else->error("Unsupported source")
                }
                cancel.checkActive()
                val id=previous?.id ?: UUID.randomUUID().toString()
                val result=store.saveConnection(source,label.ifBlank {source.label}.filterNot(Char::isISOControl).take(120),config,id)
                if(source==SourceKind.HTTP)store.saveItem(result.id,"audio",result.name)
                result
            }
            password="";apiKey="";kind=null;editing=null
            open(saved)
        }
    }

    fun signInPlex()=run("Plex sign-in did not complete. Open the browser and try again.") {cancel ->
        val auth=PlexAuth()
        val clientId=editing?.config?.optString("clientId")?.takeIf(String::isNotBlank) ?: UUID.randomUUID().toString()
        val allowLan=lan
        val pin=withContext(Dispatchers.IO){auth.createPin(clientId,cancel)}
        context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(PlexAuth.browserUrl(clientId,pin))))
        val selection=withTimeout(10*60*1000L) {
            var token:String?=null
            while(token==null) {
                cancel.checkActive();delay(1100)
                token=withContext(Dispatchers.IO){auth.pollPin(pin,clientId,cancel)}
            }
            withContext(Dispatchers.IO) {
                val account=auth.account(clientId,checkNotNull(token),cancel)
                PlexSelection(clientId,account,auth.servers(clientId,account,cancel,allowLan),allowLan)
            }
        }
        cancel.checkActive()
        plex=selection
        if(selection.servers.isEmpty())message="Your Plex account has no reachable music servers."
    }

    val server=connection
    val selectedKind=kind
    val selectedPlex=plex
    val selectedPlexServer=plexServer
    val busy=operation!=null
    val title=when {
        menu!=null->menu!!.title
        selectedPlexServer!=null->selectedPlexServer.name
        selectedPlex!=null->"Choose a Plex server"
        selectedKind!=null->selectedKind.label
        showDownloads->"Offline music"
        submittedQuery!=null->"Search results"
        parents.isNotEmpty()->parents.last().title
        server!=null->server.name
        else->"Sources"
    }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(bottom=24.dp+bottomInset),
        verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {ScreenHeader(title,::back)}
        message?.let {copy->item {Text(copy,style=EvannaDesign.typography.bodyMedium)}}
        if(busy)item {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.weight(1f)){ActivityIndicator(Modifier.fillMaxWidth())}
                TextButton(onClick={operation?.cancel()}){Text("Cancel")}
            }
        }
        when {
            menu!=null-> {
                val song=menu!!
                val owner=server ?: return@LazyColumn
                item {
                    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        listOf("Play" to PlaybackService.SELECT_SOURCE,"Play next" to PlaybackService.PLAY_NEXT_SOURCE,
                            "Add to queue" to PlaybackService.ENQUEUE_SOURCE).forEach {(label,action)->
                            SettingsLink(label) {onPlay(song.ref,action) {success->
                                if(success){menu=null;if(action!=PlaybackService.SELECT_SOURCE)message="Added to queue."}
                                else message="This track could not start. Check your server and audio output."
                            }}
                        }
                        if(owner.kind!=SourceKind.RADIO) {
                            if(song.offlineFile!=null)TextButton(enabled=!busy,onClick={
                                run("Could not remove this download.") {
                                    withContext(Dispatchers.IO){catalog.removeDownload(song.ref)}
                                    menu=null;request++;message="Offline copy removed."
                                }
                            }){Text("Remove offline copy")}
                            else TextButton(enabled=!busy && owner.enabled,onClick={
                                run("Download failed. Check the server and available storage.") {cancel ->
                                    withContext(Dispatchers.IO){catalog.download(song.ref,cancel)}
                                    menu=null;request++;message="Saved offline."
                                }
                            }){Text("Save offline")}
                        }
                    }
                }
            }
            selectedPlexServer!=null && selectedPlex!=null-> {
                item {Text("Choose the address your phone can reach.",style=EvannaDesign.typography.bodyMedium)}
                items(selectedPlexServer.connections) {url->
                    SettingsLink(Uri.parse(url).host ?: "Server address") {
                        run("Could not verify this Plex server. Try its other address.") {cancel ->
                            val saved=withContext(Dispatchers.IO) {
                                val config=PlexClient().selectServer(selectedPlexServer,url,selectedPlex.account,
                                    selectedPlex.clientId,selectedPlex.allowLanHttp,cancel)
                                cancel.checkActive()
                                store.saveConnection(SourceKind.PLEX,selectedPlexServer.name,config,editing?.id ?: UUID.randomUUID().toString())
                            }
                            plex=null;plexServer=null;kind=null;editing=null;open(saved)
                        }
                    }
                }
            }
            selectedPlex!=null->items(selectedPlex.servers,key={it.id}) {selected->
                SettingsLink(selected.name) {if(!busy)plexServer=selected}
            }
            selectedKind!=null-> {
                val needsCredentials=selectedKind in listOf(SourceKind.JELLYFIN,SourceKind.SUBSONIC,SourceKind.WEBDAV,SourceKind.SMB)
                item {
                    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        if(selectedKind==SourceKind.PLEX) {
                            Text("Sign in on Plex’s website to choose a music server you can access.")
                        } else {
                            OutlinedTextField(name,{name=it.take(120)},"Name",Modifier.fillMaxWidth(),enabled=!busy)
                            OutlinedTextField(address,{address=it.take(8192)},when(selectedKind){
                                SourceKind.UPNP->"Device description URL"
                                SourceKind.HTTP->"Audio file URL"
                                SourceKind.SMB->"smb://server/share"
                                SourceKind.RADIO->"Station or playlist URL"
                                else->"Server URL"
                            },Modifier.fillMaxWidth(),enabled=!busy)
                        }
                        if(needsCredentials) {
                            OutlinedTextField(username,{username=it.take(256)},"Username",Modifier.fillMaxWidth(),enabled=!busy)
                            OutlinedTextField(password,{password=it.take(4096)},"Password",Modifier.fillMaxWidth(),enabled=!busy,secret=true)
                        }
                        if(selectedKind==SourceKind.SUBSONIC) {
                            OutlinedTextField(apiKey,{apiKey=it.take(512)},"API key (optional)",Modifier.fillMaxWidth(),enabled=!busy,secret=true)
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text("Use legacy password authentication",Modifier.weight(1f))
                                Switch(legacyPassword,{legacyPassword=it},enabled=!busy)
                            }
                        }
                        if(selectedKind==SourceKind.SMB) {
                            OutlinedTextField(domain,{domain=it.take(256)},"Domain (optional)",Modifier.fillMaxWidth(),enabled=!busy)
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text("Connect as guest",Modifier.weight(1f));Switch(guest,{guest=it},enabled=!busy)
                            }
                        } else {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Text("Allow HTTP on this local network",Modifier.weight(1f))
                                Switch(lan,{lan=it},enabled=!busy)
                            }
                            if(lan)Text("Local HTTP sends data without encryption. Use it only with a server and network you trust.",
                                style=EvannaDesign.typography.bodySmall)
                        }
                        if(selectedKind==SourceKind.PLEX)Button(enabled=!busy,onClick={signInPlex()}){Text("Sign in with Plex")}
                        else Button(enabled=!busy && address.isNotBlank(),onClick={saveSource(selectedKind)}){Text("Connect")}
                        if(selectedKind==SourceKind.UPNP)OutlinedButton(enabled=!busy,onClick={
                            run("No local scan could run. Check Wi-Fi, or enter the device URL.") {cancel ->
                                discovery=withContext(Dispatchers.IO){UpnpDiscovery.discover(context,cancel)}
                                if(discovery.isEmpty())message="No media servers replied. You can still enter a device URL."
                            }
                        }){Text("Find servers on Wi-Fi")}
                    }
                }
                items(discovery,key={it.serverId}) {found->
                    SettingsLink(Uri.parse(found.location).host ?: "Media server","Use this device") {
                        if(!busy) {
                            address=found.location
                            if(address.startsWith("http:") && !lan)message="This server needs local HTTP. Allow it below if you trust this network."
                        }
                    }
                }
            }
            server==null-> {
                items(connections,key={it.id}) {saved->
                    SettingsLink(saved.name,if(saved.enabled)saved.kind.label else "${saved.kind.label} · Disconnected") {open(saved)}
                }
                item {Text("Add source",style=EvannaDesign.typography.titleMedium)}
                items(listOf(SourceKind.UPNP,SourceKind.PLEX,SourceKind.JELLYFIN,SourceKind.SUBSONIC,
                    SourceKind.WEBDAV,SourceKind.SMB,SourceKind.HTTP,SourceKind.RADIO)) {source->
                    SettingsLink(source.label) {setup(source)}
                }
            }
            else-> {
                val hasSearch=when(server.kind) {
                    SourceKind.PLEX->parents.firstOrNull()?.details?.optString("type")=="section"
                    SourceKind.JELLYFIN,SourceKind.SUBSONIC->parents.isEmpty()
                    SourceKind.UPNP->parents.isEmpty() && server.config.optBoolean("titleSearch")
                    else->false
                }
                if(parents.isEmpty() && submittedQuery==null)item {
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        if(server.enabled)TextButton(enabled=!busy,onClick={request++}){Text("Refresh")}
                        TextButton(enabled=!busy,onClick={setup(server.kind,server)}){Text("Reconnect")}
                        if(server.enabled)TextButton(enabled=!busy,onClick={forgetting=server}){Text("Disconnect")}
                    }
                }
                if(server.enabled && !showDownloads && parents.isEmpty() && submittedQuery==null && server.kind!=SourceKind.RADIO)item {
                    TextButton(enabled=!busy,onClick={showDownloads=true;page=CatalogPage(emptyList(),null);request++}){Text("Offline music")}
                }
                if(server.enabled && !showDownloads && hasSearch)item {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        SearchField(query,{query=it.take(200)},"Search songs",Modifier.weight(1f))
                        TextButton(enabled=!busy && query.isNotBlank(),onClick={submittedQuery=query.trim();page=CatalogPage(emptyList(),null);request++}){Text("Search")}
                    }
                }
                if(!server.enabled)item {Text("Reconnect to browse the server. Offline copies appear below.")}
                if(!busy && page.items.isEmpty() && message==null)item {Text(if(showDownloads)"No tracks saved offline." else "No music in this folder.")}
                items(page.items.distinctBy {it.ref},key={it.ref.encoded}) {entry->
                    SettingsLink(entry.title,if(entry.folder)"Folder" else listOfNotNull(entry.artist,entry.album,
                        "Saved offline".takeIf {entry.offlineFile!=null}).joinToString(" · ").ifBlank {null}) {
                        if(!busy) {
                            if(entry.folder){parents=parents+entry;page=CatalogPage(emptyList(),null);request++}
                            else menu=entry
                        }
                    }
                }
                page.nextOffset?.let {offset->item {
                    TextButton(enabled=!busy,onClick={
                        run("Could not load more songs. Try again.") {cancel ->
                            val more=withContext(Dispatchers.IO){catalog.browse(server.id,parents.lastOrNull()?.ref,offset,submittedQuery,cancel)}
                            cancel.checkActive();page=CatalogPage(page.items+more.items,more.nextOffset)
                        }
                    }){Text("Load more")}
                }}
            }
        }
    }
    forgetting?.let {target->
        AlertDialog(onDismissRequest={forgetting=null},title={Text("Disconnect ${target.name}?")},
            text={Text("Evanna will remove this server’s saved sign-in. Your queues, playlists and offline copies stay on this phone.")},
            confirmButton={TextButton(onClick={
                forgetting=null
                run("Could not disconnect this source.") {
                    withContext(Dispatchers.IO){store.forgetConnection(target.id)}
                    connection=null;message="Source disconnected."
                }
            }){Text("Disconnect")}},
            dismissButton={TextButton(onClick={forgetting=null}){Text("Cancel")}})
    }
}
