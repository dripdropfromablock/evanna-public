package com.evanna.player.playback

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.evanna.player.user.*
import com.evanna.player.usb.DacIdentity
import java.io.File

/** Private fixture database only; no playback, grants or actual personal/library data. */
internal object PlaylistProbe {
    fun run(instrumentation:Instrumentation):String {
        val context=instrumentation.targetContext;val name="playlist-probe.db"
        val backup=File(context.noBackupFilesDir,"user-migrations/$name-v1.db")
        context.deleteDatabase(name);backup.delete()
        val identity=DacIdentity(1,2,"Private fixture")
        val legacy=object:SQLiteOpenHelper(context,name,null,1) {
            override fun onCreate(db:SQLiteDatabase){userSchema(1).forEach(db::execSQL)}
            override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int)=error("Unexpected upgrade")
        }
        try {
            legacy.setWriteAheadLoggingEnabled(true)
            legacy.writableDatabase.execSQL("INSERT INTO metadata VALUES('fixture','retained')")
            legacy.writableDatabase.execSQL("INSERT INTO dac_profiles VALUES(?,1,2,'Private fixture',96000,-20,-10,2)",arrayOf(identity.key))
            var id=0L;var expected:Playlist?=null
            val first=PlaylistEntry("a".repeat(64),"First","Artist","Album",123000000,"first.flac")
            val missing=PlaylistEntry(null,"Missing",fileName="missing.flac")
            UserDatabase(context,name).use {store ->
                check(store.profile(identity.key)==DacProfile(identity,96000,-20f,-10f,2f))
                SQLiteDatabase.openDatabase(backup.path,null,SQLiteDatabase.OPEN_READONLY).use {copy ->
                    check(copy.version==1)
                    check(copy.rawQuery("SELECT value FROM metadata WHERE key='fixture'",null).use {it.moveToFirst() && it.getString(0)=="retained"})
                    check(copy.rawQuery("SELECT max_rate FROM dac_profiles",null).use {it.moveToFirst() && it.getInt(0)==96000})
                }
                id=checkNotNull(store.savePlaylist(null,"List",listOf(first,missing,first)))
                val before=checkNotNull(store.playlist(id));check(before.items==listOf(first,missing,first))
                check(store.savePlaylist(id,"Changed",before.items.reversed(),before.revision)==id)
                expected=checkNotNull(store.playlist(id));check(expected!!.revision==before.revision+1)
                check(store.playlist(id,before.revision)==null)
                check(store.playlist(id,expected!!.revision)==expected)
                check(store.savePlaylist(id,"Stale",emptyList(),before.revision)==null)
                check(store.playlist(id)==expected)
                store.writableDatabase.execSQL("CREATE TRIGGER refuse_items BEFORE INSERT ON playlist_items WHEN NEW.position=1 BEGIN SELECT RAISE(ABORT,'test'); END")
                check(store.savePlaylist(id,"Lost",listOf(missing,first),expected!!.revision)==null)
                check(store.playlist(id)==expected)
                check(store.savePlaylist(null,"Refused new",listOf(first,missing))==null)
                check(store.playlists().size==1)
                store.writableDatabase.execSQL("DROP TRIGGER refuse_items")
                check(store.savePlaylist(null," ",emptyList())==null)
                check(store.savePlaylist(null,"Too big",List(1001){first})==null)
            }
            UserDatabase(context,name).use {store ->
                check(store.playlist(id)==expected)
                check(!store.deletePlaylist(id,1));check(store.deletePlaylist(id,expected!!.revision))
                check(store.playlists().isEmpty())
                check(store.readableDatabase.rawQuery("SELECT COUNT(*) FROM playlist_items",null).use {it.moveToFirst();it.getInt(0)}==0)
                check(store.profile(identity.key)==DacProfile(identity,96000,-20f,-10f,2f))
            }
            return "Private WAL profile migration/backup, playlist CRUD/order/duplicates/missing entries, stale-editor refusal, failed-write rollback, reopen and cascade delete passed. No user data or grants touched."
        }finally {legacy.close();context.deleteDatabase(name);backup.delete()}
    }
}
