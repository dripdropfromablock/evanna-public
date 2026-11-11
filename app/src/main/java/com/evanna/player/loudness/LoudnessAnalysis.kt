package com.evanna.player.loudness

import java.io.IOException
import kotlinx.coroutines.*

internal enum class LevelingMode { OFF,ALBUM,TRACK }
internal fun storedLevelingMode(value:String?):LevelingMode=LevelingMode.entries.firstOrNull {it.name==value} ?: LevelingMode.OFF
internal fun validLoudnessKey(value:String)=value.matches(Regex("[0-9a-f]{64}"))
internal fun loudnessStampMatches(size:Long?,modified:Long?,actualSize:Long?,actualModified:Long?):Boolean =
    size!=null && size>0 && modified!=null && modified>0 && size==actualSize && modified==actualModified
internal data class LoudnessRecord(val trackId:String,val sourceKey:String,val measurement:LoudnessMeasurement,val measuredAt:Long) {
    init {require(validLoudnessKey(trackId) && validLoudnessKey(sourceKey) && measuredAt>=0)}
}
internal data class LoudnessCandidate(val id:String,val sourceKey:String,val uri:String,val title:String) {
    init {require(validLoudnessKey(id) && validLoudnessKey(sourceKey) && uri.startsWith("content://") && uri.length<=8192 && title.length<=512)}
}
internal data class LoudnessAnalysisPage(val candidates:List<LoudnessCandidate>,val nextOffset:Int?)
internal enum class LoudnessAnalysisState { IDLE,RUNNING,WAITING,DONE,CANCELLED,ERROR }
internal data class LoudnessAnalysisProgress(val state:LoudnessAnalysisState=LoudnessAnalysisState.IDLE,val checked:Int=0,
    val measured:Int=0,val cached:Int=0,val changed:Int=0,val failed:Int=0,val message:String?=null)
internal class LoudnessStartupInterrupted:IOException()
internal interface LoudnessAnalysisSource {
    fun waiting():Boolean
    suspend fun page(offset:Int):LoudnessAnalysisPage
    suspend fun cached(candidate:LoudnessCandidate):Boolean
    suspend fun measure(candidate:LoudnessCandidate):LoudnessMeasurement
    suspend fun saveIfCurrent(candidate:LoudnessCandidate,value:LoudnessMeasurement):Boolean
}

/** Storage failures abort; individual decode failures do not discard already measured files. */
internal suspend fun runLoudnessAnalysis(source:LoudnessAnalysisSource,progress:(LoudnessAnalysisProgress)->Unit={}):LoudnessAnalysisProgress {
    var state=LoudnessAnalysisProgress(LoudnessAnalysisState.RUNNING)
    var offset=0
    suspend fun ready() {
        while(source.waiting()) {
            currentCoroutineContext().ensureActive()
            state=state.copy(state=LoudnessAnalysisState.WAITING);progress(state);delay(100)
        }
        currentCoroutineContext().ensureActive()
        state=state.copy(state=LoudnessAnalysisState.RUNNING);progress(state)
    }
    progress(state)
    while(true) {
        ready()
        val page=source.page(offset)
        require(page.candidates.size<=100 && (page.nextOffset==null || page.nextOffset in (offset+1)..1_000_000))
        for(candidate in page.candidates) {
            ready()
            if(source.cached(candidate))state=state.copy(checked=state.checked+1,cached=state.cached+1)
            else {
                var value:LoudnessMeasurement?=null
                while(true) {
                    ready()
                    try {value=source.measure(candidate);break}
                    catch(cancelled:CancellationException){throw cancelled}
                    catch(_:LoudnessStartupInterrupted){delay(50)}
                    catch(_:IOException){break}
                    catch(_:SecurityException){break}
                }
                currentCoroutineContext().ensureActive()
                state=when {
                    value==null -> state.copy(checked=state.checked+1,failed=state.failed+1)
                    source.saveIfCurrent(candidate,value) -> state.copy(checked=state.checked+1,measured=state.measured+1)
                    else -> state.copy(checked=state.checked+1,changed=state.changed+1)
                }
            }
            progress(state)
        }
        offset=page.nextOffset ?: break
    }
    return state.copy(state=LoudnessAnalysisState.DONE).also(progress)
}
