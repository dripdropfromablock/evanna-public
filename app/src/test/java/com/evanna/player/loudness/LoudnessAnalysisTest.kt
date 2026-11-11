package com.evanna.player.loudness

import com.evanna.player.usb.dacIdentity
import com.evanna.player.user.DacProfile
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LoudnessAnalysisTest {
    private fun candidate(i:Int)=LoudnessCandidate(i.toString(16).padStart(64,'0'),"a".repeat(64),"content://fixture/$i","Song $i")
    private val measured=LoudnessMeasurement(-23.0,0.1,1_000_000)
    private open inner class Source: LoudnessAnalysisSource {
        val pages=mutableMapOf(0 to LoudnessAnalysisPage(listOf(candidate(1),candidate(2)),null))
        val saved=mutableListOf<String>();val attempts=mutableListOf<String>();var waits=false
        override fun waiting()=waits
        override suspend fun page(offset:Int)=pages[offset] ?: error("Unexpected page")
        override suspend fun cached(candidate:LoudnessCandidate)=false
        override suspend fun measure(candidate:LoudnessCandidate):LoudnessMeasurement {attempts+=candidate.id;return measured}
        override suspend fun saveIfCurrent(candidate:LoudnessCandidate,value:LoudnessMeasurement):Boolean {saved+=candidate.id;return true}
    }
    @Test fun modeDefaultsAndStoredValuesNeverTurnOnProcessingAccidentally() {
        assertEquals(LevelingMode.OFF,DacProfile(dacIdentity(1,2,"DAC")).levelingMode)
        assertEquals(LevelingMode.OFF,storedLevelingMode("unknown"));assertEquals(LevelingMode.OFF,storedLevelingMode(null))
        assertEquals(LevelingMode.ALBUM,storedLevelingMode("ALBUM"));assertEquals(LevelingMode.TRACK,storedLevelingMode("TRACK"))
    }
    @Test fun recordsAndCandidatesRejectMalformedIdentitiesAndLimits() {
        val valid=LoudnessRecord("a".repeat(64),"b".repeat(64),measured,0)
        assertTrue(runCatching {valid.copy(sourceKey="b".repeat(63))}.isFailure)
        assertTrue(runCatching {valid.copy(trackId="A".repeat(64))}.isFailure)
        assertTrue(runCatching {valid.copy(measuredAt=-1)}.isFailure)
        assertTrue(runCatching {candidate(1).copy(uri="https://remote/file")}.isFailure)
    }
    @Test fun onlyKnownUnchangedProviderStampsCanAuthorizeSavedMeasurements() {
        assertTrue(loudnessStampMatches(100,200,100,200))
        assertFalse(loudnessStampMatches(100,200,101,200));assertFalse(loudnessStampMatches(100,200,100,201))
        assertFalse(loudnessStampMatches(null,200,100,200));assertFalse(loudnessStampMatches(100,200,null,200))
        assertFalse(loudnessStampMatches(100,0,100,0));assertFalse(loudnessStampMatches(0,200,0,200))
    }
    @Test fun filteredEmptyPagesContinueAndCurrentMeasurementsAreCached() = runBlocking {
        val source=object:Source() {override suspend fun cached(candidate:LoudnessCandidate)=candidate.id==candidate(1).id}
        source.pages.clear();source.pages[0]=LoudnessAnalysisPage(emptyList(),100)
        source.pages[100]=LoudnessAnalysisPage(listOf(candidate(1),candidate(2)),null)
        val done=runLoudnessAnalysis(source)
        assertEquals(LoudnessAnalysisState.DONE,done.state);assertEquals(2,done.checked)
        assertEquals(1,done.cached);assertEquals(1,done.measured);assertEquals(listOf(candidate(2).id),source.saved)
    }
    @Test fun startupInterruptionRetriesSameFileAndFailuresDoNotCountAsMeasurements() = runBlocking {
        val source=object:Source() {
            override suspend fun measure(candidate:LoudnessCandidate):LoudnessMeasurement {
                attempts+=candidate.id
                if(attempts.size==1)throw LoudnessStartupInterrupted()
                if(candidate.id==candidate(2).id)throw IOException("Unreadable fixture")
                return measured
            }
        }
        val done=runLoudnessAnalysis(source)
        assertEquals(2,done.checked);assertEquals(1,done.measured);assertEquals(1,done.failed)
        assertEquals(listOf(candidate(1).id,candidate(1).id,candidate(2).id),source.attempts)
    }
    @Test fun waitingAndChangedSourcesDoNotSaveStaleResults() = runBlocking {
        val source=object:Source() {override suspend fun saveIfCurrent(candidate:LoudnessCandidate,value:LoudnessMeasurement)=false}
        source.waits=true;var waited=false
        val done=runLoudnessAnalysis(source) {if(it.state==LoudnessAnalysisState.WAITING){waited=true;source.waits=false}}
        assertTrue(waited);assertEquals(2,done.changed);assertEquals(0,done.measured)
    }
    @Test fun cancellationUnwindsCurrentFileBeforeAnySave() = runBlocking {
        val started=CompletableDeferred<Unit>();var closed=false
        val source=object:Source() {override suspend fun measure(candidate:LoudnessCandidate):LoudnessMeasurement {
            try {started.complete(Unit);awaitCancellation()}finally {closed=true}
        }}
        val job=launch {runLoudnessAnalysis(source)}
        withTimeout(2000){started.await()};job.cancelAndJoin()
        assertTrue(closed);assertTrue(source.saved.isEmpty())
    }
    @Test fun cancellationAtTheReturnBoundaryCannotStoreACompletedMeasurement() = runBlocking {
        val source=object:Source() {override suspend fun measure(candidate:LoudnessCandidate):LoudnessMeasurement {
            currentCoroutineContext().cancel();return measured
        }}
        val job=launch {runLoudnessAnalysis(source)};job.join()
        assertTrue(job.isCancelled);assertTrue(source.saved.isEmpty())
    }
    @Test fun storageFailureAndInvalidPaginationAbortRatherThanClaimCompletion() = runBlocking {
        val source=object:Source() {override suspend fun saveIfCurrent(candidate:LoudnessCandidate,value:LoudnessMeasurement):Boolean=throw IOException("Fixture full")}
        assertTrue(runCatching {runLoudnessAnalysis(source)}.exceptionOrNull() is IOException)
        source.pages[0]=LoudnessAnalysisPage(emptyList(),0)
        assertTrue(runCatching {runLoudnessAnalysis(source)}.isFailure)
    }
}
