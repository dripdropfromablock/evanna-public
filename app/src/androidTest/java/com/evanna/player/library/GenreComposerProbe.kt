package com.evanna.player.library

import android.app.Instrumentation
import com.evanna.player.decoder.MusicTags

internal object GenreComposerProbe {
    fun run(instrumentation:Instrumentation):String {
        val context=instrumentation.targetContext;val name="genre-composer-probe.db";context.deleteDatabase(name)
        try {LibraryDatabase(context,name).use {store ->
            store.addRoot("r","Fixture",false);store.begin("r","scan")
            val tags=listOf(MusicTags(genre="Rock",composer="Writer A"),MusicTags(genre="Rock",composer="Writer B"),
                MusicTags(genre="Jazz",composer="Writer A"),MusicTags(),MusicTags(genre="100%_literal",composer="Writer A"))
            tags.forEachIndexed {i,tags ->
                val id=i.toString(16).padStart(64,'0')
                store.record("r","scan",LibraryDocument(id,id,"content://fixture/$id","$i.flac","audio/flac",1,1),
                    LibraryAudio("Song $i","Artist","Album",1000,"FLAC",44100,24,2,tags))
            };store.finish("r","scan")
            check(store.groups(LibraryGroupKind.GENRE).groups.associate {it.value to it.count}==mapOf(null to 1,"Jazz" to 1,"Rock" to 2,"100%_literal" to 1))
            check(store.groups(LibraryGroupKind.COMPOSER).groups.associate {it.value to it.count}==mapOf(null to 1,"Writer A" to 3,"Writer B" to 1))
            check(store.groups(LibraryGroupKind.GENRE,search="100%_").groups.single().value=="100%_literal")
            check(store.page(filter=LibraryFilter(search="Writer B")).songs.single().musicTags.composer=="Writer B")
            check(store.groups(LibraryGroupKind.COMPOSER,limit=1,offset=1).groups.single().value=="Writer A")
            for((kind,value,count) in listOf(Triple(LibraryGroupKind.GENRE,"Rock",2),Triple(LibraryGroupKind.COMPOSER,"Writer A",3),Triple(LibraryGroupKind.GENRE,null,1))) {
                val filter=LibraryFilter(kind=kind,value=value)
                check(store.page(filter=filter).total==count)
                check(store.queueWindow(filter,null)?.songs?.size==count)
                check(store.page(filter=filter).songs.all {if(kind==LibraryGroupKind.GENRE)it.musicTags.genre==value else it.musicTags.composer==value})
            }
        };return "Disposable genre/composer/null/count/escaped-search/paging/exact queue checks pass. Owner data, grants and audio unchanged."
        }finally {context.deleteDatabase(name)}
    }
}
