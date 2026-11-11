package com.evanna.player.library

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.evanna.player.decoder.MusicTags
import com.evanna.player.user.PlaylistEntry
import com.evanna.player.user.PlaylistFile
import java.io.File
import java.security.MessageDigest
import com.evanna.player.storage.backupSqliteSnapshot

internal data class LibraryRoot(val uri: String, val name: String, val state: LibraryRootState,val songCount:Int=0)
internal data class ExcludedFolder(val id:String,val name:String)
internal data class LibrarySong(val id: String, val uri: String, val title: String, val artist: String?, val album: String?,
    val durationUs: Long?, val codec: String?, val rate: Int?, val available: Boolean, val musicTags: MusicTags = MusicTags(),val addedAt:Long=0,
    /** A CUE track's exact frame range inside [uri]; null start means the whole file. */
    val startFrame:Long?=null,val endFrame:Long?=null)
internal data class LibraryPage(val roots: List<LibraryRoot>, val songs: List<LibrarySong>, val total: Int)
internal data class LibraryQueueWindow(val songs: List<LibrarySong>, val startIndex: Int, val truncated: Boolean)
internal enum class LibraryGroupKind { ALBUM, ARTIST, GENRE, COMPOSER, FOLDER }
internal data class AlbumIdentity(val title: String?, val albumArtist: String?) {
    val key get() = "album:${title?.let { "${it.length}:$it" } ?: "-"}:${albumArtist?.let { "${it.length}:$it" } ?: "-"}"
}
internal data class AlbumSummary(val displayArtist: String?, val year: Int?, val multipleDiscs: Boolean)
internal data class LibraryFilter(val search: String = "", val kind: LibraryGroupKind? = null, val value: String? = null,
    val albumArtist: String? = null,val sort:LibrarySort=LibrarySort.TITLE)
internal data class LibraryGroup(val value: String?, val count: Int, val artist: String? = null, val albumArtist: String? = null,val year:Int?=null)
internal data class LibraryGroupPage(val groups: List<LibraryGroup>, val total: Int)
internal data class ArtworkSource(val uri: String, val key: String, val available: Boolean)

