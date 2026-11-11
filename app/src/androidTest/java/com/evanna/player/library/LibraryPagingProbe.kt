package com.evanna.player.library

import android.app.Instrumentation

/** Disposable 10k-row index; no owner library, grants or audio is touched. */
internal object LibraryPagingProbe {
    fun run(instrumentation:Instrumentation):String {
        val context=instrumentation.targetContext;val name="library-paging-probe.db"
        context.deleteDatabase(name)
        try {LibraryDatabase(context,name).use {store ->
            val db=store.writableDatabase
            db.beginTransaction()
            try {
                db.execSQL("INSERT INTO roots VALUES('r','Fixture','READY',NULL,0)")
                val track=db.compileStatement("INSERT INTO tracks(id,title,artist,album,album_artist,sort_title,sort_artist,sort_album) VALUES(?,?,?,?,?,?,?,?)")
                val file=db.compileStatement("INSERT INTO files VALUES('r',?,?,?,'fixture.flac',1,1,'scan',1,1)")
                track.use {t -> file.use {f -> for(i in 0 until 10000) {
                    val id=i.toString(16).padStart(64,'0');val letter=('A'.code+i/400).toChar().toString()
                    val key=letter+i.toString().padStart(5,'0')
                    t.clearBindings();t.bindString(1,id);t.bindString(2,"The $key");t.bindString(3,"Artist $key")
                    t.bindString(4,"Album $key");t.bindString(5,if(i%2==0)"Band A" else "Band B")
                    t.bindString(6,key);t.bindString(7,key);t.bindString(8,key);t.executeInsert()
                    f.clearBindings();f.bindString(1,id);f.bindString(2,id);f.bindString(3,"content://fixture/$id");f.executeInsert()
                }}}
                db.setTransactionSuccessful()
            }finally {db.endTransaction()}
            for(letter in 'A'..'Z') {
                val expected=((letter-'A')*400).coerceAtMost(10000)
                check(store.letterOffset(letter)==expected)
                check(store.letterOffset(letter,group=LibraryGroupKind.ALBUM)==expected)
                check(store.letterOffset(letter,group=LibraryGroupKind.ARTIST)==expected)
                if(expected<10000)check(store.page(offset=expected).songs.first().title.startsWith("The $letter"))
            }
            check(store.letterOffset('#')==0)
            check(store.letterOffset('M',LibraryFilter(search="The M"))==0)
            check(store.letterOffset('Z',LibraryFilter(search="The M"))==400)
            val ids=(0..9900 step 100).flatMap {store.page(offset=it).songs.map {song->song.id}}
            check(ids.size==10000 && ids.toSet().size==10000)
            // Same visible album title, distinct tagged album identities, plus NULL groups before A.
            db.execSQL("UPDATE tracks SET album='Shared',sort_album='Lemon',album_artist='Band A' WHERE id=?",arrayOf(ids[0]))
            db.execSQL("UPDATE tracks SET album='Shared',sort_album='Lemon',album_artist='Band B' WHERE id=?",arrayOf(ids[1]))
            db.execSQL("UPDATE tracks SET artist=NULL,sort_artist=NULL,album=NULL,sort_album=NULL WHERE id=?",arrayOf(ids[2]))
            check(store.letterOffset('A',group=LibraryGroupKind.ARTIST)==1)
            check(store.letterOffset('A',group=LibraryGroupKind.ALBUM)==1)
            val rank=store.letterOffset('L',group=LibraryGroupKind.ALBUM)
            val albums=store.groups(LibraryGroupKind.ALBUM,offset=rank,limit=1000).groups
            check(albums.filter {it.value=="Shared"}.map {it.albumArtist}.toSet()==setOf("Band A","Band B"))
            // Case must not move a jump: a small "b" key stays with B, and "_" sorts before A (Android 11 once counted by case).
            db.execSQL("UPDATE tracks SET sort_title='b'||substr(sort_title,2) WHERE id=?",arrayOf(ids[400]))
            db.execSQL("UPDATE tracks SET sort_title='_'||sort_title WHERE id=?",arrayOf(ids[800]))
            check(store.letterOffset('A')==1 && store.letterOffset('C')==801 && store.letterOffset('D')==1200)
            check(store.page(offset=801).songs.first().title=="The C00801")
            check(runCatching {store.letterOffset('a')}.isFailure)
            check(runCatching {store.letterOffset('A',LibraryFilter(sort=LibrarySort.ADDED))}.isFailure)
        }
            return "Disposable 10,000-row title/sort-hint/search/group-prefix and complete paging checks pass; exact album identities and unknown groups retained. No owner state or audio changed."
        }finally {context.deleteDatabase(name)}
    }
}
