package com.evanna.player.loudness

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import com.evanna.player.decoder.NativeDecoder
import com.evanna.player.decoder.isLocalDocument
import com.evanna.player.library.*
import com.evanna.player.playback.PlaybackRuntime
import com.evanna.player.user.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

private data class AnalysisTarget(val album:AlbumIdentity?=null,val trackId:String?=null) {
    val full get()=album==null && trackId==null
}
private class AnalysisLibraryChanged:IOException()

/** App-owned, opt-in work. Opening this object never scans files or opens an audio output. */
internal object LibraryLoudnessAnalysis {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val state=MutableStateFlow(LoudnessAnalysisProgress())
    val progress=state.asStateFlow()
    private val pending=ArrayDeque<AnalysisTarget>()
    private var current:AnalysisTarget?=null
    private var job:Job?=null
    private var automaticSuppressed=false

    fun start(context:Context) {val app=context.applicationContext;scope.launch {automaticSuppressed=false;enqueue(app,AnalysisTarget())}}
    fun requestAlbum(context:Context,album:AlbumIdentity) {
        if(album.title==null)return
        val app=context.applicationContext;scope.launch {if(!automaticSuppressed)enqueue(app,AnalysisTarget(album))}
    }
    fun requestTrack(context:Context,id:String) {
        if(!validLoudnessKey(id))return
        val app=context.applicationContext;scope.launch {if(!automaticSuppressed)enqueue(app,AnalysisTarget(trackId=id))}
    }
    fun allowAutomatic() {scope.launch {automaticSuppressed=false}}
    fun cancel() {scope.launch {automaticSuppressed=true;pending.clear();job?.cancel()}}

    private fun enqueue(app:Context,target:AnalysisTarget) {
        val previous=job
        if(previous!=null && !previous.isActive && !previous.isCompleted)return
        if(current?.full==true || pending.any {it.full} || target==current || target in pending)return
        if(target.full)pending.clear()
        if(pending.size>=16)return
        pending.add(target)
        if(previous?.isActive==true)return
        job=scope.launch {
            try {
                while(pending.isNotEmpty()) {
                    val next=pending.removeFirst();current=next
                    val source=LibraryAnalysisSource(app,next)
                    withContext(Dispatchers.IO){runLoudnessAnalysis(source){state.value=it}}
                }
            }catch(cancelled:CancellationException) {
                state.value=state.value.copy(state=LoudnessAnalysisState.CANCELLED,message="Analysis cancelled. Saved measurements were kept.")
                throw cancelled
            }catch(_:AnalysisLibraryChanged) {
                pending.clear();state.value=state.value.copy(state=LoudnessAnalysisState.ERROR,message="Library changed. Saved measurements were kept; resume to continue.")
            }catch(_:Exception) {
                pending.clear();state.value=state.value.copy(state=LoudnessAnalysisState.ERROR,message="Analysis stopped. Saved measurements were kept; check access and free storage, then resume.")
            }finally {current=null;job=null}
        }
    }
}

