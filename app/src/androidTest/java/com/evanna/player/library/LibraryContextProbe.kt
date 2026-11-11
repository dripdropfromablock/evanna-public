package com.evanna.player.library

import android.app.Instrumentation
import android.os.SystemClock
import com.evanna.player.decoder.MusicTags

/** Queue query coverage in an isolated index; no saved queue, grant or output access. */
internal object LibraryContextProbe {
    fun run(instrumentation: Instrumentation): String {
        val context=instrumentation.targetContext
        val name="library-context-probe.db"
        context.deleteDatabase(name)
        fun id(n:Int)=n.toString(16).padStart(64,'0')
        try {
            LibraryDatabase(context,name).use { db ->
                db.addRoot("root","Context fixtures",false);db.begin("root","seed")
                val sql=db.writableDatabase;sql.beginTransaction()
                try {
                    repeat(2164) { n ->
                        val doc=LibraryDocument("$n",id(n),"content://test/$n","$n.flac","audio/flac",10,20)
                        val album=if(n<18)"Test album" else "Bulk"
                        val tags=MusicTags(if(n<17)"Artist A" else "Artist B",if(n<17)n+1 else 1,discNumber=1)
                        db.record("root","seed",doc,LibraryAudio(if(n==2163)"literal%_song" else "%04d".format(n),"Artist",album,1000,"FLAC",44100,24,2,tags))
                    }
                    sql.setTransactionSuccessful()
                } finally {sql.endTransaction()}
                db.finish("root","seed")
                val start=SystemClock.elapsedRealtime()
                for (index in listOf(0,999,1000,1500,2163)) {
                    val window=checkNotNull(db.queueWindow(LibraryFilter(),id(index)))
                    check(window.startIndex==0 && window.truncated)
                    check(window.songs.map {it.id} == (index until minOf(2164,index+1000)).map(::id)) {"Selected item was capped out at $index"}
                }
                val album=LibraryFilter(kind=LibraryGroupKind.ALBUM,value="Test album",albumArtist="Artist A")
                val selected=checkNotNull(db.queueWindow(album,id(2)))
                check(!selected.truncated && selected.startIndex==2 && selected.songs.map {it.id} == (0..16).map(::id))
                check(db.queueWindow(album,id(17))==null)
                check(db.queueWindow(album.copy(albumArtist="Artist B"),id(17))?.songs?.single()?.id==id(17))
                check(db.queueWindow(LibraryFilter(search="%_"),id(2163))?.songs?.single()?.id==id(2163))
                check(db.queueWindow(LibraryFilter(kind=LibraryGroupKind.FOLDER,value="root"),id(1500))?.songs?.first()?.id==id(1500))
                check(db.queueWindow(LibraryFilter(kind=LibraryGroupKind.ARTIST,value="Artist"),id(2))!=null)
                check(db.queueWindow(LibraryFilter(kind=LibraryGroupKind.FOLDER,value="missing"),id(2))==null)
                check(db.queueWindow(album,id(2),17)?.let {!it.truncated && it.startIndex==2 && it.songs.size==17}==true)
                check(db.queueWindow(album,id(2),16)?.let {it.truncated && it.startIndex==0 && it.songs.size==15}==true)
                check(db.queueWindow(LibraryFilter(),id(9999))==null)
                sql.execSQL("UPDATE files SET readable=0 WHERE id=?",arrayOf(id(0)))
                check(db.queueWindow(LibraryFilter(),id(0))==null)
                check(db.queueWindow(LibraryFilter(),null)?.songs?.first()?.id==id(1))
                check(db.queueWindow(LibraryFilter(),id(1),limit=0)==null)
                // A concurrent rescan may change order/size together; a window must contain one committed generation.
                sql.execSQL("UPDATE tracks SET title='before:' || title WHERE album='Test album'")
                val writeFailure=java.util.concurrent.atomic.AtomicReference<Throwable>()
                val writer=Thread {
                    try { repeat(10) {generation->
                        sql.beginTransaction()
                        try {
                            sql.execSQL("UPDATE tracks SET title=? || substr(title,instr(title,':')+1) WHERE album='Test album'",arrayOf("g$generation:"))
                            sql.setTransactionSuccessful()
                        } finally {sql.endTransaction()}
                    } } catch(failure:Throwable) {writeFailure.set(failure)}
                }
                writer.start()
                try {
                    repeat(10) {
                        val snapshot=checkNotNull(db.queueWindow(album,id(2)))
                        check(snapshot.songs.size==17 && snapshot.songs.map {it.title.substringBefore(':')}.distinct().size==1)
                    }
                } finally {writer.join(10000);check(!writer.isAlive)}
                check(writeFailure.get()==null)
                return "Context queue windows for 2164 rows: anchors 0/999/1000/1500/last, album order/identity, literal search, artist/folder, unavailable/missing anchors and header start pass in ${SystemClock.elapsedRealtime()-start}ms; no playback or saved-data change."
            }
        } finally {context.deleteDatabase(name)}
    }
}
