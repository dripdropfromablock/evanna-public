package com.evanna.player.sources

import android.content.Context
import android.graphics.BitmapFactory
import com.evanna.player.decoder.NetworkAudioTrack
import com.evanna.player.decoder.ResolvedNetworkMedia
import com.evanna.player.network.CancelScope
import com.evanna.player.network.HttpAccess
import com.evanna.player.playback.QueueSource
import com.evanna.player.webmusic.WebStreams
import com.evanna.player.webmusic.SearchTrack
import com.evanna.player.webmusic.WEB_AUDIO_MAX_BYTES
import com.evanna.player.webmusic.httpsWebAddress
import com.evanna.player.network.HttpRangeSource
import com.evanna.player.network.NetworkPolicy
import com.evanna.player.sources.jellyfin.JellyfinClient
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class CatalogPage(val items:List<SourceItem>,val nextOffset:Int?)
internal const val SEARCH_CONNECTION_ID = "51365b37-51f5-4414-92b9-21ded92e6caa"

/** All source I/O runs off the main thread; saved queues carry references, never requests. */
internal class SourceCatalog(private val context:Context,val store:SourceStore=Sources.store(context)) {
    private val offline=OfflineMusic(java.io.File(context.noBackupFilesDir,"offline"),store)
    private val webCache=WebPlaybackCache(context,store)
    private fun client(kind:SourceKind):SourceClient=when(kind) {
        SourceKind.UPNP->com.evanna.player.sources.upnp.UpnpClient()
        SourceKind.PLEX->com.evanna.player.sources.plex.PlexClient()
        SourceKind.JELLYFIN->com.evanna.player.sources.jellyfin.JellyfinClient()
        SourceKind.SUBSONIC->com.evanna.player.sources.subsonic.SubsonicClient()
        SourceKind.WEBDAV->com.evanna.player.sources.webdav.WebDavClient()
        SourceKind.SMB->com.evanna.player.sources.smb.SmbClient()
        SourceKind.RADIO->com.evanna.player.sources.radio.RadioClient()
        else->throw IOException("SOURCE_TYPE_UNAVAILABLE")
    }
    fun browse(id:String,parent:SourceReference?,offset:Int=0,query:String?=null,cancel:CancelScope=CancelScope()):CatalogPage {
        require(offset in 0..1_000_000 && (query?.length ?: 0)<=200)
        val connection=activeConnection(id)
        val folder=parent?.let {require(it.connectionId==id);checkNotNull(store.item(it)).also {require(it.folder)}}
        if(connection.kind==SourceKind.HTTP) {
            return CatalogPage(if(offset==0)store.items(id).filter {query.isNullOrBlank() || it.title.contains(query,true)}else emptyList(),null)
        }
        val page=client(connection.kind).browse(connection,folder,offset,query,cancel)
        return storePage(id, folder?.details?.getString("_key").orEmpty(), offset, page, cancel)
    }

    fun searchJellyfin(id: String, query: String, cancel: CancelScope): CatalogPage {
        val connection = activeConnection(id)
        require(connection.kind == SourceKind.JELLYFIN && query.isNotBlank())
        return storePage(id, "", 0, JellyfinClient().searchMusic(connection, query.take(200), cancel), cancel)
    }

    fun jellyfinAlbumTracks(id: String, albumId: String, offset: Int, cancel: CancelScope): CatalogPage {
        val connection = activeConnection(id)
        require(connection.kind == SourceKind.JELLYFIN)
        return storePage(id, "MusicAlbum:$albumId", offset, JellyfinClient().albumTracks(connection, albumId, offset, cancel), cancel)
    }

    private fun storePage(id: String, parentKey: String, offset: Int, page: SourcePage, cancel: CancelScope): CatalogPage {
        require(page.entries.size<=1000 && (page.nextOffset==null || page.nextOffset>offset))
        val items=page.entries.map {entry ->
            cancel.checkActive()
            fun text(value:String?,fallback:String?=null)=value?.filterNot(Char::isISOControl)?.take(512)?.takeIf {it.isNotBlank()} ?: fallback
            store.saveItem(id,entry.key,text(entry.title,"Untitled")!!,text(entry.artist),text(entry.album),
                entry.durationUs?.takeIf {it>=0},entry.folder,parentKey,entry.details)
        }
        return CatalogPage(items,page.nextOffset)
    }
    fun activeConnection(id:String)=store.connection(id)?.takeIf {it.enabled} ?: throw IOException("CONNECTION_LOGIN_REQUIRED")
    fun searchPlaybackId(track: SearchTrack): String {
        track.libraryId?.let { return it }
        track.sourceRef?.let { return it.personalId }
        val address = track.streamUrl?.let(::httpsWebAddress) ?: throw IOException("SOURCE_UNAVAILABLE")
        NetworkPolicy(address.toString(), requirePublic = true)
        if (store.connection(SEARCH_CONNECTION_ID) == null)
            store.saveConnection(SourceKind.WEB, "Music search", org.json.JSONObject(), SEARCH_CONNECTION_ID)
        return store.saveItem(SEARCH_CONNECTION_ID, "${track.provider}:${track.id}", track.title, track.artist,
            track.album, track.durationMs?.times(1000), details = org.json.JSONObject().put("url", address.toString())).ref.personalId
    }

