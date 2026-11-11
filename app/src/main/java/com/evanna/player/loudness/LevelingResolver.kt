package com.evanna.player.loudness

import android.content.Context
import com.evanna.player.library.*
import com.evanna.player.playback.PcmTrack
import com.evanna.player.user.*
import kotlinx.coroutines.*

/** File analysis is separate; this path reads only validated tags and current saved results. */
internal suspend fun resolveLeveling(context:Context,mode:LevelingMode,id:String?,track:PcmTrack):LevelingDecision = withContext(Dispatchers.IO) {
    val tagged=levelingDecision(mode,track.loudnessTags)
    if(mode==LevelingMode.OFF || (if(mode==LevelingMode.TRACK)track.loudnessTags.trackDb else track.loudnessTags.albumDb)!=null)return@withContext tagged
    if(id==null || !validLoudnessKey(id) || LocalLibrary.indexing)return@withContext tagged
    try {
        val database=LocalLibrary.database(context);val personal=UserData.database(context)
        val revision=LocalLibrary.scansFinished
        suspend fun measurement(song:LibrarySong):LoudnessMeasurement? {
            // A CUE song is part of a file; a whole-file measurement must not pass as its own.
            if(!song.available || song.startFrame!=null)return null
            val source=database.artworkSource(song.id)?.takeIf {it.available} ?: return null
            val cached=personal.loudness(song.id,source.key) ?: return null
            val candidate=LoudnessCandidate(song.id,source.key,source.uri,song.title.take(512))
            return cached.measurement.takeIf {isCurrentLoudnessCandidate(context,candidate)}
        }
        val selected=database.song(id) ?: return@withContext tagged
        val result=if(mode==LevelingMode.TRACK)levelingDecision(mode,track.loudnessTags,measurement(selected))
        else {
            val title=selected.album ?: return@withContext tagged
            val filter=LibraryFilter(kind=LibraryGroupKind.ALBUM,value=title,albumArtist=selected.musicTags.albumArtist)
            val values=mutableListOf<LoudnessMeasurement?>();var offset=0
            while(true) {
                currentCoroutineContext().ensureActive()
                val page=database.page(offset=offset,filter=filter)
                if(page.total>100000)return@withContext tagged
                for(song in page.songs)values+=measurement(song) ?: return@withContext tagged
                offset=nextLibraryPage(offset,page.songs.size,page.total) ?: break
            }
            levelingDecision(mode,track.loudnessTags,album=values)
        }
        if(LocalLibrary.indexing || LocalLibrary.scansFinished!=revision)tagged else result
    }catch(cancelled:CancellationException){throw cancelled}
    catch(_:Exception){LevelingDecision(mode,0f,"Loudness data unavailable; no offset")}
}
