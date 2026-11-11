package com.evanna.player.library

import android.app.Instrumentation
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Read-only fingerprints for upgrade checks; never reports tags, filenames or URIs. */
internal object LibraryStateProbe {
    fun run(instrumentation: Instrumentation): String {
        val context = instrumentation.targetContext
        fun rows(db: SQLiteDatabase, query: String): JSONObject {
            val digest = MessageDigest.getInstance("SHA-256"); var count = 0
            db.rawQuery(query, null).use { cursor ->
                while (cursor.moveToNext()) {
                    count++
                    for (i in 0 until cursor.columnCount) {
                        val bytes = if (cursor.isNull(i)) null else cursor.getString(i).toByteArray(Charsets.UTF_8)
                        digest.update(ByteBuffer.allocate(4).putInt(bytes?.size ?: -1).array())
                        bytes?.let(digest::update)
                    }
                }
            }
            return JSONObject().put("count", count).put("sha256", digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        }
        val result = JSONObject()
        val db = LocalLibrary.database(context).readableDatabase
        db.beginTransaction()
        try {
            result.put("schema", db.version)
            result.put("track_ids", rows(db, "SELECT id FROM tracks ORDER BY id"))
            result.put("roots", rows(db, "SELECT uri,name,owned FROM roots ORDER BY uri"))
            result.put("membership", rows(db, "SELECT root,id,document_id,uri,name,size,modified,present,readable FROM files ORDER BY root,id"))
            result.put("tagged_tracks", db.rawQuery("SELECT COUNT(*) FROM tracks WHERE track_no IS NOT NULL", null).use { it.moveToFirst(); it.getInt(0) })
            result.put("old_tag_rows", db.rawQuery("SELECT COUNT(*) FROM tracks WHERE tags_version<2", null).use { it.moveToFirst(); it.getInt(0) })
            result.put("scanning_roots", db.rawQuery("SELECT COUNT(*) FROM roots WHERE state='SCANNING'", null).use { it.moveToFirst(); it.getInt(0) })
        } finally { db.endTransaction() }
        val queue = context.getDatabasePath("playback-queue.db")
        if (queue.isFile) SQLiteDatabase.openDatabase(queue.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            // A consistent SQLite transaction needs a writable connection even though these statements only read.
            it.beginTransactionNonExclusive()
            try {
                result.put("queue_session", rows(it, "SELECT * FROM session ORDER BY singleton"))
                val state=JSONObject()
                it.rawQuery("SELECT * FROM session WHERE singleton=1",null).use { cursor ->
                    if(cursor.moveToFirst())for(name in listOf("current_index","position_us","shuffle_enabled","repeat_mode")) {
                        val column=cursor.getColumnIndex(name)
                        if(column>=0)state.put(name,cursor.getString(column))
                    }
                }
                result.put("queue_state",state)
                result.put("queue_entries", rows(it, "SELECT * FROM entries ORDER BY sequence"))
            } finally { it.endTransaction() }
        }
        result.put("persisted_grants", context.contentResolver.persistedUriPermissions.count { it.isReadPermission })
        result.put("v1_backup_exists", File(context.noBackupFilesDir, "library-migrations/library-v1-backup.db").isFile)
        return result.toString()
    }
}