    fun available(ref:SourceReference):Boolean {
        if(WebStreams.owns(ref))return WebStreams[ref]!=null
        val item=store.item(ref) ?: return false
        if(ref.connectionId==WEB_PLAYBACK_CONNECTION_ID)return webCache.available(item)
        return offline.file(item)!=null ||
            store.connection(ref.connectionId)?.enabled==true
    }
    fun resolve(ref:SourceReference,cancel:CancelScope,preferOffline:Boolean=true):ResolvedNetworkMedia {
        cancel.checkActive()
        if(WebStreams.owns(ref))return WebStreams[ref]?.media ?: throw IOException("WEB_LINK_EXPIRED")
        val item=store.item(ref)?.takeUnless {it.folder} ?: throw IOException("SOURCE_UNAVAILABLE")
        if(ref.connectionId==WEB_PLAYBACK_CONNECTION_ID)return webCache.media(item)
        if (ref.connectionId == SEARCH_CONNECTION_ID) {
            val url = item.details.getString("url")
            val policy = NetworkPolicy(url, requirePublic = true)
            return ResolvedNetworkMedia { app, scope -> HttpRangeSource(app, url, emptyMap(), policy, scope, WEB_AUDIO_MAX_BYTES) }
        }
        if(preferOffline)offline.media(item)?.let {return it}
        val connection=activeConnection(ref.connectionId)
        return if(connection.kind==SourceKind.HTTP)
            ResolvedNetworkMedia(item.details.optString("url").ifBlank {connection.config.getString("url")},emptyMap(),connection.networkPolicy())
        else client(connection.kind).resolve(connection,item,cancel)
    }
    fun download(ref:SourceReference,cancel:CancelScope) {
        require(activeConnection(ref.connectionId).kind !in setOf(SourceKind.RADIO,SourceKind.WEB))
        offline.save(context,ref,cancel){resolve(ref,cancel,preferOffline=false)}
    }
    fun removeDownload(ref:SourceReference)=offline.remove(ref)
    fun downloaded(connectionId:String)=store.offlineItems(connectionId).filter {offline.file(it)!=null}
    fun queueSource(ref:SourceReference):QueueSource {
        if(WebStreams.owns(ref))return WebStreams[ref]?.source ?: throw IOException("WEB_LINK_EXPIRED")
        val item=store.item(ref)?.takeUnless {it.folder} ?: throw IOException("SOURCE_UNAVAILABLE")
        val radio=store.connection(ref.connectionId)?.kind==SourceKind.RADIO
        fun prepare(cancel:CancelScope):com.evanna.player.playback.PcmTrack = if(radio)
            com.evanna.player.decoder.RadioAudioTrack.prepare(ref.personalId,cancel){resolve(ref,it)}
        else NetworkAudioTrack.prepare(context,ref.personalId,cancel,fastRangeSeek=ref.connectionId==SEARCH_CONNECTION_ID){resolve(ref,it)}
        return QueueSource(null,item.title,item.artist,item.album,item.durationUs,sourceRef=ref,prepare=::prepare) {prepare(CancelScope())}
    }
    fun artwork(ref:SourceReference,cancel:CancelScope):ByteArray? {
        val item=store.item(ref) ?: return null
        val connection=activeConnection(ref.connectionId)
        val privateBytes=when(connection.kind) {
            SourceKind.PLEX->com.evanna.player.sources.plex.PlexClient().artwork(connection,item,cancel)
            SourceKind.SUBSONIC->com.evanna.player.sources.subsonic.SubsonicClient().artwork(connection,item,cancel)
            else->null
        }
        if(privateBytes!=null)return boundedArtwork(privateBytes)
        val address=item.details.optString("artwork").takeIf {it.isNotBlank()}?.toHttpUrl() ?: return null
        val media=resolve(ref,cancel)
        val policy=media.policy ?: return null
        val headers=media.headers.takeIf {policy.sameOrigin(address)} ?: emptyMap()
        val response=HttpAccess(policy).fetch(address.toString(),300*1024,headers,scope=cancel)
        if(response.code!=200 || !response.headers["Content-Type"].orEmpty().startsWith("image/"))return null
        return boundedArtwork(response.bytes)
    }
    private fun boundedArtwork(bytes:ByteArray):ByteArray? {
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        if(bounds.outWidth !in 1..8192 || bounds.outHeight !in 1..8192)return null
        bounds.inJustDecodeBounds=false
        while(maxOf(bounds.outWidth,bounds.outHeight)/bounds.inSampleSize.coerceAtLeast(1)>512)
            bounds.inSampleSize=bounds.inSampleSize.coerceAtLeast(1)*2
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)?.let {bitmap ->
            try {com.evanna.player.playback.artworkForSession(bitmap)}finally {bitmap.recycle()}
        }
    }
}
