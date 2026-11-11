package com.evanna.player.user

internal fun validLibraryId(id:String)=id.matches(Regex("[0-9a-f]{64}"))
internal fun UserDatabase.isFavorite(id:String):Boolean {
    require(validLibraryId(id))
    return readableDatabase.rawQuery("SELECT 1 FROM favorites WHERE track_id=?",arrayOf(id)).use {it.moveToFirst()}
}
internal fun UserDatabase.setFavorite(id:String,selected:Boolean):Boolean=try {
    require(validLibraryId(id))
    val db=writableDatabase;db.beginTransaction()
    try {
        if(selected) {
            if(!isFavorite(id)) {
                check(db.rawQuery("SELECT COUNT(*) FROM favorites",null).use {it.moveToFirst();it.getInt(0)}<100000)
                db.execSQL("INSERT INTO favorites VALUES(?,?)",arrayOf<Any>(id,System.currentTimeMillis()))
            }
        }else db.delete("favorites","track_id=?",arrayOf(id))
        db.setTransactionSuccessful()
    }finally {db.endTransaction()}
    changed();true
}catch(_:Exception){false}
internal fun UserDatabase.favoriteIds(limit:Int=100,offset:Int=0):List<String> {
    return personalPage(PersonalView.FAVORITES,limit,offset).map {it.id}
}
internal fun UserDatabase.favoriteWindow(startId:String?):List<String> = personalWindow(PersonalView.FAVORITES,startId)
