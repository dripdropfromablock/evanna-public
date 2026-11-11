package com.evanna.player.user

internal data class PlayedSong(val eventId:String,val trackId:String,val playedAt:Long) {
    init {require(eventId.length in 1..100 && validLibraryId(trackId) && playedAt>=0)}
}
internal enum class PersonalView(val label:String) { FAVORITES("Favorites"), RECENT("Recently played"), MOST("Most played") }
internal data class PersonalSong(val id:String,val plays:Long)

internal fun UserDatabase.saveHistory(events:List<PlayedSong>):Boolean=try {
    require(events.size<=5000)
    if(events.isNotEmpty()) {
        val db=writableDatabase;db.beginTransaction()
        try {
            events.forEach {db.execSQL("INSERT OR IGNORE INTO history(event_id,track_id,played_at) VALUES(?,?,?)",arrayOf<Any>(it.eventId,it.trackId,it.playedAt))}
            db.execSQL("DELETE FROM history WHERE rowid NOT IN (SELECT rowid FROM history ORDER BY played_at DESC,rowid DESC LIMIT 5000)")
            db.setTransactionSuccessful()
        }finally {db.endTransaction()}
        changed()
    }
    true
}catch(_:Exception){false}

private fun personalQuery(view:PersonalView)=if(view==PersonalView.FAVORITES)
    "SELECT track_id,1 plays,added_at sort_time FROM favorites" else
    "SELECT track_id,COUNT(*) plays,MAX(played_at) sort_time FROM history GROUP BY track_id"
private fun personalOrder(view:PersonalView)=if(view==PersonalView.MOST)"plays DESC,sort_time DESC,track_id" else "sort_time DESC,track_id"

internal fun UserDatabase.personalPage(view:PersonalView,limit:Int=100,offset:Int=0):List<PersonalSong> {
    require(limit in 1..1000 && offset>=0)
    return readableDatabase.rawQuery("SELECT track_id,plays FROM (${personalQuery(view)}) ORDER BY ${personalOrder(view)} LIMIT ? OFFSET ?",arrayOf(limit.toString(),offset.toString())).use {c ->
        buildList {while(c.moveToNext())add(PersonalSong(c.getString(0),c.getLong(1)))}
    }
}
internal fun UserDatabase.personalWindow(view:PersonalView,startId:String?):List<String> {
    val db=readableDatabase;db.beginTransactionNonExclusive()
    try {
        val base=personalQuery(view)
        val offset=if(startId==null)0 else {
            if(!validLibraryId(startId))return emptyList()
            val anchor=db.rawQuery("SELECT plays,sort_time FROM ($base) WHERE track_id=?",arrayOf(startId)).use {c ->
                if(!c.moveToFirst())return emptyList()
                c.getLong(0) to c.getLong(1)
            }
            val args=listOf(anchor.second.toString(),anchor.second.toString(),startId)
            val condition="sort_time>CAST(? AS INTEGER) OR (sort_time=CAST(? AS INTEGER) AND track_id<?)"
            val ranked=if(view==PersonalView.MOST)"plays>CAST(? AS INTEGER) OR (plays=CAST(? AS INTEGER) AND ($condition))" else condition
            db.rawQuery("SELECT COUNT(*) FROM ($base) WHERE $ranked",(if(view==PersonalView.MOST)listOf(anchor.first.toString(),anchor.first.toString())+args else args).toTypedArray())
                .use {it.moveToFirst();it.getInt(0)}
        }
        val result=personalPage(view,1000,offset).map {it.id};db.setTransactionSuccessful();return result
    }finally {db.endTransaction()}
}
