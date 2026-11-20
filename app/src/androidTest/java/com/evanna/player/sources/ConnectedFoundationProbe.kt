package com.evanna.player.sources

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.evanna.player.playback.*
import org.json.JSONObject
import com.evanna.player.decoder.NetworkAudioTrack
import com.evanna.player.decoder.ResolvedNetworkMedia
import com.evanna.player.decoder.NetworkFixtureServer
import com.evanna.player.network.CancelScope
import com.evanna.player.network.NetworkPolicy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.os.ParcelFileDescriptor
import com.evanna.player.decoder.NativeDecoder
import java.io.File
import com.evanna.player.user.*

internal object ConnectedFoundationProbe {
    fun run(instrumentation:Instrumentation):String {
        val context=instrumentation.targetContext
        val queueName="connected-migration-probe.db";val sourcesName="connected-source-probe.db"
        val usersName="connected-personal-probe.db"
        val offlineDirectory=File(context.cacheDir,"offline-probe-${java.util.UUID.randomUUID()}")
        context.deleteDatabase(queueName);context.deleteDatabase(sourcesName);context.deleteDatabase(usersName)
        try {
            object:SQLiteOpenHelper(context,queueName,null,3) {
                override fun onCreate(db:SQLiteDatabase) {
                    db.execSQL("CREATE TABLE session(singleton INTEGER PRIMARY KEY CHECK(singleton=1),current_index INTEGER NOT NULL,position_us INTEGER NOT NULL,shuffle_enabled INTEGER NOT NULL DEFAULT 0,repeat_mode TEXT NOT NULL DEFAULT 'OFF')")
                    db.execSQL("CREATE TABLE entries(sequence INTEGER PRIMARY KEY,id TEXT NOT NULL UNIQUE,library_id TEXT,file_uri TEXT,title TEXT NOT NULL,artist TEXT,album TEXT,duration_us INTEGER,play_order INTEGER NOT NULL,album_artist TEXT,track_no INTEGER,track_total INTEGER,disc_no INTEGER,disc_total INTEGER,year INTEGER,genre TEXT,composer TEXT,compilation INTEGER NOT NULL DEFAULT 0)")
                    db.execSQL("INSERT INTO session VALUES(1,0,123456,0,'OFF')")
                    db.execSQL("INSERT INTO entries(sequence,id,file_uri,title,play_order) VALUES(0,'evanna:local:queue:legacy','content://com.android.externalstorage.documents/document/primary%3Alegacy.flac','Legacy',0)")
                }
                override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int)=error("Unexpected upgrade")
            }.use {it.writableDatabase}
            val original=PlaybackQueueStore(context,queueName).use {it.load()!!}
            check(original.items.single().title=="Legacy" && original.positionUs==123456L)
            check(original.items.single().sourceRef==null && original.items.single().fileUri!!.startsWith("content://"))
            SourceStore(context,sourcesName).use {store ->
                val connection=store.saveConnection(SourceKind.HTTP,"Fixture",JSONObject().put("url","https://example.com/audio.flac").put("password","private-test-password"))
                val item=store.saveItem(connection.id,"server-item","Song",details=JSONObject().put("url","https://example.com/audio.flac?token=private-test-token"))
                val localId="a".repeat(64)
                val legacy=PlaylistEntry(localId,"Local track",fileName="local.flac")
                object:SQLiteOpenHelper(context,usersName,null,8) {
                    override fun onCreate(db:SQLiteDatabase) {
                        userSchema(8).forEach(db::execSQL)
                        db.execSQL("INSERT INTO playlists VALUES(1,'Legacy list',0,0,1)")
                        db.execSQL("INSERT INTO playlist_items(playlist_id,position,track_id,title,file_name) VALUES(1,0,?,?,?)",
                            arrayOf(localId,legacy.title,legacy.fileName))
                        db.execSQL("INSERT INTO favorites VALUES(?,1)",arrayOf(localId))
                    }
                    override fun onUpgrade(db:SQLiteDatabase,oldVersion:Int,newVersion:Int)=error("Unexpected upgrade")
                }.use {it.writableDatabase}
                UserDatabase(context,usersName).use {user ->
                    check(user.playlist(1)?.items==listOf(legacy) && user.isFavorite(localId))
                    val server=PlaylistEntry(null,"Server track",sourceRef=item.ref)
                    check(user.savePlaylist(1,"Legacy list",listOf(legacy,server,server),revision=1)==1L)
                    check(user.playlist(1)?.items==listOf(legacy,server,server))
                    check(user.setFavorite(item.ref.personalId,true) && user.isFavorite(item.ref.personalId))
                    check(user.saveHistory(listOf(PlayedSong("remote-play",item.ref.personalId,2))))
                    check(user.personalWindow(PersonalView.RECENT,item.ref.personalId)==listOf(item.ref.personalId))
                }
                val again=store.saveItem(connection.id,"server-item","Renamed",details=JSONObject())
                check(item.ref==again.ref)
                check(store.connection(connection.id)!!.config.getString("password")=="private-test-password")
                val encrypted=store.readableDatabase.rawQuery("SELECT config FROM connections WHERE id=?",arrayOf(connection.id)).use {it.moveToFirst();it.getBlob(0)}
                check(!encrypted.toString(Charsets.UTF_8).contains("private-test-password"))
                val second=store.saveConnection(SourceKind.HTTP,"Other",JSONObject().put("url","https://other.example/audio.flac"))
                val other=store.readableDatabase.rawQuery("SELECT config FROM connections WHERE id=?",arrayOf(second.id)).use {it.moveToFirst();it.getBlob(0)}
                store.writableDatabase.execSQL("UPDATE connections SET config=? WHERE id=?",arrayOf(other,connection.id))
                check(runCatching {store.connection(connection.id)}.isFailure){"Encrypted rows were swappable"}
                check(store.connections().any {it.id==second.id && it.enabled})
                store.writableDatabase.execSQL("UPDATE connections SET config=? WHERE id=?",arrayOf(encrypted,connection.id))
                val config=JSONObject().put("url","https://dav.example/music").put("username","test").put("password","secret")
                val dav=store.saveConnection(SourceKind.WEBDAV,"WebDAV",config)
                val oldTrack=store.saveItem(dav.id,"/album/track.flac","Unbrowsed track",parentId="/album")
                store.forgetConnection(dav.id)
                check(!store.connection(dav.id)!!.config.has("password"))
                store.saveConnection(dav.kind,dav.name,config,dav.id)
                check(store.item(oldTrack.ref)?.details?.getString("_key")=="/album/track.flac")
                val remote=SavedQueueEntry("evanna:local:queue:remote",null,null,"Song",null,null,1000000,sourceRef=item.ref)
                val duplicate=remote.copy(id="evanna:local:queue:duplicate")
                val mixed=original.copy(items=original.items+remote+duplicate,playOrder=original.playOrder+remote.id+duplicate.id)
                PlaybackQueueStore(context,queueName).use {it.save(mixed);check(it.load()==mixed)}
                val bytes=instrumentation.context.assets.open("decoder-seek/seek.flac").use {it.readBytes()}
                val offline=OfflineMusic(offlineDirectory,store)
                NetworkFixtureServer(bytes).use {server ->
                    val url=server.url.toString()
                    val media=ResolvedNetworkMedia(url,emptyMap(),NetworkPolicy(url,true,true))
                    val cancelled=CancelScope().apply {cancel()}
                    check(runCatching {offline.save(context,item.ref,cancelled){media}}.isFailure)
                    check(offline.file(checkNotNull(store.item(item.ref)))==null && offlineDirectory.list().orEmpty().isEmpty())
                    offline.save(context,item.ref,CancelScope()){media}
                    check(offline.file(checkNotNull(store.item(item.ref)))!!.readBytes().contentEquals(bytes))
                }
                store.forgetConnection(connection.id)
                check(!store.connection(connection.id)!!.enabled && store.item(item.ref)!=null)
                check(!store.connection(connection.id)!!.config.has("password"))
                checkNotNull(offline.media(checkNotNull(store.item(item.ref)))).open(context,CancelScope()).use {fd ->
                    offline.remove(item.ref)
                    NativeDecoder.open(fd.descriptor.fd).use {check(it.info.seekable)}
                }
                check(store.offlineItems(connection.id).isEmpty())
            }
            val bytes=instrumentation.context.assets.open("decoder-seek/seek.flac").use {it.readBytes()}
            NetworkFixtureServer(bytes).use {server ->
                val prepare=CancelScope()
                val track=NetworkAudioTrack.prepare(context,"evanna:network:probe",prepare) {
                    ResolvedNetworkMedia(server.url.toString(),emptyMap(),NetworkPolicy(server.url.toString(),true,true))
                }
                check(!prepare.cancelled){"Preparing one reader cancelled its parent operation"}
                track.open(CancelScope()).use {reader ->
                    val reference=File.createTempFile("source-reference-",".flac",context.cacheDir).apply {writeBytes(bytes)}
                    try {ParcelFileDescriptor.open(reference,ParcelFileDescriptor.MODE_READ_ONLY).use {fd ->
                        NativeDecoder.open(fd.fd).use {local ->
                            val buffer=ByteBuffer.allocateDirect(4096*2*4).order(ByteOrder.LITTLE_ENDIAN)
                            val expected=ByteBuffer.allocateDirect(buffer.capacity()).order(ByteOrder.LITTLE_ENDIAN)
                            fun compare():Int {
                                val count=reader.read(buffer,4096);check(local.readPcm(expected,4096)==count)
                                check(buffer.duplicate().apply {limit(count*2*4)}==expected.duplicate().apply {limit(count*2*4)}){"Network PCM changed"}
                                return count
                            }
                            check(compare()>0)
                            while(compare()>0){}
                            reader.seekTo(2000000);local.seekTo(2000000);check(compare()>0)
                        }
                    }}finally {check(reference.delete())}
                }
                track.close()
            }
            return "PASS: queue and playlist migrations preserve local rows; remote identities, favorites and history restore; encrypted rows resist swaps; offline download is byte-exact and remains readable after disconnect and unlink; native network PCM matches local decode through EOF and seek."
        } finally {
            context.deleteDatabase(queueName);context.deleteDatabase(sourcesName);context.deleteDatabase(usersName)
            offlineDirectory.listFiles().orEmpty().forEach {it.delete()}
            offlineDirectory.delete()
        }
    }
}
