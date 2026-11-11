package com.evanna.player.library

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import kotlinx.coroutines.*

/** No actual provider/grant operations: only a named disposable catalog and release journal. */
internal object FolderManagementProbe {
    fun run(instrumentation:Instrumentation):String {
        val context=instrumentation.targetContext;val name="folder-management-probe.db"
        val backup=File(context.noBackupFilesDir,"library-migrations/folder-management-probe-v4-backup.db")
        context.deleteDatabase(name);backup.delete()
        val old=object:SQLiteOpenHelper(context,name,null,4) {
            override fun onCreate(db:SQLiteDatabase){librarySchema(4).forEach(db::execSQL)}
            override fun onUpgrade(db:SQLiteDatabase,from:Int,to:Int)=error("Unexpected upgrade")
        }
        val a="a".repeat(64);val b="b".repeat(64);val c="c".repeat(64)
        try {
            old.setWriteAheadLoggingEnabled(true)
            val db=old.writableDatabase
            db.execSQL("INSERT INTO roots VALUES('r','Owned','READY',NULL,1),('b','Borrowed','READY',NULL,0)")
            for(id in listOf(a,b,c))db.execSQL("INSERT INTO tracks(id,title,added_at) VALUES(?,'Fixture',123)",arrayOf(id))
            for((root,id) in listOf("r" to a,"r" to b,"b" to b,"b" to c))db.execSQL("INSERT INTO files VALUES(?,?,?,'content://fixture/file','fixture.flac',1,1,'scan',1,1)",arrayOf(root,id,id))
            db.execSQL("CREATE TABLE excluded_folders(conflict TEXT)")
            check(runCatching {LibraryDatabase(context,name).use {it.writableDatabase}}.isFailure)
            check(db.version==4)
            check(db.rawQuery("SELECT COUNT(*) FROM files",null).use {it.moveToFirst();it.getInt(0)}==4)
            db.execSQL("DROP TABLE excluded_folders")
            LibraryDatabase(context,name).use {store ->
                check(store.readableDatabase.version==LIBRARY_DB_VERSION)
                SQLiteDatabase.openDatabase(backup.path,null,SQLiteDatabase.OPEN_READONLY).use {copy ->
                    check(copy.version==4);check(copy.rawQuery("SELECT COUNT(*) FROM files",null).use {it.moveToFirst();it.getInt(0)}==4)
                }
                check(store.roots().all {it.songCount==2});check(store.song(a)?.addedAt==123L)
                store.writableDatabase.execSQL("CREATE TRIGGER refuse_prune BEFORE DELETE ON files WHEN OLD.id='$a' BEGIN SELECT RAISE(ABORT,'test'); END")
                check(runCatching {store.excludeFolder("r","dir","Excluded",setOf(a))}.isFailure)
                check(store.excludedFolders("r").isEmpty() && store.song(a)!=null)
                check(runCatching {store.removeRoot("r")}.isFailure)
                check(store.pendingGrantReleases().isEmpty() && store.roots().size==2)
                store.writableDatabase.execSQL("DROP TRIGGER refuse_prune")
                store.excludeFolder("r","dir","Excluded",setOf(a))
                check(store.excludedFolders("r")==listOf(ExcludedFolder("dir","Excluded")))
                check(store.song(a)==null && store.song(b)!=null)
                check(store.roots().single {it.uri=="r"}.songCount==1)
                store.includeFolder("r","dir");check(store.excludedFolders("r").isEmpty())
                store.removeRoot("r");check(store.pendingGrantReleases()==listOf("r"))
                check(store.song(b)!=null && store.song(c)!=null)
                // Re-added access is retained, and inherits our original ownership before clearing the journal.
                store.addRoot("r","Readded",false);check(store.pendingGrantReleases().isEmpty())
                store.removeRoot("r");check(store.pendingGrantReleases()==listOf("r"))
                store.removeRoot("b");check(store.pendingGrantReleases()==listOf("r"))
                check(store.page().total==0)
            }
            LibraryDatabase(context,name).use {store -> check(store.pendingGrantReleases()==listOf("r"));store.acknowledgeGrantRelease("r");check(store.pendingGrantReleases().isEmpty())}
            val cleanupReport = if (context.packageName == "com.evanna.player.reviewvalidation") {
                val catalog = LocalLibrary.database(context)
                check(catalog.roots().isEmpty() && catalog.pendingGrantReleases().isEmpty())
                val revision = LocalLibrary.scansFinished
                repeat(3) {
                    var cleanup: Job? = null
                    instrumentation.runOnMainSync {
                        cleanup = LocalLibrary.cleanupRemovedFolders(context)
                        check(!LocalLibrary.scanning) { "No-op cleanup advertised a library scan" }
                        check(LocalLibrary.scansFinished == revision)
                    }
                    runBlocking { withTimeout(5000) { checkNotNull(cleanup).join() } }
                    check(!LocalLibrary.scanning && !LocalLibrary.managingFolders)
                    check(LocalLibrary.scansFinished == revision) { "No-op cleanup invalidated the analysis revision" }
                }
                "No-op cleanup kept scanning false and analysis revision unchanged across three opens."
            } else "No-op cleanup regression requires the isolated reviewvalidation application."
            return "Disposable v4 WAL backup/migration/rollback/retry, counts, overlaps, exclusions, owned/borrowed journal and re-add checks pass. $cleanupReport No real grants, files or owner state touched."
        }finally {old.close();context.deleteDatabase(name);backup.delete()}
    }
}
