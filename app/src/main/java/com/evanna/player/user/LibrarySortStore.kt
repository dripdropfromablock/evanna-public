package com.evanna.player.user

import com.evanna.player.library.*

internal fun UserDatabase.librarySort(scope:LibrarySortScope):LibrarySort {
    val value=readableDatabase.rawQuery("SELECT value FROM metadata WHERE key=?",arrayOf("library_sort:${scope.name}")).use {if(it.moveToFirst())it.getString(0)else null}
    return storedLibrarySort(scope,value)
}
internal fun UserDatabase.saveLibrarySort(scope:LibrarySortScope,sort:LibrarySort):Boolean=try {
    require(sort in allowedLibrarySorts(scope))
    writableDatabase.execSQL("INSERT OR REPLACE INTO metadata(key,value) VALUES(?,?)",arrayOf("library_sort:${scope.name}",sort.name))
    changed();true
}catch(_:Exception){false}
