package com.evanna.player.library

import android.app.Instrumentation
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** Only named fixture databases/backups; never opens library.db. */
internal object LibraryMigrationProbe {
    private val schema = listOf(
        "CREATE TABLE roots(uri TEXT PRIMARY KEY, name TEXT NOT NULL, state TEXT NOT NULL, token TEXT, owned INTEGER NOT NULL)",
        "CREATE TABLE tracks(id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT, album TEXT, duration INTEGER, codec TEXT, rate INTEGER, bits INTEGER, channels INTEGER)",
        "CREATE TABLE files(root TEXT NOT NULL REFERENCES roots(uri), id TEXT NOT NULL REFERENCES tracks(id), document_id TEXT NOT NULL, uri TEXT NOT NULL, name TEXT NOT NULL, size INTEGER, modified INTEGER, token TEXT NOT NULL, present INTEGER NOT NULL, readable INTEGER NOT NULL, PRIMARY KEY(root,id))",
        "CREATE INDEX files_by_id ON files(id)")

    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        val name = "album-migration-probe.db"
        val backup = File(context.noBackupFilesDir, "library-migrations/album-migration-probe-v1-backup.db")
        fun seed(): SQLiteDatabase {
            context.deleteDatabase(name)
            return context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).apply {
                schema.forEach(::execSQL); version = 1; enableWriteAheadLogging()
                execSQL("INSERT INTO roots VALUES('root','Music é','READY',NULL,1)")
                execSQL("INSERT INTO roots VALUES('overlap','Other','SCANNING','old',0)")
                execSQL("INSERT INTO tracks VALUES('id','Title ü','Artist','Album',1000,'FLAC',44100,24,2)")
                execSQL("INSERT INTO files VALUES('root','id','doc','content://fixture/one','song.flac',10,20,'old',1,1)")
                execSQL("INSERT INTO files VALUES('overlap','id','doc','content://fixture/two','song.flac',10,20,'old',0,0)")
            }
        }
        fun scalar(db: SQLiteDatabase, sql: String) = db.rawQuery(sql, null).use { check(it.moveToFirst()); it.getString(0) }
        try {
            seed().use { writer ->
                // Committed frames remain in WAL while this original connection stays open.
                LibraryDatabase(context, name).use { helper ->
                    val db = helper.writableDatabase
                    check(db.version == LIBRARY_DB_VERSION) { "Library did not upgrade" }
                    check(scalar(db, "SELECT COUNT(*) FROM files") == "2")
                    check(scalar(db, "SELECT title FROM tracks") == "Title ü")
                    check(scalar(db, "SELECT tags_version FROM tracks") == "1")
                    check(db.rawQuery("SELECT album_artist,track_no FROM tracks", null).use { it.moveToFirst() && it.isNull(0) && it.isNull(1) })
                    check(scalar(db, "SELECT state FROM roots WHERE uri='overlap'") == "PAUSED")
                    check(db.rawQuery("PRAGMA foreign_key_check", null).use { !it.moveToFirst() })
                }
                check(backup.isFile) { "Verified backup missing" }
                SQLiteDatabase.openDatabase(backup.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    check(db.version == 1)
                    check(scalar(db, "SELECT COUNT(*) FROM files") == "2")
                    check(scalar(db, "SELECT title FROM tracks") == "Title ü")
                    check(scalar(db, "SELECT state FROM roots WHERE uri='overlap'") == "SCANNING")
                    check(scalar(db, "PRAGMA quick_check") == "ok")
                }
                check(writer.version == LIBRARY_DB_VERSION)
            }
            val stamp = backup.lastModified()
            LibraryDatabase(context, name).use { check(it.readableDatabase.version == LIBRARY_DB_VERSION) }
            check(backup.lastModified() == stamp)
            // A schema failure after ALTER must roll back all changes and permit a real retry.
            seed().close()
            val throwing = object : SQLiteOpenHelper(context, name, null, 2) {
                override fun onCreate(db: SQLiteDatabase) = error("Existing v1 expected")
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
                    upgradeLibraryV1(db, backup)
                    error("Injected post-ALTER failure")
                }
            }
            throwing.use { check(runCatching { it.writableDatabase }.isFailure) }
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                check(db.version == 1 && scalar(db, "SELECT title FROM tracks") == "Title ü")
                check(db.rawQuery("PRAGMA table_info(tracks)", null).use { c ->
                    var found = false; while (c.moveToNext()) if (c.getString(1) == "album_artist") found = true; !found
                })
                val blocker = File(context.cacheDir, "album-backup-blocker").apply { writeText("not a directory") }
                db.beginTransaction()
                try {
                    val failure = runCatching { backupLibraryV1(db, File(blocker, "backup.db")) }.exceptionOrNull()
                    check(failure?.message == "LIBRARY_BACKUP_DIRECTORY_FAILED")
                    val directoryTarget = File(context.cacheDir, "album-backup-publish-blocker").apply { check(isDirectory || mkdirs()) }
                    try { check(runCatching { backupLibraryV1(db, directoryTarget) }.isFailure) }
                    finally { directoryTarget.delete() }
                    check(backup.isFile)
                } finally { db.endTransaction(); blocker.delete() }
                check(db.version == 1)
            }
            LibraryDatabase(context, name).use { check(it.writableDatabase.version == LIBRARY_DB_VERSION) }
            context.deleteDatabase(name); backup.delete()
            LibraryDatabase(context, name).use { check(it.writableDatabase.version == LIBRARY_DB_VERSION) }
            check(!backup.exists()) { "Fresh install must not need a v1 backup" }
            return "v1 WAL rows, metadata, overlapping/missing memberships and ownership preserved; backup independently readable; failed migration rolls back/retries; fresh/reopen paths pass; real library untouched."
        } finally { context.deleteDatabase(name); backup.delete() }
    }
}
