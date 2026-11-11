package com.evanna.player.playback

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.evanna.player.user.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** Fixture databases and a separate paused owner only; never opens an output or the real personal database. */
@UnstableApi
internal object FavoritesProbe {
    fun run(instrumentation:Instrumentation):String {
        val context=instrumentation.targetContext
        for(version in 1..2) {
            val name="favorites-v$version.db";val backup=File(context.noBackupFilesDir,"user-migrations/$name-v$version.db")
            context.deleteDatabase(name);backup.delete()
            val old=object:SQLiteOpenHelper(context,name,null,version) {
                override fun onCreate(db:SQLiteDatabase){userSchema(version).forEach(db::execSQL)}
                override fun onUpgrade(db:SQLiteDatabase,from:Int,to:Int)=error("Unexpected upgrade")
            }
            try {
                old.setWriteAheadLoggingEnabled(true)
                old.writableDatabase.execSQL("INSERT INTO metadata VALUES('fixture','kept')")
                if(version==2) {
                    old.writableDatabase.execSQL("INSERT INTO playlists VALUES(1,'Kept',1,1,1)")
                    old.writableDatabase.execSQL("INSERT INTO playlist_items VALUES(1,0,NULL,'Missing',NULL,NULL,NULL,NULL)")
                }
                val id="a".repeat(64)
                UserDatabase(context,name).use {store ->
                    check(!store.isFavorite(id));check(store.setFavorite(id,true));check(store.setFavorite(id,true))
                    check(store.favoriteIds()==listOf(id));check(store.favoriteWindow(id)==listOf(id))
                    check(store.favoriteWindow("b".repeat(64)).isEmpty());check(!store.setFavorite("content://private",true))
                    store.writableDatabase.execSQL("CREATE TRIGGER refuse_favorite BEFORE DELETE ON favorites BEGIN SELECT RAISE(ABORT,'test'); END")
                    check(!store.setFavorite(id,false));check(store.isFavorite(id));store.writableDatabase.execSQL("DROP TRIGGER refuse_favorite")
                    SQLiteDatabase.openDatabase(backup.path,null,SQLiteDatabase.OPEN_READONLY).use {copy ->
                        check(copy.version==version)
                        check(copy.rawQuery("SELECT value FROM metadata",null).use {it.moveToFirst() && it.getString(0)=="kept"})
                        if(version==2)check(copy.rawQuery("SELECT title FROM playlist_items",null).use {it.moveToFirst() && it.getString(0)=="Missing"})
                    }
                    if(version==2)check(store.playlist(1)?.items?.single()?.title=="Missing")
                }
                UserDatabase(context,name).use {store ->
                    check(store.isFavorite(id));check(store.setFavorite(id,false));check(store.favoriteIds().isEmpty())
                }
            }finally {old.close();context.deleteDatabase(name);backup.delete()}
        }
        val owner=UsbSessionOwner(UsbPlaybackController {error("No output allowed")},object:PlaybackFocus {
            override val held=false;override fun request()=false;override fun abandon()=Unit
        })
        fun source(name:String)=QueueSource("a".repeat(64),name,null,null,null) {object:PcmTrack {
            override val id=name;override val format=TrackPcmFormat(48000,16,2,16,"FLAC",true,1000)
            override fun open():PcmReader=error("No decode allowed");override fun close()=Unit
        }}
        var player:EvannaPlayer?=null
        try {
            runBlocking {check(owner.enqueue(source("First")));check(owner.enqueue(source("Second")))}
            val first=owner.queue.value.current!!.id;val second=owner.queue.value.items[1].id
            val favoriteCleared = CountDownLatch(1)
            instrumentation.runOnMainSync {
                val currentPlayer = EvannaPlayer(owner)
                player = currentPlayer
                currentPlayer.setCurrentFavorite(first, false)
                val before = currentPlayer.playlistMetadata
                currentPlayer.setCurrentFavorite(first, true)
                val after = currentPlayer.playlistMetadata
                check(after.extras?.getBoolean(EvannaPlayer.FAVORITE) == true)
                check(before != after) { "A favorite toggle must notify Media3 controllers even on the same track" }
                currentPlayer.addListener(object : Player.Listener {
                    override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) {
                        if (mediaMetadata.extras?.getBoolean(EvannaPlayer.FAVORITE_AVAILABLE) != true) {
                            favoriteCleared.countDown()
                        }
                    }
                })
            }
            runBlocking { check(owner.selectQueue(second)) }
            check(favoriteCleared.await(5, TimeUnit.SECONDS)) { "The old track's favorite did not clear" }
            instrumentation.runOnMainSync {
                player!!.setCurrentFavorite(first,false)
                check(player!!.playlistMetadata.extras?.getBoolean(EvannaPlayer.FAVORITE_AVAILABLE)!=true)
                player!!.setCurrentFavorite(second,false)
                check(player!!.playlistMetadata.extras?.getBoolean(EvannaPlayer.FAVORITE_AVAILABLE)==true)
                check(player!!.playlistMetadata.extras?.getBoolean(EvannaPlayer.FAVORITE)==false)
            }
        }finally {instrumentation.runOnMainSync {player?.release()};runBlocking {owner.close()}}
        return "Private personal migration/WAL backup, retained missing playlist, favorite idempotence/validation/rollback/reopen and current-only Media3 favorite state passed; no output or owner data changed."
    }
}
