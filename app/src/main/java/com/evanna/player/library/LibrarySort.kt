package com.evanna.player.library

internal enum class LibrarySort(val label:String) {
    TITLE("Title"),ARTIST("Artist"),ALBUM("Album"),ADDED("Recently added"),DURATION("Duration (shortest first)"),YEAR("Year")
}
internal enum class LibrarySortScope { SONGS, SEARCH, ALBUMS, FOLDERS }
internal fun allowedLibrarySorts(scope:LibrarySortScope):List<LibrarySort> = if(scope==LibrarySortScope.ALBUMS)
    listOf(LibrarySort.TITLE,LibrarySort.ARTIST,LibrarySort.YEAR,LibrarySort.ADDED)
    else listOf(LibrarySort.TITLE,LibrarySort.ARTIST,LibrarySort.ALBUM,LibrarySort.ADDED,LibrarySort.DURATION)
internal fun storedLibrarySort(scope:LibrarySortScope,value:String?):LibrarySort =
    allowedLibrarySorts(scope).firstOrNull {it.name==value} ?: LibrarySort.TITLE
