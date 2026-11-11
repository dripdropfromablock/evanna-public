package com.evanna.player.library

internal enum class LibraryContextAction { PLAY_FROM, PLAY_FIRST, SHUFFLE }
internal data class LibraryContextRequest(val filter: LibraryFilter, val startId: String?, val action: LibraryContextAction)

internal fun libraryContextRequest(kind: String?, value: String?, albumArtist: String?, search: String?,
    startId: String?, action: String?,sort:String?=null): LibraryContextRequest? {
    if (search == null || search.length > 200 || (value?.length ?: 0) > 8192 || (albumArtist?.length ?: 0) > 512) return null
    val group = when (kind) {
        "SONGS" -> null
        "ALBUM" -> LibraryGroupKind.ALBUM
        "ARTIST" -> LibraryGroupKind.ARTIST
        "GENRE" -> LibraryGroupKind.GENRE
        "COMPOSER" -> LibraryGroupKind.COMPOSER
        "FOLDER" -> LibraryGroupKind.FOLDER
        else -> return null
    }
    if (group == null && value != null || group != LibraryGroupKind.ALBUM && albumArtist != null) return null
    if (group == LibraryGroupKind.FOLDER && value?.startsWith("content://") != true) return null
    if (group!=null && group!=LibraryGroupKind.FOLDER && (value?.length ?: 0) > 512) return null
    val intent = LibraryContextAction.entries.firstOrNull { it.name == action } ?: return null
    if (intent == LibraryContextAction.PLAY_FROM) {
        if (startId?.matches(Regex("[0-9a-f]{64}")) != true) return null
    } else if (startId != null || group==null || group==LibraryGroupKind.FOLDER) return null
    val order=if(sort==null)LibrarySort.TITLE else LibrarySort.entries.firstOrNull {it.name==sort} ?: return null
    if(group !in listOf(LibraryGroupKind.ALBUM,LibraryGroupKind.ARTIST) && order !in allowedLibrarySorts(LibrarySortScope.SONGS))return null
    return LibraryContextRequest(LibraryFilter(search, group, value, albumArtist,
        if(group in listOf(LibraryGroupKind.ALBUM,LibraryGroupKind.ARTIST))LibrarySort.TITLE else order), startId, intent)
}
