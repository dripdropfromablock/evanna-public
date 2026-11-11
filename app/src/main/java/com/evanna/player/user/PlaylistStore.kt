package com.evanna.player.user

import android.content.ContentValues

internal fun UserDatabase.playlists():List<PlaylistSummary> = readableDatabase.rawQuery(
    "SELECT p.id,p.name,COUNT(i.position) FROM playlists p LEFT JOIN playlist_items i ON i.playlist_id=p.id GROUP BY p.id ORDER BY p.modified DESC,p.id DESC LIMIT 500",null).use {cursor ->
    buildList {while(cursor.moveToNext())add(PlaylistSummary(cursor.getLong(0),cursor.getString(1),cursor.getInt(2)))}
}
internal fun UserDatabase.playlist(id:Long,expectedRevision:Long?=null):Playlist? {
    val db=readableDatabase;db.beginTransactionNonExclusive()
    try {
        val head=db.rawQuery("SELECT name,revision FROM playlists WHERE id=?",arrayOf(id.toString())).use {if(it.moveToFirst())it.getString(0) to it.getLong(1) else null} ?: return null
        if(expectedRevision!=null && expectedRevision!=head.second)return null
        val items=db.rawQuery("SELECT track_id,title,artist,album,duration_us,file_name FROM playlist_items WHERE playlist_id=? ORDER BY position LIMIT 1001",arrayOf(id.toString())).use {cursor ->
            buildList {while(cursor.moveToNext())add(PlaylistEntry(if(cursor.isNull(0))null else cursor.getString(0),cursor.getString(1),cursor.getString(2),cursor.getString(3),
                if(cursor.isNull(4))null else cursor.getLong(4),cursor.getString(5)))}
        }
        check(items.size<=PLAYLIST_LIMIT)
        db.setTransactionSuccessful();return Playlist(id,head.first,head.second,items)
    }finally {db.endTransaction()}
}

/** Compare revision before replacing items: failed writes and stale editors retain the previous complete list. */
internal fun UserDatabase.savePlaylist(id:Long?,name:String,items:List<PlaylistEntry>,revision:Long?=null):Long?=try {
    val valid=checkNotNull(validPlaylistName(name));require(items.size<=PLAYLIST_LIMIT)
    val db=writableDatabase;db.beginTransaction()
    try {
        val now=System.currentTimeMillis()
        val key=if(id==null) {
            check(revision==null)
            check(db.rawQuery("SELECT COUNT(*) FROM playlists",null).use {it.moveToFirst();it.getInt(0)}<500)
            db.insertOrThrow("playlists",null,ContentValues().apply {put("name",valid);put("created",now);put("modified",now);put("revision",1)})
        }else {
            require(revision!=null && revision in 1 until Long.MAX_VALUE)
            check(db.update("playlists",ContentValues().apply {put("name",valid);put("modified",now);put("revision",revision+1)},
                "id=? AND revision=?",arrayOf(id.toString(),revision.toString()))==1)
            db.delete("playlist_items","playlist_id=?",arrayOf(id.toString()));id
        }
        items.forEachIndexed {index,item ->db.insertOrThrow("playlist_items",null,ContentValues().apply {
            put("playlist_id",key);put("position",index);put("track_id",item.trackId);put("title",item.title)
            put("artist",item.artist);put("album",item.album);put("duration_us",item.durationUs);put("file_name",item.fileName)
        })}
        db.setTransactionSuccessful();key
    }finally {db.endTransaction()}
}catch(_:Exception){null}

internal fun UserDatabase.deletePlaylist(id:Long,revision:Long):Boolean=try {
    writableDatabase.delete("playlists","id=? AND revision=?",arrayOf(id.toString(),revision.toString()))==1
}catch(_:Exception){false}
