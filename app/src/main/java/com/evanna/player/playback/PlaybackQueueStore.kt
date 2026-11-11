package com.evanna.player.playback

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.evanna.player.decoder.MusicTags
import com.evanna.player.storage.backupSqliteSnapshot
import java.io.File

private val queueTagColumns=listOf("album_artist TEXT","track_no INTEGER","track_total INTEGER","disc_no INTEGER",
    "disc_total INTEGER","year INTEGER","genre TEXT","composer TEXT","compilation INTEGER NOT NULL DEFAULT 0")
private fun queueSchema(version:Int)=listOf(
    "CREATE TABLE session(singleton INTEGER PRIMARY KEY CHECK(singleton=1),current_index INTEGER NOT NULL,position_us INTEGER NOT NULL${if(version>=2)",shuffle_enabled INTEGER NOT NULL DEFAULT 0,repeat_mode TEXT NOT NULL DEFAULT 'OFF'" else ""})",
    "CREATE TABLE entries(sequence INTEGER PRIMARY KEY,id TEXT NOT NULL UNIQUE,library_id TEXT,file_uri TEXT,title TEXT NOT NULL,artist TEXT,album TEXT,duration_us INTEGER${if(version>=2)",play_order INTEGER NOT NULL" else ""})")

/** One private transaction retains the previous complete session if saving fails. */
internal class PlaybackQueueStore(context: Context, name: String = "playback-queue.db") : SQLiteOpenHelper(context,name,null,3), QueuePersistence {
    private val backupDirectory=File(context.noBackupFilesDir,"queue-migrations")
    private val backupName=name
    override fun onCreate(db: SQLiteDatabase) {
        queueSchema(2).forEach(db::execSQL)
        queueTagColumns.forEach {db.execSQL("ALTER TABLE entries ADD COLUMN $it")}
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..2 && newVersion==3){"Queue migration required"}
        backupSqliteSnapshot(db,File(backupDirectory,"$backupName-v$oldVersion.db"),queueSchema(oldVersion),
            listOf("session" to "singleton","entries" to "sequence"))
        if(oldVersion==1) {
            db.execSQL("ALTER TABLE session ADD COLUMN shuffle_enabled INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE session ADD COLUMN repeat_mode TEXT NOT NULL DEFAULT 'OFF'")
            db.execSQL("ALTER TABLE entries ADD COLUMN play_order INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE entries SET play_order=sequence")
        }
        queueTagColumns.forEach {db.execSQL("ALTER TABLE entries ADD COLUMN $it")}
    }
    override fun load(): SavedQueue? {
        val db=readableDatabase
        db.beginTransaction()
        try {
            data class Session(val index:Int,val position:Long,val shuffle:Boolean,val repeat:QueueRepeat)
            val session=db.rawQuery("SELECT current_index,position_us,shuffle_enabled,repeat_mode FROM session WHERE singleton=1",null).use { c ->
                if(c.moveToFirst()) {
                    check(c.getInt(2) in 0..1){"INVALID_SHUFFLE"}
                    Session(c.getInt(0),c.getLong(1),c.getInt(2)==1,QueueRepeat.valueOf(c.getString(3)))
                } else null
            }
            val order=mutableListOf<Pair<Int,String>>()
            val items=db.rawQuery("SELECT sequence,id,library_id,file_uri,title,artist,album,duration_us,play_order,album_artist,track_no,track_total,disc_no,disc_total,year,genre,composer,compilation FROM entries ORDER BY sequence LIMIT 1001",null).use { c ->
                buildList {
                    while(c.moveToNext()) {
                        check(c.getInt(0)==size && size<1000){"INVALID_QUEUE"}
                        check(c.getInt(17) in 0..1){"INVALID_COMPILATION"}
                        val tags=MusicTags(c.optionalString(9),c.optionalInt(10),c.optionalInt(11),c.optionalInt(12),c.optionalInt(13),
                            c.optionalInt(14),c.optionalString(15),c.optionalString(16),c.getInt(17)==1)
                        add(SavedQueueEntry(c.getString(1),c.optionalString(2),c.optionalString(3),c.getString(4),c.optionalString(5),c.optionalString(6),if(c.isNull(7)) null else c.getLong(7),tags))
                        order+=c.getInt(8) to c.getString(1)
                    }
                }
            }
            check(session!=null || items.isEmpty()){"INCOMPLETE_QUEUE"}
            check(order.map{it.first}.sorted()==items.indices.toList()){"INVALID_SHUFFLE_ORDER"}
            return session?.let { SavedQueue(items,it.index,it.position,it.shuffle,it.repeat,order.sortedBy{entry->entry.first}.map{entry->entry.second}) }
        } finally { db.endTransaction() }
    }
    override fun save(state: SavedQueue) {
        val db=writableDatabase
        db.beginTransaction()
        try {
            db.delete("entries",null,null)
            val order=state.playOrder.withIndex().associate{it.value to it.index}
            state.items.forEachIndexed { index,item ->
                val row=ContentValues().apply {
                    put("sequence",index);put("id",item.id);put("library_id",item.libraryId);put("file_uri",item.fileUri)
                    put("title",item.title);put("artist",item.artist);put("album",item.album);put("duration_us",item.durationUs)
                    put("play_order",order.getValue(item.id))
                    val tags=item.musicTags
                    put("album_artist",tags.albumArtist);put("track_no",tags.trackNumber);put("track_total",tags.totalTracks)
                    put("disc_no",tags.discNumber);put("disc_total",tags.totalDiscs);put("year",tags.year)
                    put("genre",tags.genre);put("composer",tags.composer);put("compilation",if(tags.compilation)1 else 0)
                }
                db.insertOrThrow("entries",null,row)
            }
            db.execSQL("INSERT OR REPLACE INTO session(singleton,current_index,position_us,shuffle_enabled,repeat_mode) VALUES(1,?,?,?,?)",arrayOf(state.index,state.positionUs,if(state.shuffleEnabled)1 else 0,state.repeat.name))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
private fun Cursor.optionalString(index:Int):String?=if(isNull(index)) null else getString(index)
private fun Cursor.optionalInt(index:Int):Int?=if(isNull(index))null else getInt(index)