/** Private index only. Scanner generations protect incomplete scans and late cancellation. */
internal class LibraryDatabase(context: Context, name: String = "library.db",private val nowMs:()->Long={System.currentTimeMillis()}) : SQLiteOpenHelper(context, name, null, LIBRARY_DB_VERSION), LibraryScanIndex {
    private val migrationBackup = File(context.noBackupFilesDir, "library-migrations/${File(name).name.removeSuffix(".db")}-v1-backup.db")
    private val migrationDirectory=File(context.noBackupFilesDir,"library-migrations")
    private val migrationName=File(name).name.removeSuffix(".db")
    init { setWriteAheadLoggingEnabled(true) }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        librarySchema(LIBRARY_DB_VERSION).forEach(db::execSQL)
    }
    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        db.execSQL("UPDATE roots SET state='PAUSED', token=NULL WHERE state='SCANNING'")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1 until LIBRARY_DB_VERSION && newVersion == LIBRARY_DB_VERSION) { "Library migration required" }
        if(oldVersion==1)upgradeLibraryV1(db,migrationBackup)
        else backupSqliteSnapshot(db,File(migrationDirectory,"$migrationName-v$oldVersion-backup.db"),librarySchema(oldVersion),listOf("roots" to "uri","tracks" to "id","files" to "root,id")+
            if(oldVersion>=5)listOf("excluded_folders" to "root,id","pending_grant_releases" to "uri")else emptyList())
        librarySchema(LIBRARY_DB_VERSION).drop(librarySchema(maxOf(oldVersion,2)).size).forEach(db::execSQL)
        if(oldVersion<3)db.execSQL("UPDATE tracks SET added_at=?",arrayOf(nowMs().coerceAtLeast(0)))
    }

    fun addRoot(uri: String, name: String, owned: Boolean) {
        transaction { db ->
            val inherited=db.rawQuery("SELECT 1 FROM pending_grant_releases WHERE uri=?",arrayOf(uri)).use {it.moveToFirst()}
            val ownership=if(owned || inherited)1 else 0
            val values = ContentValues().apply { put("uri", uri); put("name", name); put("state", "PAUSED"); put("owned", ownership) }
            db.insertWithOnConflict("roots", null, values, SQLiteDatabase.CONFLICT_IGNORE).also {
                if (it < 0) check(db.rawQuery("SELECT 1 FROM roots WHERE uri=?", arrayOf(uri)).use { c -> c.moveToFirst() })
            }
            db.execSQL("UPDATE roots SET name=?,owned=MAX(owned,?) WHERE uri=?", arrayOf(name, ownership, uri))
            db.delete("pending_grant_releases","uri=?",arrayOf(uri))
        }
    }
    fun roots(): List<LibraryRoot> = readableDatabase.rawQuery(
        "SELECT uri,name,state,(SELECT COUNT(*) FROM files f WHERE f.root=roots.uri) FROM roots ORDER BY name COLLATE NOCASE,uri", null).use { c ->
        buildList { while (c.moveToNext()) add(LibraryRoot(c.getString(0), c.getString(1), LibraryRootState.valueOf(c.getString(2)),c.getInt(3))) }
    }
    fun removeRoot(root:String) {
        transaction {db ->
            val owned=db.rawQuery("SELECT owned FROM roots WHERE uri=?",arrayOf(root)).use {check(it.moveToFirst());it.getInt(0)!=0}
            db.delete("files","root=?",arrayOf(root));db.delete("excluded_folders","root=?",arrayOf(root))
            db.delete("roots","uri=?",arrayOf(root));pruneOrphans(db)
            if(owned)db.insertOrThrow("pending_grant_releases",null,ContentValues().apply {put("uri",root)})
        }
    }
    fun pendingGrantReleases():List<String> = readableDatabase.rawQuery("SELECT uri FROM pending_grant_releases ORDER BY uri",null).use {c -> buildList {while(c.moveToNext())add(c.getString(0))}}
    fun acknowledgeGrantRelease(root:String) {writableDatabase.delete("pending_grant_releases","uri=?",arrayOf(root))}
    fun excludedFolders(root:String):List<ExcludedFolder> = readableDatabase.rawQuery("SELECT id,name FROM excluded_folders WHERE root=? ORDER BY name COLLATE NOCASE,id",arrayOf(root)).use {c -> buildList {while(c.moveToNext())add(ExcludedFolder(c.getString(0),c.getString(1)))}}
    fun folderDocuments(root:String):List<Pair<String,String>> = readableDatabase.rawQuery("SELECT id,document_id FROM files WHERE root=? LIMIT 100001",arrayOf(root)).use {c ->
        check(c.count<=100000);buildList {while(c.moveToNext())add(c.getString(0) to c.getString(1))}
    }
    fun excludeFolder(root:String,id:String,name:String,descendants:Set<String>) {
        require(id.isNotBlank() && id.length<=8192 && name.isNotBlank() && name.length<=512 && descendants.size<=100000 && descendants.all {it.matches(Regex("[0-9a-f]{64}"))})
        transaction {db ->
            check(db.rawQuery("SELECT 1 FROM roots WHERE uri=?",arrayOf(root)).use {it.moveToFirst()})
            check(excludedFolders(root).let {it.size<128 || it.any {folder->folder.id==id}})
            db.insertWithOnConflict("excluded_folders",null,ContentValues().apply {put("root",root);put("id",id);put("name",name)},SQLiteDatabase.CONFLICT_REPLACE).also {check(it>=0)}
            db.compileStatement("DELETE FROM files WHERE root=? AND id=?").use {statement -> for(key in descendants) {
                statement.clearBindings();statement.bindString(1,root);statement.bindString(2,key);statement.executeUpdateDelete()
            }}
            pruneOrphans(db)
        }
    }
    fun includeFolder(root:String,id:String) {writableDatabase.delete("excluded_folders","root=? AND id=?",arrayOf(root,id))}
    private fun pruneOrphans(db:SQLiteDatabase) {db.execSQL("DELETE FROM tracks WHERE NOT EXISTS(SELECT 1 FROM files WHERE files.id=tracks.id)")}
    fun rootsNeedingTagRefresh(): List<LibraryRoot> = readableDatabase.rawQuery(
        "SELECT r.uri,r.name,r.state FROM roots r WHERE r.state='READY' AND EXISTS(SELECT 1 FROM files f JOIN tracks t ON t.id=f.id WHERE f.root=r.uri AND f.present=1 AND f.readable=1 AND t.tags_version<?) ORDER BY r.uri",
        arrayOf(LIBRARY_TAGS_VERSION.toString())).use { c ->
        buildList { while (c.moveToNext()) add(LibraryRoot(c.getString(0), c.getString(1), LibraryRootState.valueOf(c.getString(2)))) }
    }
    fun page(limit: Int = 100, offset: Int = 0, filter: LibraryFilter = LibraryFilter()): LibraryPage {
        require(limit in 1..1000 && offset >= 0)
        val db = readableDatabase
        val (where, arguments) = matching(filter)
        val songs = db.rawQuery(songQuery(filter) + " LIMIT ? OFFSET ?", (arguments + listOf(limit.toString(), offset.toString())).toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(c.librarySong()) }
        }
        val total = db.rawQuery("SELECT COUNT(*) FROM tracks t WHERE $where", arguments.toTypedArray()).use { it.moveToFirst(); it.getInt(0) }
        return LibraryPage(roots(), songs, total)
    }
    private fun songQuery(filter: LibraryFilter): String {
        val (where, _) = matching(filter)
        val available = "(f.present=1 AND f.readable=1 AND r.state!='UNAVAILABLE')"
        val choose = "SELECT f2.rowid FROM files f2 JOIN roots r2 ON r2.uri=f2.root WHERE f2.id=t.id ORDER BY (f2.present=1 AND f2.readable=1 AND r2.state!='UNAVAILABLE') DESC,f2.root LIMIT 1"
        val years = if (filter.kind == LibraryGroupKind.ARTIST)
            "LEFT JOIN (SELECT NULLIF(album,'') album_key,NULLIF(album_artist,'') artist_key,CASE WHEN MIN(year)=MAX(year) THEN MIN(year) END year,MIN(NULLIF(sort_album,'')) sort_album,MIN(NULLIF(sort_album_artist,'')) sort_artist FROM tracks WHERE cue_hidden=0 GROUP BY album_key,artist_key) ay ON ay.album_key IS NULLIF(t.album,'') AND ay.artist_key IS NULLIF(t.album_artist,'')" else ""
        return "SELECT t.id,f.uri,t.title,t.artist,t.album,t.duration,t.codec,t.rate,$available AS available,$musicTagColumns,t.added_at,t.start_frame,t.end_frame,COALESCE(NULLIF(t.sort_title,''),t.title) AS sort_key FROM tracks t JOIN files f ON f.id=t.id JOIN roots r ON r.uri=f.root $years WHERE f.rowid=($choose) AND $where ORDER BY ${orderFor(filter)}"
    }
    /** Locate the anchor before applying the queue cap; all reads share one SQLite snapshot. */
    fun queueWindow(filter: LibraryFilter, startId: String?, limit: Int = 1000): LibraryQueueWindow? {
        if (limit !in 1..1000) return null
        val db = readableDatabase
        db.beginTransactionNonExclusive()
        try {
            if (filter.kind == LibraryGroupKind.FOLDER && roots().none { it.uri == filter.value }) return null
            val (_, arguments) = matching(filter)
            var index = -1
            var total = 0
            db.rawQuery("SELECT id,available FROM (${songQuery(filter)})", arguments.toTypedArray()).use { cursor ->
                total = cursor.count
                while (cursor.moveToNext()) if (startId == cursor.getString(0) || startId == null && cursor.getInt(1) != 0) {
                    if (cursor.getInt(1) == 0) return null
                    index = cursor.position; break
                }
            }
            if (index < 0) return null
            val offset = if (total > limit) index else 0
            return LibraryQueueWindow(page(limit, offset, filter).songs, index-offset, total>limit)
        } finally { db.endTransaction() }
    }
    fun groups(kind: LibraryGroupKind, search: String = "", limit: Int = 100, offset: Int = 0,sort:LibrarySort=LibrarySort.TITLE): LibraryGroupPage {
        return groupPage(kind,LibraryFilter(search),limit,offset,sort)
    }
    fun albumsForArtist(artist:String?,search:String="",limit:Int=100,offset:Int=0):LibraryGroupPage =
        groupPage(LibraryGroupKind.ALBUM,LibraryFilter(search,LibraryGroupKind.ARTIST,artist),limit,offset,LibrarySort.YEAR)

    private fun groupPage(kind:LibraryGroupKind,filter:LibraryFilter,limit:Int,offset:Int,sort:LibrarySort):LibraryGroupPage {
        require(limit in 1..1000 && offset >= 0 && kind != LibraryGroupKind.FOLDER)
        val (query,arguments)=groupQuery(kind,filter,sort)
        val groups = readableDatabase.rawQuery(query+" LIMIT ? OFFSET ?", (arguments + listOf(limit.toString(), offset.toString())).toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(if (kind == LibraryGroupKind.ALBUM) LibraryGroup(c.stringOrNull(0), c.getInt(1), c.stringOrNull(2), c.stringOrNull(3),c.longOrNull(5)?.toInt())
                else LibraryGroup(c.stringOrNull(0), c.getInt(1))) }
        }
        val total = readableDatabase.rawQuery("SELECT COUNT(*) FROM ($query)", arguments.toTypedArray()).use { it.moveToFirst(); it.getInt(0) }
        return LibraryGroupPage(groups, total)
    }
    /** Count the prefix in the same ordering/filter used by paging; never load the skipped songs. */
    fun letterOffset(letter:Char,filter:LibraryFilter=LibraryFilter(),group:LibraryGroupKind?=null):Int {
        require(letter in libraryAlphabet && filter.sort==LibrarySort.TITLE && filter.kind==null)
        require(group==null || group==LibraryGroupKind.ALBUM || group==LibraryGroupKind.ARTIST)
        if(letter=='#')return 0
        val (query,args)=if(group==null)songQuery(filter) to matching(filter).second else groupQuery(group,filter,filter.sort)
        // Android 11's SQLite ran `sort_key COLLATE NOCASE < ?` here case-sensitively, so jumps landed early.
        // Plain comparisons spell out the same ASCII fold: below the capital, or from '[' up to the small letter.
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM ($query) WHERE sort_key IS NULL OR sort_key < ? OR (sort_key >= '[' AND sort_key < ?)",
            (args+letter.toString()+letter.lowercase()).toTypedArray()).use {it.moveToFirst();it.getInt(0)}
    }
    private fun groupQuery(kind:LibraryGroupKind,filter:LibraryFilter,sort:LibrarySort):Pair<String,List<String>> {
        require(kind!=LibraryGroupKind.FOLDER)
        require(if(kind==LibraryGroupKind.ALBUM)sort in allowedLibrarySorts(LibrarySortScope.ALBUMS)else sort==LibrarySort.TITLE)
        val (where, arguments) = matching(filter)
        val column=tagColumn(kind)
        val grouped=if(kind==LibraryGroupKind.ALBUM)
            "SELECT NULLIF(t.album,'') value,NULLIF(t.album_artist,'') album_artist,COUNT(*) songs,MIN(NULLIF(t.sort_album,'')) sort_title,MIN(NULLIF(t.sort_album_artist,'')) sort_artist,CASE WHEN MIN(t.year)=MAX(t.year) THEN MIN(t.year) END sort_year,MAX(t.added_at) sort_added FROM tracks t WHERE $where GROUP BY value,album_artist"
            else "SELECT NULLIF(t.$column,'') value,COUNT(*) songs,${if(kind==LibraryGroupKind.ARTIST)"MIN(NULLIF(t.sort_artist,''))" else "NULL"} sort_title FROM tracks t WHERE $where GROUP BY value"
        // Rank track artists inside each exact album, with a deterministic tie and no invented artist for all-single mixes.
        val artists = "WITH counts AS (SELECT NULLIF(a.album,'') album_key,NULLIF(a.album_artist,'') artist_key,NULLIF(a.artist,'') name,COUNT(*) songs " +
            "FROM tracks a WHERE a.cue_hidden=0 AND NULLIF(a.artist,'') IS NOT NULL AND EXISTS(SELECT 1 FROM files m WHERE m.id=a.id) GROUP BY album_key,artist_key,name), " +
            "tops AS (SELECT album_key,artist_key,MAX(songs) top,COUNT(*) names FROM counts GROUP BY album_key,artist_key) " +
            "SELECT c.album_key,c.artist_key,MIN(c.name) name FROM counts c JOIN tops x ON x.album_key IS c.album_key AND x.artist_key IS c.artist_key " +
            "WHERE c.songs=x.top AND (x.top>1 OR x.names=1) GROUP BY c.album_key,c.artist_key"
        val title="COALESCE(g.sort_title,g.value) COLLATE NOCASE,g.value COLLATE NOCASE,g.value,g.album_artist COLLATE NOCASE,g.album_artist"
        val artist="COALESCE(g.sort_artist,g.album_artist,b.name)"
        val order=when(sort) {
            LibrarySort.ARTIST -> "($artist IS NULL),$artist COLLATE NOCASE,$title"
            LibrarySort.YEAR -> "(g.sort_year IS NULL),g.sort_year,$title"
            LibrarySort.ADDED -> "g.sort_added DESC,$title"
            else -> title
        }
        val query = if (kind != LibraryGroupKind.ALBUM)
            "SELECT value,songs,NULL,NULL,COALESCE(sort_title,value) sort_key,NULL FROM ($grouped) ORDER BY sort_key COLLATE NOCASE,value COLLATE NOCASE,value" else
            "SELECT g.value,g.songs,COALESCE(g.album_artist,b.name),g.album_artist,COALESCE(g.sort_title,g.value) sort_key,g.sort_year FROM ($grouped) g LEFT JOIN ($artists) b ON b.album_key IS g.value AND b.artist_key IS g.album_artist ORDER BY $order"
        return query to arguments
    }
    private fun orderFor(filter: LibraryFilter): String = when (filter.kind) {
        LibraryGroupKind.ALBUM -> albumOrder
        LibraryGroupKind.ARTIST -> "(ay.year IS NULL),ay.year,COALESCE(ay.sort_album,NULLIF(t.album,'')) COLLATE NOCASE,NULLIF(t.album,''),COALESCE(ay.sort_artist,NULLIF(t.album_artist,'')) COLLATE NOCASE,NULLIF(t.album_artist,''),$albumOrder"
        else -> {
            val title="COALESCE(NULLIF(t.sort_title,''),t.title) COLLATE NOCASE"
            val artist="COALESCE(NULLIF(t.sort_artist,''),NULLIF(t.artist,''))"
            val album="COALESCE(NULLIF(t.sort_album,''),NULLIF(t.album,''))"
            when(filter.sort) {
                LibrarySort.TITLE -> "$title,t.id"
                LibrarySort.ARTIST -> "($artist IS NULL),$artist COLLATE NOCASE,$title,t.id"
                LibrarySort.ALBUM -> "($album IS NULL),$album COLLATE NOCASE,$title,t.id"
                LibrarySort.ADDED -> "t.added_at DESC,t.id"
                LibrarySort.DURATION -> "(t.duration IS NULL),t.duration,$title,t.id"
                LibrarySort.YEAR -> error("Unsupported song sort")
            }
        }
    }

    fun albumSummary(album: AlbumIdentity): AlbumSummary {
        val (where, arguments) = matching(LibraryFilter(kind = LibraryGroupKind.ALBUM, value = album.title, albumArtist = album.albumArtist))
        val db = readableDatabase
        val facts = db.rawQuery("SELECT MIN(t.year),MAX(t.year),MAX(t.disc_total),COUNT(DISTINCT t.disc_no) FROM tracks t WHERE $where", arguments.toTypedArray()).use { c ->
            c.moveToFirst()
            (c.longOrNull(0)?.toInt().takeIf { c.longOrNull(0) == c.longOrNull(1) }) to (c.getInt(2)>1 || c.getInt(3)>1)
        }
        val artist = album.albumArtist ?: db.rawQuery("SELECT t.artist,COUNT(*) songs FROM tracks t WHERE $where AND NULLIF(t.artist,'') IS NOT NULL GROUP BY t.artist ORDER BY songs DESC,t.artist LIMIT 2", arguments.toTypedArray()).use { c ->
            if (!c.moveToFirst()) null else {
                val first = c.getString(0); val count = c.getInt(1)
                first.takeIf { count>1 || !c.moveToNext() }
            }
        }
        return AlbumSummary(artist, facts.first, facts.second)
    }
    private fun matching(filter: LibraryFilter): Pair<String, List<String>> {
        require(filter.search.length <= 200)
        val conditions = mutableListOf("t.cue_hidden=0", "EXISTS(SELECT 1 FROM files membership WHERE membership.id=t.id)")
        val arguments = mutableListOf<String>()
        val text = filter.search.trim()
        if (text.isNotEmpty()) {
            // shortcut: Case folding follows platform SQLite; add normalized keys for uniform cross-device search.
            val pattern = "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            conditions += """(t.title LIKE ? ESCAPE '\' OR t.artist LIKE ? ESCAPE '\' OR t.album LIKE ? ESCAPE '\' OR t.genre LIKE ? ESCAPE '\' OR t.composer LIKE ? ESCAPE '\' OR EXISTS(SELECT 1 FROM files named WHERE named.id=t.id AND named.name LIKE ? ESCAPE '\'))"""
            repeat(6) { arguments += pattern }
        }
        when (filter.kind) {
            LibraryGroupKind.ALBUM, LibraryGroupKind.ARTIST,LibraryGroupKind.GENRE,LibraryGroupKind.COMPOSER -> {
                val column = tagColumn(filter.kind)
                conditions += if (filter.value == null) "NULLIF(t.$column,'') IS NULL" else "NULLIF(t.$column,'')=?"
                filter.value?.let { arguments += it }
                if (filter.kind == LibraryGroupKind.ALBUM) {
                    conditions += if (filter.albumArtist == null) "NULLIF(t.album_artist,'') IS NULL" else "NULLIF(t.album_artist,'')=?"
                    filter.albumArtist?.let { arguments += it }
                }
            }
            LibraryGroupKind.FOLDER -> {
                require(filter.value != null)
                conditions += "EXISTS(SELECT 1 FROM files scoped WHERE scoped.id=t.id AND scoped.root=?)"
                arguments += filter.value
            }
            null -> Unit
        }
        return conditions.joinToString(" AND ") to arguments
    }
    fun song(id: String): LibrarySong? {
        if (!id.matches(Regex("[0-9a-f]{64}"))) return null
        return readableDatabase.rawQuery("SELECT t.id,f.uri,t.title,t.artist,t.album,t.duration,t.codec,t.rate,(f.present=1 AND f.readable=1 AND r.state!='UNAVAILABLE') AS available,$musicTagColumns,t.added_at,t.start_frame,t.end_frame FROM tracks t JOIN files f ON f.id=t.id JOIN roots r ON r.uri=f.root WHERE t.id=? ORDER BY available DESC,f.root LIMIT 1", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) null else c.librarySong()
        }
    }
    private fun Cursor.librarySong() = LibrarySong(getString(0), getString(1), getString(2), stringOrNull(3), stringOrNull(4), longOrNull(5),
        stringOrNull(6), longOrNull(7)?.toInt(), getInt(8) != 0, musicTags(9), getLong(18), longOrNull(19), longOrNull(20))
    /** At most two distinct matches are needed to reject ambiguity; overlapping grants may repeat a file. */
    fun playlistFiles(id:String?=null,entry:PlaylistEntry?=null,documentId:String?=null):List<PlaylistFile> {
        require(id!=null || entry!=null || documentId!=null)
        val where=if(id!=null)"t.id=?" else if(documentId!=null)"f.document_id=?" else "(f.name=? COLLATE NOCASE OR (t.title=? COLLATE NOCASE AND COALESCE(t.artist,'')=? COLLATE NOCASE))"
        val args=if(id!=null)arrayOf(id)else if(documentId!=null)arrayOf(documentId)else arrayOf(entry?.fileName.orEmpty(),entry?.title.orEmpty(),entry?.artist.orEmpty())
        return readableDatabase.rawQuery("SELECT t.id,t.title,t.artist,t.album,t.duration,f.name,f.uri,f.root,f.document_id,(f.present=1 AND f.readable=1 AND r.state!='UNAVAILABLE') FROM tracks t JOIN files f ON f.id=t.id JOIN roots r ON r.uri=f.root WHERE $where ORDER BY t.id,f.root LIMIT 1001",args).use {c ->
            if(c.count>1000)return@use emptyList()
            buildList {while(c.moveToNext())add(PlaylistFile(PlaylistEntry(c.getString(0),c.getString(1).take(512),c.getString(2)?.take(512),c.getString(3)?.take(512),
                c.longOrNull(4)?.takeIf {it in 0..604_800_000_000L},c.getString(5)),c.getString(6),c.getString(7),c.getString(8),c.getInt(9)!=0))}
        }
    }
    fun artworkSource(id: String): ArtworkSource? {
        if (!id.matches(Regex("[0-9a-f]{64}"))) return null
        return readableDatabase.rawQuery("SELECT f.uri,f.size,f.modified,f.token,(f.present=1 AND f.readable=1 AND r.state!='UNAVAILABLE') AS available,f.root FROM files f JOIN roots r ON r.uri=f.root WHERE f.id=? ORDER BY available DESC,f.root", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) return@use null
            val uri = c.getString(0); val size = c.longOrNull(1); val modified = c.longOrNull(2); val available = c.getInt(4) != 0
            // Every overlapping root can discover a changed cover even when the selected root was not rescanned.
            val versions = buildList {
                do { add(artworkKey(c.getString(5), c.getString(0), c.longOrNull(1), c.longOrNull(2), c.getString(3))) }
                while (c.moveToNext())
            }
            ArtworkSource(uri, artworkKey(id, uri, size, modified, versions.sorted().joinToString("")), available)
        }
    }
    fun albumArtworkSongs(album: AlbumIdentity): List<String> {
        val (where, arguments) = matching(LibraryFilter(kind = LibraryGroupKind.ALBUM, value = album.title, albumArtist = album.albumArtist))
        return readableDatabase.rawQuery("SELECT t.id FROM tracks t WHERE $where AND EXISTS(SELECT 1 FROM files f JOIN roots r ON r.uri=f.root WHERE f.id=t.id AND f.present=1 AND f.readable=1 AND r.state!='UNAVAILABLE') ORDER BY $albumOrder LIMIT 4",
            arguments.toTypedArray()).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    }
    override fun begin(root: String, token: String) {
        writableDatabase.execSQL("UPDATE roots SET state='SCANNING',token=? WHERE uri=?", arrayOf(token, root))
    }
    override fun cached(root: String, key: String): LibraryCached? = readableDatabase.rawQuery(
        "SELECT f.document_id,f.uri,f.name,f.size,f.modified,f.readable,t.title,t.artist,t.album,t.duration,t.codec,t.rate,t.bits,t.channels,$musicTagColumns,t.tags_version,t.sort_title,t.sort_artist,t.sort_album,t.sort_album_artist FROM files f JOIN tracks t ON t.id=f.id WHERE f.root=? AND f.id=?", arrayOf(root, key)).use { c ->
        if (!c.moveToFirst()) null else LibraryCached(LibraryDocument(c.getString(0), key, c.getString(1), c.getString(2), "audio/", c.longOrNull(3), c.longOrNull(4)),
            if (c.getInt(5) == 0) null else LibraryAudio(c.getString(6), c.stringOrNull(7), c.stringOrNull(8), c.longOrNull(9), c.getString(10), c.getInt(11), c.longOrNull(12)?.toInt(), c.getInt(13), c.musicTags(14),
                com.evanna.player.decoder.SortTags(c.stringOrNull(24),c.stringOrNull(25),c.stringOrNull(26),c.stringOrNull(27))), c.getInt(23))
    }
    override fun record(root: String, token: String, document: LibraryDocument, audio: LibraryAudio?) {
        transaction { db ->
            if (!current(db, root, token)) return@transaction
            val details = ContentValues().apply {
                put("id", document.key); put("title", audio?.title ?: document.name)
                if (audio != null) { put("artist", audio.artist); put("album", audio.album); put("duration", audio.durationUs); put("codec", audio.codec); put("rate", audio.rate); put("bits", audio.bits); put("channels", audio.channels) }
                if (audio != null) {
                    val tags = audio.musicTags
                    put("album_artist", tags.albumArtist); put("track_no", tags.trackNumber); put("track_total", tags.totalTracks)
                    put("disc_no", tags.discNumber); put("disc_total", tags.totalDiscs); put("year", tags.year)
                    put("genre", tags.genre); put("composer", tags.composer); put("compilation", if (tags.compilation) 1 else 0)
                    put("tags_version", LIBRARY_TAGS_VERSION)
                    val sort=audio.sortTags
                    put("sort_title",sort.title);put("sort_artist",sort.artist);put("sort_album",sort.album);put("sort_album_artist",sort.albumArtist)
                }
            }
            if (audio == null) {
                details.put("added_at",nowMs().coerceAtLeast(0));db.insertWithOnConflict("tracks", null, details, SQLiteDatabase.CONFLICT_IGNORE)
            }else if (db.update("tracks", details, "id=?", arrayOf(document.key)) == 0) {
                details.put("added_at",nowMs().coerceAtLeast(0));db.insertOrThrow("tracks", null, details)
            }
            val file = ContentValues().apply {
                put("root", root); put("id", document.key); put("document_id", document.id); put("uri", document.uri); put("name", document.name)
                put("size", document.size); put("modified", document.modified); put("token", token); put("present", 1); put("readable", if (audio == null) 0 else 1)
            }
            db.insertWithOnConflict("files", null, file, SQLiteDatabase.CONFLICT_REPLACE).also { check(it >= 0) }
            // Shown again unless this scan's sheet pass still covers it.
            db.execSQL("UPDATE tracks SET cue_hidden=0 WHERE id=?", arrayOf(document.key))
        }
    }
    override fun recordCue(root: String, token: String, file: LibraryDocument, audio: LibraryAudio, sheet: CueSheet, ranges: List<CueRange>) {
        transaction { db ->
            if (!current(db, root, token)) return@transaction
            db.execSQL("UPDATE tracks SET cue_hidden=1 WHERE id=?", arrayOf(file.key))
            val tags = audio.musicTags
            val totalTracks = sheet.totalTracks
            for (range in ranges) {
                val id = MessageDigest.getInstance("SHA-256").digest((file.key + "\u0000cue\u0000" + range.track.number).toByteArray())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                val frames = range.endFrame?.minus(range.startFrame) ?: audio.durationUs?.let { ((it * audio.rate + 500_000) / 1_000_000 - range.startFrame).coerceAtLeast(0) }
                val details = ContentValues().apply {
                    put("id", id); put("title", range.track.title ?: "Track ${range.track.number}")
                    put("artist", range.track.performer ?: sheet.performer ?: audio.artist); put("album", sheet.title ?: audio.album)
                    put("duration", frames?.let { it * 1_000_000 / audio.rate }); put("codec", audio.codec); put("rate", audio.rate)
                    put("bits", audio.bits); put("channels", audio.channels)
                    put("album_artist", sheet.performer ?: tags.albumArtist); put("track_no", range.track.number)
                    put("track_total", totalTracks); put("disc_no", tags.discNumber); put("disc_total", tags.totalDiscs)
                    put("year", tags.year); put("genre", tags.genre); put("composer", tags.composer); put("compilation", if (tags.compilation) 1 else 0)
                    put("tags_version", LIBRARY_TAGS_VERSION); put("start_frame", range.startFrame); put("end_frame", range.endFrame)
                }
                if (db.update("tracks", details, "id=?", arrayOf(id)) == 0) { details.put("added_at", nowMs().coerceAtLeast(0)); db.insertOrThrow("tracks", null, details) }
                val entry = ContentValues().apply {
                    put("root", root); put("id", id); put("document_id", file.id); put("uri", file.uri); put("name", file.name)
                    put("size", file.size); put("modified", file.modified); put("token", token); put("present", 1); put("readable", 1)
                }
                db.insertWithOnConflict("files", null, entry, SQLiteDatabase.CONFLICT_REPLACE).also { check(it >= 0) }
            }
        }
    }
    override fun exclude(root: String, token: String, key: String) {
        transaction { db ->
            if (!current(db, root, token)) return@transaction
            db.delete("files", "root=? AND id=?", arrayOf(root, key))
            db.delete("tracks", "id=? AND NOT EXISTS(SELECT 1 FROM files WHERE files.id=tracks.id)", arrayOf(key))
        }
    }
    override fun finish(root: String, token: String) {
        transaction { db ->
            if (!current(db, root, token)) return@transaction
            db.execSQL("UPDATE files SET present=0 WHERE root=? AND token!=?", arrayOf(root, token))
            db.execSQL("UPDATE roots SET state='READY' WHERE uri=? AND token=?", arrayOf(root, token))
        }
    }
    override fun interrupt(root: String, token: String, state: LibraryRootState) {
        writableDatabase.execSQL("UPDATE roots SET state=? WHERE uri=? AND token=?", arrayOf(state.name, root, token))
    }
    private fun current(db: SQLiteDatabase, root: String, token: String) = db.rawQuery("SELECT 1 FROM roots WHERE uri=? AND token=?", arrayOf(root, token)).use { it.moveToFirst() }
    private fun transaction(action: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try { action(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
}
private fun Cursor.stringOrNull(column: Int): String? = if (isNull(column)) null else getString(column)
private fun Cursor.longOrNull(column: Int): Long? = if (isNull(column)) null else getLong(column)
private fun tagColumn(kind:LibraryGroupKind):String=when(kind) {
    LibraryGroupKind.ALBUM -> "album"
    LibraryGroupKind.ARTIST -> "artist"
    LibraryGroupKind.GENRE -> "genre"
    LibraryGroupKind.COMPOSER -> "composer"
    LibraryGroupKind.FOLDER -> error("Folder is not a tag group")
}
private const val musicTagColumns = "t.album_artist,t.track_no,t.track_total,t.disc_no,t.disc_total,t.year,t.genre,t.composer,t.compilation"
private const val albumOrder = "(t.disc_no IS NULL),t.disc_no,(t.track_no IS NULL),t.track_no,COALESCE(NULLIF(t.sort_title,''),t.title) COLLATE NOCASE,t.id"
private fun Cursor.musicTags(start: Int): MusicTags {
    val track = longOrNull(start + 1)?.toInt()
    // Older CUE rows may have stored an audio count below the raw track number; keep them queueable before a rescan.
    val total = longOrNull(start + 2)?.toInt()?.takeIf { track == null || it >= track }
    return MusicTags(stringOrNull(start), track, total, longOrNull(start+3)?.toInt(), longOrNull(start+4)?.toInt(),
        longOrNull(start+5)?.toInt(), stringOrNull(start+6), stringOrNull(start+7), getInt(start+8) != 0)
}
