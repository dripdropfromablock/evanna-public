package com.evanna.player.library

import android.app.Instrumentation
import com.evanna.player.decoder.MusicTags

internal object ArtistLibraryProbe {
    fun run(instrumentation:Instrumentation):String {
        val context=instrumentation.targetContext;val name="artist-library-probe.db"
        context.deleteDatabase(name)
        try {LibraryDatabase(context,name).use {store ->
            store.addRoot("r","Fixture",false);store.begin("r","scan")
            fun song(i:Int,artist:String?,album:String?,year:Int?,band:String?="Band",track:Int=1) {
                val id=i.toString(16).padStart(64,'0')
                store.record("r","scan",LibraryDocument(id,id,"content://fixture/$id","$i.flac","audio/flac",1,1),
                    LibraryAudio("Song $i",artist,album,1000,"FLAC",44100,24,2,MusicTags(albumArtist=band,trackNumber=track,year=year)))
            }
            song(1,"Artist","New",2024);song(2,"Artist","Old",1990,track=2);song(3,"Artist","Old",1990,track=1)
            song(4,"Other","Unrelated",1980);song(5,"Artist","No year",null)
            song(6,null,"Unknown artist album",2000);song(7,"Artist","Old",2001,"Other band")
            song(8,"Artist","Mixed year",2000);song(9,"Artist","Mixed year",2002)
            store.finish("r","scan")
            val page=store.albumsForArtist("Artist")
            check(page.total==5)
            check(page.groups.map {it.year}==listOf(1990,2001,2024,null,null))
            check(page.groups.filter {it.value=="Old"}.map {it.albumArtist}.toSet()==setOf("Band","Other band"))
            check(store.albumsForArtist("Artist",limit=1,offset=1).groups.single().albumArtist=="Other band")
            check(store.albumsForArtist(null).groups.single().value=="Unknown artist album")
            check(store.albumsForArtist("Artist",search="Song 1").groups.single().value=="New")
            val queue=store.queueWindow(LibraryFilter(kind=LibraryGroupKind.ARTIST,value="Artist"),null) ?: error("Missing artist queue")
            check(queue.songs.take(4).map {it.id}==listOf(3,2,7,1).map {it.toString(16).padStart(64,'0')})
            check(queue.songs.none {it.artist!="Artist"})
        };return "Disposable artist album/year/identity/paging/search/unknown-artist and chronological queue checks pass. No owner state or sound changed."
        }finally {context.deleteDatabase(name)}
    }
}
