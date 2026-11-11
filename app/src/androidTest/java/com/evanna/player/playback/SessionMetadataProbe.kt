package com.evanna.player.playback

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.media3.common.util.UnstableApi
import com.evanna.player.decoder.MusicTags
import java.io.File
import kotlinx.coroutines.runBlocking

/** Only named disposable databases, generated cover pixels and a separate silent owner. */
@UnstableApi
internal object SessionMetadataProbe {
    fun run(instrumentation:Instrumentation):String {
        val context=instrumentation.targetContext
        val tags=MusicTags("Album artist",3,12,2,3,2024,"Jazz","Composer",true)
        for(version in 1..2) {
            val name="session-metadata-v$version.db"
            val backup=File(context.noBackupFilesDir,"queue-migrations/$name-v$version.db")
            context.deleteDatabase(name);backup.delete()
            val legacy=object:SQLiteOpenHelper(context,name,null,version) {
                override fun onCreate(db:SQLiteDatabase) {
                    db.execSQL("CREATE TABLE session(singleton INTEGER PRIMARY KEY CHECK(singleton=1),current_index INTEGER NOT NULL,position_us INTEGER NOT NULL${if(version==2)",shuffle_enabled INTEGER NOT NULL DEFAULT 0,repeat_mode TEXT NOT NULL DEFAULT 'OFF'" else ""})")
                    db.execSQL("CREATE TABLE entries(sequence INTEGER PRIMARY KEY,id TEXT NOT NULL UNIQUE,library_id TEXT,file_uri TEXT,title TEXT NOT NULL,artist TEXT,album TEXT,duration_us INTEGER${if(version==2)",play_order INTEGER NOT NULL" else ""})")
                }
                override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int)=error("Unexpected legacy upgrade")
            }
            try {
                legacy.setWriteAheadLoggingEnabled(true)
                val db=legacy.writableDatabase
                db.execSQL(if(version==2)"INSERT INTO session VALUES(1,1,4321000,1,'QUEUE')" else "INSERT INTO session VALUES(1,1,4321000)")
                for(index in 0..1)db.execSQL("INSERT INTO entries VALUES($index,? ,?,NULL,?,'Artist','Album',10000000${if(version==2)",${1-index}" else ""})",
                    arrayOf("evanna:local:queue:migration-$index","a".repeat(64),"Song $index"))
                // Keep the old connection open: the upgrade/backup must include its committed WAL rows.
                var expected:SavedQueue?=null
                PlaybackQueueStore(context,name).use {store ->
                    val loaded=checkNotNull(store.load())
                    check(loaded.index==1 && loaded.positionUs==4_321_000L && loaded.items.size==2)
                    check(loaded.items.all {it.musicTags==MusicTags()})
                    check(loaded.shuffleEnabled==(version==2))
                    check(loaded.repeat==if(version==2)QueueRepeat.QUEUE else QueueRepeat.OFF)
                    check(loaded.playOrder==if(version==2)loaded.items.map {it.id}.reversed() else loaded.items.map {it.id})
                    check(store.readableDatabase.version==3)
                    SQLiteDatabase.openDatabase(backup.path,null,SQLiteDatabase.OPEN_READONLY).use {copy ->
                        check(copy.version==version)
                        check(copy.rawQuery("SELECT COUNT(*) FROM entries",null).use {it.moveToFirst();it.getInt(0)}==2)
                        check(copy.rawQuery("SELECT position_us FROM session",null).use {it.moveToFirst();it.getLong(0)}==4_321_000L)
                    }
                    expected=loaded.copy(items=loaded.items.map {it.copy(musicTags=tags)})
                    store.save(checkNotNull(expected));check(store.load()==expected)
                    store.writableDatabase.execSQL("CREATE TRIGGER refuse_metadata BEFORE INSERT ON entries WHEN NEW.sequence=1 BEGIN SELECT RAISE(ABORT,'test'); END")
                    check(runCatching {store.save(checkNotNull(expected).copy(positionUs=9_000_000))}.isFailure)
                    check(store.load()==expected)
                    store.writableDatabase.execSQL("DROP TRIGGER refuse_metadata")
                }
                PlaybackQueueStore(context,name).use {check(it.load()==expected)}
            }finally {legacy.close();context.deleteDatabase(name);backup.delete()}
        }
        val pixels=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
        pixels.eraseColor(0xff234567.toInt())
        val bytes=try {checkNotNull(artworkForSession(pixels))}finally {pixels.recycle()}
        check(bytes.size<=SESSION_ARTWORK_LIMIT)
        checkNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size)).also {check(it.width==512 && it.height==512);it.recycle()}
        val tooLarge=Bitmap.createBitmap(513,2,Bitmap.Config.ARGB_8888)
        try {check(artworkForSession(tooLarge)==null)}finally {tooLarge.recycle()}
        val owner=UsbSessionOwner(UsbPlaybackController {error("Output opened")},object:PlaybackFocus {
            override val held=false
            override fun request():Boolean=error("Focus requested")
            override fun abandon()=Unit
        })
        var player:EvannaPlayer?=null
        fun main(block:()->Unit)=instrumentation.runOnMainSync {block()}
        try {
            runBlocking {
                check(owner.enqueue(PlayerUiProbe.source("Metadata fixture").copy(musicTags=tags)))
                check(owner.enqueue(PlayerUiProbe.source("No-cover fixture")))
            }
            val first=owner.queue.value.items.first().id
            main {
                player=EvannaPlayer(owner)
                player!!.setCurrentArtwork(first,bytes)
                val item=checkNotNull(player!!.currentMediaItem);val metadata=item.mediaMetadata
                check(metadata.albumArtist==tags.albumArtist && metadata.trackNumber==3 && metadata.totalTrackCount==12)
                check(metadata.discNumber==2 && metadata.totalDiscCount==3 && metadata.recordingYear==2024)
                check(metadata.genre=="Jazz" && metadata.composer=="Composer")
                check(metadata.artworkData.contentEquals(bytes) && metadata.artworkUri==null && item.localConfiguration==null)
                check(player!!.getMediaItemAt(1).mediaMetadata.artworkData==null)
            }
            runBlocking {check(owner.selectQueue(owner.queue.value.items[1].id))}
            main {
                player!!.setCurrentArtwork(first,bytes)
                check(player!!.currentMediaItem?.mediaMetadata?.artworkData==null)
            }
            check(owner.snapshot.value.route==null)
        }finally {runBlocking {check(owner.shutdown())};main {player?.release()}}
        return "Disposable v1/v2 WAL-aware queue backup/migration, exact tags/order/position restore, failed-save rollback, bounded generated cover, current-only/stale artwork and opaque session metadata passed; no real queue, grant, output or playback changed."
    }
}
