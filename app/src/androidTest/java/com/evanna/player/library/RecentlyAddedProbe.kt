package com.evanna.player.library

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** Synthetic documents in a private fixture index; no real folder or provider is accessed. */
internal object RecentlyAddedProbe {
    fun run(instrumentation:Instrumentation):String {
        val context=instrumentation.targetContext;val name="added-library-probe.db"
        val backup=File(context.noBackupFilesDir,"library-migrations/added-library-probe-v2-backup.db")
        val a="a".repeat(64);val b="b".repeat(64)
        context.deleteDatabase(name);backup.delete()
        val old=object:SQLiteOpenHelper(context,name,null,2) {
            override fun onCreate(db:SQLiteDatabase){createLibraryV2(db)}
            override fun onUpgrade(db:SQLiteDatabase,from:Int,to:Int)=error("Unexpected upgrade")
        }
        var clock=1000L
        try {
            old.setWriteAheadLoggingEnabled(true)
            old.writableDatabase.execSQL("INSERT INTO roots VALUES('root','Music','READY',NULL,1)")
            old.writableDatabase.execSQL("INSERT INTO tracks(id,title,artist,album,genre,tags_version) VALUES(?,'Old','Artist','Album','Jazz',2)",arrayOf(a))
            old.writableDatabase.execSQL("INSERT INTO files VALUES('root',?,'old','content://fixture/a','a.flac',10,20,'old',1,1)",arrayOf(a))
            old.writableDatabase.execSQL("CREATE TRIGGER refuse_added BEFORE UPDATE ON tracks BEGIN SELECT RAISE(ABORT,'test'); END")
            check(runCatching {LibraryDatabase(context,name){clock}.use {it.writableDatabase}}.isFailure)
            check(old.writableDatabase.version==2)
            check(old.writableDatabase.rawQuery("PRAGMA table_info(tracks)",null).use {cursor ->
                var added=false;while(cursor.moveToNext())if(cursor.getString(1)=="added_at")added=true;!added
            })
            old.writableDatabase.execSQL("DROP TRIGGER refuse_added")
            LibraryDatabase(context,name){clock}.use {store ->
                check(store.song(a)?.addedAt==1000L);check(store.song(a)?.musicTags?.genre=="Jazz")
                SQLiteDatabase.openDatabase(backup.path,null,SQLiteDatabase.OPEN_READONLY).use {copy ->
                    check(copy.version==2)
                    check(copy.rawQuery("SELECT genre FROM tracks",null).use {it.moveToFirst() && it.getString(0)=="Jazz"})
                }
                fun document(key:String)=LibraryDocument(key,key,"content://fixture/$key","$key.flac","audio/flac",10,20)
                val audio=LibraryAudio("New","Artist","Album",1000000,"FLAC",48000,24,2)
                clock=2000;store.begin("root","scan");store.record("root","scan",document(b),audio)
                clock=3000;store.record("root","scan",document(a),audio.copy(title="Refreshed"));store.finish("root","scan")
                check(store.song(a)?.addedAt==1000L);check(store.song(b)?.addedAt==2000L)
                val filter=LibraryFilter(sort=LibrarySort.ADDED)
                check(store.page(filter=filter).songs.map {it.id}==listOf(b,a))
                check(store.queueWindow(filter,b)?.songs?.map {it.id}==listOf(b,a))
                store.addRoot("overlap","Other",false);store.begin("overlap","overlap")
                clock=4000;store.record("overlap","overlap",document(a),audio);store.finish("overlap","overlap")
                check(store.song(a)?.addedAt==1000L)
                store.begin("root","unreadable");store.record("root","unreadable",document(b),null);store.finish("root","unreadable")
                check(store.song(b)?.addedAt==2000L);check(store.song(b)?.available==false)
            }
            LibraryDatabase(context,name){clock}.use {store ->
                check(store.song(a)?.addedAt==1000L && store.song(b)?.addedAt==2000L)
                check(store.readableDatabase.version==LIBRARY_DB_VERSION)
            }
            return "Private v2 WAL migration/backup retains tags and rolls back/retries injected failure; first-seen dates survive updates, overlapping roots, unreadable files and reopen; Recently added and queue order agree. No owner files/index touched."
        }finally {old.close();context.deleteDatabase(name);backup.delete()}
    }
}