private class LibraryAnalysisSource(private val context:Context,private val target:AnalysisTarget):LoudnessAnalysisSource {
    private val database=LocalLibrary.database(context)
    private val personal=UserData.database(context)
    private val revision=LocalLibrary.scansFinished
    override fun waiting()=LocalLibrary.indexing || PlaybackRuntime.isUsbPreparing()
    private fun unchangedLibrary() {if(LocalLibrary.scansFinished!=revision)throw AnalysisLibraryChanged()}
    override suspend fun page(offset:Int):LoudnessAnalysisPage {
        unchangedLibrary()
        val page=if(target.trackId!=null) {
            val song=if(offset==0)database.song(target.trackId) else null
            LibraryPage(emptyList(),listOfNotNull(song),if(song!=null)1 else 0)
        }else database.page(offset=offset,filter=target.album?.let {LibraryFilter(kind=LibraryGroupKind.ALBUM,value=it.title,albumArtist=it.albumArtist)} ?: LibraryFilter())
        val candidates=page.songs.mapNotNull {song ->
            val source=database.artworkSource(song.id)
            // Analysis decodes whole files, so CUE songs (part of a file) are skipped rather than mislabeled.
            if(!song.available || song.startFrame!=null || source?.available!=true)null else LoudnessCandidate(song.id,source.key,source.uri,song.title.take(512))
        }
        unchangedLibrary()
        return LoudnessAnalysisPage(candidates,nextLibraryPage(offset,page.songs.size,page.total))
    }
    private suspend fun current(candidate:LoudnessCandidate):Boolean {
        unchangedLibrary()
        return isCurrentLoudnessCandidate(context,candidate)
    }
    override suspend fun cached(candidate:LoudnessCandidate):Boolean {
        unchangedLibrary()
        return current(candidate) && personal.loudness(candidate.id,candidate.sourceKey)!=null
    }
    override suspend fun saveIfCurrent(candidate:LoudnessCandidate,value:LoudnessMeasurement):Boolean {
        if(!current(candidate))return false
        if(!personal.saveLoudness(LoudnessRecord(candidate.id,candidate.sourceKey,value,System.currentTimeMillis().coerceAtLeast(0))))throw IOException("LOUDNESS_STORAGE_FAILED")
        return true
    }
    override suspend fun measure(candidate:LoudnessCandidate):LoudnessMeasurement {
        if(!current(candidate))throw IOException("LOUDNESS_SOURCE_CHANGED")
        return coroutineScope {
            val signal=CancellationSignal()
            val decoder=AtomicReference<NativeDecoder?>(null)
            val interrupted=AtomicBoolean(false)
            fun interrupt() {runCatching {signal.cancel()};runCatching {decoder.get()?.requestCancel()}}
            val monitor=launch {
                while(isActive) {
                    if(waiting()){interrupted.set(true);interrupt();break}
                    delay(50)
                }
            }
            try {
                suspendCancellableCoroutine {continuation ->
                    continuation.invokeOnCancellation {interrupt()}
                    try {
                        val result=context.contentResolver.openFileDescriptor(Uri.parse(candidate.uri),"r",signal)?.use {fd ->
                            NativeDecoder.open(fd.fd).use {active ->
                                decoder.set(active)
                                if(interrupted.get() || !continuation.isActive)active.requestCancel()
                                active.measureLoudness()
                            }
                        } ?: throw IOException("DOCUMENT_UNAVAILABLE")
                        continuation.resume(result)
                    }catch(failure:Exception) {continuation.resumeWithException(if(interrupted.get())LoudnessStartupInterrupted() else failure)}
                    finally {decoder.set(null)}
                }
            }finally {monitor.cancelAndJoin()}
        }
    }
}

/** Used at analysis save and playback preparation; only current granted provider versions qualify. */
internal suspend fun isCurrentLoudnessCandidate(context:Context,candidate:LoudnessCandidate):Boolean {
        val database=LocalLibrary.database(context)
        val source=database.artworkSource(candidate.id) ?: return false
        val uri=Uri.parse(candidate.uri)
        if(!source.available || source.key!=candidate.sourceKey || source.uri!=candidate.uri || !isLocalDocument(context,uri) || !DocumentsContract.isTreeUri(uri))return false
        val tree=DocumentsContract.buildTreeDocumentUri(uri.authority!!,DocumentsContract.getTreeDocumentId(uri))
        if(!hasTreeReadGrant(context,tree))return false
        val saved=database.cached(tree.toString(),candidate.id)?.document ?: return false
        if(saved.uri!=candidate.uri)return false
        return suspendCancellableCoroutine {continuation ->
            val signal=CancellationSignal()
            continuation.invokeOnCancellation {signal.cancel()}
            try {
                val matches=context.contentResolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED),null,null,null,signal)?.use {cursor ->
                    if(cursor.count!=1 || !cursor.moveToFirst() || cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING,false))false
                    else loudnessStampMatches(saved.size,saved.modified,if(cursor.isNull(0))null else cursor.getLong(0),if(cursor.isNull(1))null else cursor.getLong(1))
                } ?: false
                continuation.resume(matches)
            }catch(failure:Exception){continuation.resumeWithException(failure)}
        }
}
