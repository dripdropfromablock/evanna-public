package com.evanna.player.library

import android.database.sqlite.SQLiteDatabase
import java.io.File
import com.evanna.player.storage.backupSqliteSnapshot

internal const val LIBRARY_DB_VERSION=7

private val v1Schema = listOf(
    "CREATE TABLE roots(uri TEXT PRIMARY KEY, name TEXT NOT NULL, state TEXT NOT NULL, token TEXT, owned INTEGER NOT NULL)",
    "CREATE TABLE tracks(id TEXT PRIMARY KEY, title TEXT NOT NULL, artist TEXT, album TEXT, duration INTEGER, codec TEXT, rate INTEGER, bits INTEGER, channels INTEGER)",
    "CREATE TABLE files(root TEXT NOT NULL REFERENCES roots(uri), id TEXT NOT NULL REFERENCES tracks(id), document_id TEXT NOT NULL, uri TEXT NOT NULL, name TEXT NOT NULL, size INTEGER, modified INTEGER, token TEXT NOT NULL, present INTEGER NOT NULL, readable INTEGER NOT NULL, PRIMARY KEY(root,id))",
    "CREATE INDEX files_by_id ON files(id)")
private val tagColumns = listOf("album_artist TEXT", "track_no INTEGER", "track_total INTEGER", "disc_no INTEGER",
    "disc_total INTEGER", "year INTEGER", "genre TEXT", "composer TEXT", "compilation INTEGER NOT NULL DEFAULT 0",
    "tags_version INTEGER NOT NULL DEFAULT 1")

internal fun createLibraryV2(db: SQLiteDatabase) {
    v1Schema.forEach(db::execSQL)
    tagColumns.forEach { db.execSQL("ALTER TABLE tracks ADD COLUMN $it") }
}
internal fun librarySchema(version:Int):List<String> = v1Schema+
    (if(version>=2)tagColumns.map {"ALTER TABLE tracks ADD COLUMN $it"}else emptyList())+
    (if(version>=3)listOf("ALTER TABLE tracks ADD COLUMN added_at INTEGER NOT NULL DEFAULT 0",
        "CREATE INDEX tracks_by_added ON tracks(added_at DESC,id)")else emptyList())+
    (if(version>=4)listOf("ALTER TABLE tracks ADD COLUMN sort_title TEXT","ALTER TABLE tracks ADD COLUMN sort_artist TEXT",
        "ALTER TABLE tracks ADD COLUMN sort_album TEXT","ALTER TABLE tracks ADD COLUMN sort_album_artist TEXT",
        "CREATE INDEX tracks_by_sort_title ON tracks(COALESCE(NULLIF(sort_title,''),title) COLLATE NOCASE,id)",
        "CREATE INDEX tracks_by_sort_artist ON tracks(COALESCE(NULLIF(sort_artist,''),NULLIF(artist,'')) COLLATE NOCASE,id)",
        "CREATE INDEX tracks_by_sort_album ON tracks(COALESCE(NULLIF(sort_album,''),NULLIF(album,'')) COLLATE NOCASE,id)",
        "CREATE INDEX tracks_by_duration ON tracks(duration,id)")else emptyList())+
    (if(version>=5)listOf("CREATE TABLE excluded_folders(root TEXT NOT NULL REFERENCES roots(uri),id TEXT NOT NULL,name TEXT NOT NULL,PRIMARY KEY(root,id))",
        "CREATE TABLE pending_grant_releases(uri TEXT PRIMARY KEY)")else emptyList())+
    // CUE tracks are ordinary rows with an exact frame range of their file; a covered whole file is hidden.
    (if(version>=6)listOf("ALTER TABLE tracks ADD COLUMN start_frame INTEGER","ALTER TABLE tracks ADD COLUMN end_frame INTEGER",
        "ALTER TABLE tracks ADD COLUMN cue_hidden INTEGER NOT NULL DEFAULT 0")else emptyList())+
    if(version>=7)listOf("CREATE INDEX files_by_root_token ON files(root,token)")else emptyList()

internal fun upgradeLibraryV1(db: SQLiteDatabase, backup: File) {
    backupLibraryV1(db, backup)
    tagColumns.forEach { db.execSQL("ALTER TABLE tracks ADD COLUMN $it") }
}

/** The helper's upgrade transaction freezes the logical snapshot, including committed WAL frames. */
internal fun backupLibraryV1(source: SQLiteDatabase, destination: File) {
    check(source.inTransaction() && source.version == 1) { "LIBRARY_BACKUP_SNAPSHOT_REQUIRED" }
    try {backupSqliteSnapshot(source,destination,v1Schema,listOf("roots" to "uri","tracks" to "id","files" to "root,id"))}
    catch(failure:IllegalStateException) {
        val message=failure.message
        if(message?.startsWith("DATABASE_BACKUP_")==true)throw IllegalStateException(message.replaceFirst("DATABASE_","LIBRARY_"),failure)
        throw failure
    }
}
