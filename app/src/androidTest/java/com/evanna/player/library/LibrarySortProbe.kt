package com.evanna.player.library

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.evanna.player.decoder.MusicTags
import com.evanna.player.decoder.SortTags
import com.evanna.player.user.*
import java.io.File

/** Named fixture stores only: query/migration/settings checks never access user folders or playback. */
internal object LibrarySortProbe {
    fun run(instrumentation:Instrumentation):String {
        val context=instrumentation.targetContext;val name="sort-library-probe.db";val settings="sort-settings-probe.db"
        val backup=File(context.noBackupFilesDir,"library-migrations/sort-library-probe-v3-backup.db")
        val a="a".repeat(64);val b="b".repeat(64);val c="c".repeat(64)
        context.deleteDatabase(name);context.deleteDatabase(settings);backup.delete()
        val old=object:SQLiteOpenHelper(context,name,null,3) {
            override fun onCreate(db:SQLiteDatabase){librarySchema(3).forEach(db::execSQL)}
            override fun onUpgrade(db:SQLiteDatabase,from:Int,to:Int)=error("Unexpected upgrade")
        }
        var clock=200L
        try {
            old.setWriteAheadLoggingEnabled(true)
            old.writableDatabase.execSQL("INSERT INTO roots VALUES('r','Music','READY',NULL,1)")
            old.writableDatabase.execSQL("INSERT INTO tracks(id,title,tags_version,added_at) VALUES(?,'The Apple',2,111)",arrayOf(a))
            old.writableDatabase.execSQL("INSERT INTO files VALUES('r',?,'a','content://fixture/a','a.flac',10,20,'old',1,1)",arrayOf(a))
            // A conflicting future index injects failure after the real ALTERs, without a production test hook.
            old.writableDatabase.execSQL("CREATE INDEX tracks_by_sort_title ON tracks(id)")
            check(runCatching {LibraryDatabase(context,name){clock}.use {it.writableDatabase}}.isFailure)
            check(old.writableDatabase.version==3)
            check(old.writableDatabase.rawQuery("PRAGMA table_info(tracks)",null).use {cursor ->
                var sort=false;while(cursor.moveToNext())if(cursor.getString(1)=="sort_title")sort=true;!sort
            })
            old.writableDatabase.execSQL("DROP INDEX tracks_by_sort_title")
            LibraryDatabase(context,name){clock}.use {store ->
                check(store.song(a)?.addedAt==111L)
                check(store.rootsNeedingTagRefresh().single().uri=="r")
                SQLiteDatabase.openDatabase(backup.path,null,SQLiteDatabase.OPEN_READONLY).use {copy ->
                    check(copy.version==3)
                    check(copy.rawQuery("SELECT added_at FROM tracks",null).use {it.moveToFirst() && it.getLong(0)==111L})
                }
                fun document(id:String)=LibraryDocument(id,id,"content://fixture/$id","$id.flac","audio/flac",10,20)
                val first=LibraryAudio("The Apple","The Zed","The Later",3000,"FLAC",48000,24,2,
                    MusicTags(albumArtist="The Orchestra",trackNumber=2,year=2020),SortTags("Apple","Zed","Later","Orchestra"))
                val second=LibraryAudio("Banana","Artist Z","Old",1000,"FLAC",48000,24,2,
                    MusicTags(albumArtist="Band",trackNumber=1,year=1990),SortTags("Banana","Alpha","Aardvark","Alpha"))
                store.begin("r","scan");store.record("r","scan",document(a),first)
                clock=400;store.record("r","scan",document(b),second)
                clock=500;store.record("r","scan",document(c),LibraryAudio("No names",null,null,null,"FLAC",48000,24,2))
                store.finish("r","scan")
                fun ids(sort:LibrarySort)=store.page(filter=LibraryFilter(sort=sort)).songs.map {it.id}
                check(ids(LibrarySort.TITLE)==listOf(a,b,c));check(ids(LibrarySort.ARTIST)==listOf(b,a,c))
                check(ids(LibrarySort.ALBUM)==listOf(b,a,c));check(ids(LibrarySort.DURATION)==listOf(b,a,c))
                check(ids(LibrarySort.ADDED)==listOf(c,b,a));check(store.song(a)?.title=="The Apple")
                check(store.cached("r",a)?.audio?.sortTags==first.sortTags)
                check(store.queueWindow(LibraryFilter(sort=LibrarySort.ARTIST),b)?.songs?.map {it.id}==listOf(b,a,c))
                check(store.groups(LibraryGroupKind.ARTIST).groups.map {it.value}==listOf(null,"Artist Z","The Zed"))
                fun albums(sort:LibrarySort)=store.groups(LibraryGroupKind.ALBUM,sort=sort).groups.map {it.value}
                check(albums(LibrarySort.TITLE)==listOf(null,"Old","The Later"))
                check(albums(LibrarySort.ARTIST)==listOf("Old","The Later",null))
                check(albums(LibrarySort.YEAR)==listOf("Old","The Later",null))
                check(albums(LibrarySort.ADDED)==listOf(null,"Old","The Later"))
                check(store.groups(LibraryGroupKind.ALBUM,limit=1,offset=1,sort=LibrarySort.YEAR).groups.single().value=="The Later")
                check(store.rootsNeedingTagRefresh().isEmpty())
            }
            LibraryDatabase(context,name){clock}.use {check(it.page(filter=LibraryFilter(sort=LibrarySort.ARTIST)).songs.map {song->song.id}==listOf(b,a,c))}
            UserDatabase(context,settings).use {store ->
                check(store.librarySort(LibrarySortScope.SONGS)==LibrarySort.TITLE)
                check(store.saveLibrarySort(LibrarySortScope.SONGS,LibrarySort.ARTIST))
                check(store.saveLibrarySort(LibrarySortScope.ALBUMS,LibrarySort.YEAR))
                check(store.saveLibrarySort(LibrarySortScope.SEARCH,LibrarySort.DURATION))
                check(!store.saveLibrarySort(LibrarySortScope.SONGS,LibrarySort.YEAR))
                store.writableDatabase.execSQL("CREATE TRIGGER refuse_sort BEFORE INSERT ON metadata WHEN NEW.key='library_sort:SONGS' BEGIN SELECT RAISE(ABORT,'test'); END")
                check(!store.saveLibrarySort(LibrarySortScope.SONGS,LibrarySort.DURATION))
                check(store.librarySort(LibrarySortScope.SONGS)==LibrarySort.ARTIST)
                store.writableDatabase.execSQL("DROP TRIGGER refuse_sort")
                store.writableDatabase.execSQL("INSERT INTO metadata VALUES('library_sort:FOLDERS','TITLE; DROP')")
                check(store.librarySort(LibrarySortScope.FOLDERS)==LibrarySort.TITLE)
            }
            UserDatabase(context,settings).use {store ->
                check(store.librarySort(LibrarySortScope.SONGS)==LibrarySort.ARTIST)
                check(store.librarySort(LibrarySortScope.ALBUMS)==LibrarySort.YEAR)
                check(store.librarySort(LibrarySortScope.SEARCH)==LibrarySort.DURATION)
            }
            return "Private v3 WAL migration/rollback/backup preserves first-seen time; sort hints/order/cache/paging/queue order and scoped setting validation/save failure/reopen pass. No owner data or output changed."
        }finally {old.close();context.deleteDatabase(name);context.deleteDatabase(settings);backup.delete()}
    }
}
