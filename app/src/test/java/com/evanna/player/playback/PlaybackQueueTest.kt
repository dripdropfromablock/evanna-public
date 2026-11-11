package com.evanna.player.playback

import com.truesample.usbaudio.*
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueTest {
    @Test fun favoriteChangesTargetTheCurrentLibraryOccurrenceAndNeverStartAudio()=runBlocking {
        val output=Output();val owner=UsbSessionOwner(UsbPlaybackController {output},Focus())
        val writes=mutableListOf<String>()
        try {
            owner.enqueue(entry("first").copy(libraryId="a".repeat(64)))
            owner.enqueue(entry("second").copy(libraryId="b".repeat(64)))
            val first=owner.queue.value.current!!.id
            assertTrue(owner.favoriteCurrent(first){writes+=it;true})
            val second=owner.queue.value.items[1].id;owner.selectQueue(second)
            assertFalse(owner.favoriteCurrent(first){writes+=it;true})
            assertFalse(owner.favoriteCurrent(second){false})
            assertTrue(owner.favoriteCurrent(second){writes+=it;true})
            assertEquals(listOf("a".repeat(64),"b".repeat(64)),writes)
            assertEquals(0,output.starts)
            owner.replaceQueue(entry("direct").copy(libraryId=null))
            assertFalse(owner.favoriteCurrent(owner.queue.value.current!!.id){error("No library ID")})
        }finally {owner.close()}
    }
    private val format = TrackPcmFormat(48000, 16, 2, 16, "FLAC", true, 1000000)
    private class Focus : PlaybackFocus {
        override var held = false
        var granted = true
        override fun request(): Boolean { held = granted; return held }
        override fun abandon() { held = false }
    }
    private class Output : UsbPlaybackOutput {
        override val description = "Synthetic queue sink"
        var starts = 0
        var stopped = CountDownLatch(1)
        var writing = CountDownLatch(1)
        var block = false
        override var usbVolume: VolumeRange? = null
        var volumeAction: () -> Unit = {}
        var stopAction: () -> Unit = {}
        override fun setEqualizer(bands: List<EqBand>, preampDb: Float) = Unit
        override fun plan(source: PcmFormat) = PlaybackPlan(source.sampleRate, source.significantBits, false, true)
        override fun prepareVolume() = true
        override fun start(source: PcmFormat): StreamInfo {
            starts++; stopped = CountDownLatch(1); writing = CountDownLatch(1)
            return StreamInfo(source.sampleRate, source.significantBits, source.channels,
                DacFormat(1, 1, 1, 2, (source.significantBits + 7) / 8, source.significantBits, DacFormat.SyncType.SYNC, 512, listOf(source.sampleRate), source.sampleRate, source.sampleRate), source.sampleRate, source.sampleRate, true, false)
        }
        override fun readiness() = UsbPlaybackReadiness(true, true, true)
        override fun setVolume(db: Float): Boolean {
            val previous = usbVolume ?: return false
            volumeAction()
            usbVolume = previous.copy(currentDb = db)
            return true
        }
        override fun stats() = StreamStats(0, 0, 0, false)
        override fun write(buffer: ByteBuffer, frames: Int): Int { writing.countDown(); if (block) { check(stopped.await(5, TimeUnit.SECONDS)); return -1 }; return frames }
        override fun drain() = true
        override fun stop(): Boolean { stopped.countDown(); stopAction(); return true }
        override fun close() = Unit
    }
    private inner class Track(val name: String, pcm: TrackPcmFormat = format, private val endRead: CountDownLatch? = null) : PcmTrack {
        override val id = "evanna:local:$name"
        override val format = pcm
        var closes = 0
        var failClose = false
        override fun open() = object : PcmReader {
            override val format = this@Track.format
            var read = false
            override fun read(target: ByteBuffer, maxFrames: Int): Int { if (read) { endRead?.let { check(it.await(5, TimeUnit.SECONDS)) }; return 0 }; read = true; target.putInt(0,0);target.putInt(4,0);return 1 }
            override fun requestCancel() { endRead?.countDown() }
            override fun close() = Unit
        }
        override fun close() { closes++; check(!failClose) }
    }
    private fun entry(name: String, create: () -> PcmTrack = { Track(name) }) = QueueSource("library:$name", name, null, null, 1000000, create=create)
    private suspend fun ended(owner: UsbSessionOwner) = withTimeout(5000) { owner.snapshot.first { it.state == UsbPlaybackState.ENDED } }

    @Test fun contextSelectsAndPlaysTheTappedOccurrenceWithLazySources() = runBlocking {
        val output=Output().apply { block=true }; val owner=UsbSessionOwner(UsbPlaybackController { output },Focus())
        val opened=mutableListOf<Int>()
        val sources=(1..17).map { n -> entry("Track $n") { opened+=n; Track("$n") } }
        try {
            owner.open(7); owner.setRepeat(QueueRepeat.QUEUE); owner.setShuffle(true)
            assertTrue(owner.libraryContext { ContextQueue(sources,2) })
            assertEquals(17,owner.queue.value.items.size);assertEquals(2,owner.queue.value.index)
            assertEquals("Track 3",owner.queue.value.current?.title);assertEquals(listOf(3),opened)
            assertEquals(QueueRepeat.QUEUE,owner.queue.value.repeat);assertFalse(owner.queue.value.shuffleEnabled)
            assertTrue(output.writing.await(5,TimeUnit.SECONDS));assertEquals(1,output.starts)
        } finally { owner.close() }
    }
    @Test fun acceptedContextDoesNotAuthorizeConversionOrStartAfterOutputChoice() = runBlocking {
        val output=Output();val owner=UsbSessionOwner(UsbPlaybackController {output},Focus())
        val processed=format.copy(codec="MP3",significantBits=32,sourceBits=null,strictEligible=false)
        val sources=listOf(entry("Processed") { Track("processed",processed) },entry("Next"))
        try {
            assertTrue(owner.libraryContext { ContextQueue(sources,0) });assertEquals(0,output.starts)
            assertTrue(owner.open(7));assertEquals(0,output.starts)
            assertTrue(owner.libraryContext { ContextQueue(sources,0) })
            assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED,owner.snapshot.value.failure)
            assertEquals(2,owner.queue.value.items.size);assertEquals(0,output.starts)
            assertFalse(owner.snapshot.value.conversionAllowed)
        } finally { owner.close() }
    }
    @Test fun rejectedContextKeepsQueueAndFailedSourceKeepsAcceptedContext() = runBlocking {
        val owner=UsbSessionOwner(UsbPlaybackController {Output()},Focus())
        try {
            owner.enqueue(entry("Original"));val original=owner.queue.value
            for (selection in listOf(ContextQueue(emptyList(),0),ContextQueue(listOf(entry("x")),1),
                ContextQueue(List(1001) { entry("$it") },0),ContextQueue(listOf(entry("")),0))) {
                assertFalse(owner.libraryContext { selection });assertEquals(original,owner.queue.value)
            }
            assertTrue(owner.libraryContext { ContextQueue(listOf(entry("Missing") { throw java.io.IOException() }),0) })
            assertEquals("Missing",owner.queue.value.current?.title);assertTrue(owner.sourceSelectionFailed)
        } finally { owner.close() }
    }
    @Test fun cancelledContextPreparationRetainsSourceWithoutStarting() = runBlocking {
        val output=Output();val owner=UsbSessionOwner(UsbPlaybackController {output},Focus())
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val track=Track("selected")
        try {
            owner.open(7)
            val selecting=launch(Dispatchers.Default) { owner.libraryContext { ContextQueue(listOf(entry("selected") {
                entered.countDown();check(release.await(5,TimeUnit.SECONDS));track
            }),0) } }
            assertTrue(entered.await(5,TimeUnit.SECONDS));selecting.cancel();release.countDown();selecting.join()
            assertEquals(0,output.starts);assertEquals("selected",owner.queue.value.current?.title)
            assertTrue(owner.sourceSelectionFailed);assertEquals(0,track.closes)
            owner.close();assertEquals(1,track.closes)
        } finally {release.countDown();owner.close()}
    }
    @Test fun contextCannotDiscardSourcesWhoseCleanupFails() = runBlocking {
        val owner=UsbSessionOwner(UsbPlaybackController {Output()},Focus());val old=Track("old").apply {failClose=true}
        try {
            owner.enqueue(entry("old") {old});val before=owner.queue.value
            assertFalse(owner.libraryContext {ContextQueue(listOf(entry("new")),0)})
            assertEquals(before,owner.queue.value);assertTrue(owner.sourceSelectionFailed)
            old.failClose=false;assertTrue(owner.close());assertEquals(2,old.closes)
        } finally {old.failClose=false;owner.close()}
    }
    @Test fun contextAtQueueCapOnlyPreparesCurrentAndShuffleKeepsItFirst() = runBlocking {
        val owner=UsbSessionOwner(UsbPlaybackController {Output()},Focus(),shuffleOrder={it.reversed()})
        var opened=0
        try {
            assertTrue(owner.libraryContext { ContextQueue(List(1000) { n -> entry("$n") {opened++;Track("$n")} },900,true) })
            assertEquals(1,opened);assertEquals(1000,owner.queue.value.items.size)
            assertEquals(owner.queue.value.current!!.id,owner.queue.value.playOrder.first())
            assertEquals(1000,owner.queue.value.playOrder.toSet().size);assertTrue(owner.queue.value.shuffleEnabled)
        } finally { owner.close() }
    }
    @Test fun playNextIsNextInBaseAndShuffleOrderWithoutChangingCurrent() = runBlocking {
        val owner=UsbSessionOwner(UsbPlaybackController {Output()},Focus(),shuffleOrder={it.reversed()})
        try {
            owner.enqueue(entry("Current"));owner.enqueue(entry("Later"));owner.setShuffle(true)
            val current=owner.queue.value.current!!.id
            assertTrue(owner.libraryQueue(true,next=true) {entry("Next")})
            assertEquals(listOf("Current","Next","Later"),owner.queue.value.items.map {it.title})
            assertEquals(current,owner.queue.value.current!!.id)
            assertEquals(1,owner.queue.value.navigationIndex(1))
            owner.setShuffle(false);assertTrue(owner.libraryQueue(true,next=true) {entry("Next again")})
            assertEquals(listOf("Current","Next again","Next","Later"),owner.queue.value.items.map {it.title})
            assertEquals(UsbPlaybackState.CLOSED,owner.snapshot.value.state)
        } finally { owner.close() }
    }
    @Test fun pauseDuringContextLookupCancelsItsPlayIntentBeforeMutation() = runBlocking {
        val owner=UsbSessionOwner(UsbPlaybackController {Output()},Focus())
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            owner.enqueue(entry("Original"));val original=owner.queue.value
            val selecting=async(Dispatchers.Default) { owner.libraryContext { entered.countDown();check(release.await(5,TimeUnit.SECONDS));ContextQueue(listOf(entry("Stale")),0) } }
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val paused=async(start=CoroutineStart.UNDISPATCHED) { owner.pause() }
            release.countDown();assertFalse(selecting.await());assertTrue(paused.await())
            assertEquals(original,owner.queue.value)
        } finally { release.countDown();owner.close() }
    }
    @Test fun laterContextSupersedesSlowLookupAndFocusDenialKeepsQueue() = runBlocking {
        val focus=Focus().apply {granted=false};val output=Output();val owner=UsbSessionOwner(UsbPlaybackController {output},focus)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            owner.open(7)
            val first=async(Dispatchers.Default) { owner.libraryContext {entered.countDown();check(release.await(5,TimeUnit.SECONDS));ContextQueue(listOf(entry("Old")),0)} }
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val latest=async(start=CoroutineStart.UNDISPATCHED) {owner.libraryContext {ContextQueue(listOf(entry("Latest")),0)}}
            release.countDown();assertFalse(first.await());assertTrue(latest.await())
            assertEquals("Latest",owner.queue.value.current?.title);assertEquals(0,output.starts);assertTrue(owner.focusDenied)
        } finally {release.countDown();owner.close()}
    }

    @Test fun appendDuplicatesReorderAndPausedSkipsKeepIdentityWithoutAutoplay() = runBlocking {
        val output = Output(); val owner = UsbSessionOwner(UsbPlaybackController { output }, Focus())
        try {
            assertTrue(owner.enqueue(entry("a"))); assertTrue(owner.enqueue(entry("b"))); assertTrue(owner.enqueue(entry("a")))
            val original = owner.queue.value
            assertEquals(3, original.items.map { it.id }.toSet().size)
            assertEquals(0, original.index); assertEquals(0, output.starts)
            assertTrue(owner.moveQueue(original.items[0].id, 2))
            assertEquals(original.items[0].id, owner.queue.value.current?.id); assertEquals(2, owner.queue.value.index)
            assertTrue(owner.skipQueue(-1)); assertEquals(original.items[2].id, owner.queue.value.current?.id)
            assertFalse(owner.skipQueue(2)); assertEquals(0, output.starts)
            assertTrue(owner.removeQueue(original.items[1].id)); assertEquals(2, owner.queue.value.items.size)
        } finally { owner.close() }
    }
    @Test fun drainedEndAdvancesOnceButPauseStaleEventsAndFocusLossCannotAutoplay() = runBlocking {
        val output=Output();val focus=Focus();val owner=UsbSessionOwner(UsbPlaybackController { output },focus)
        try {
            owner.enqueue(entry("a"));owner.enqueue(entry("b"));owner.open(7);assertTrue(owner.play())
            val first=ended(owner)
            assertTrue(owner.onPlaybackState(first));assertEquals(1,owner.queue.value.index)
            val second=ended(owner);assertEquals(2,output.starts)
            assertFalse(owner.onPlaybackState(first));assertEquals(2,output.starts)
            assertFalse(owner.onPlaybackState(second));assertFalse(focus.held)
            owner.selectQueue(owner.queue.value.items[0].id);assertTrue(owner.play());val again=ended(owner)
            owner.pause();assertFalse(owner.onPlaybackState(again));assertEquals(0,owner.queue.value.index)
            assertTrue(owner.play());val last=ended(owner);focus.abandon()
            assertFalse(owner.onPlaybackState(last));assertEquals(0,owner.queue.value.index)
        } finally { owner.close() }
    }
    @Test fun savedCeilingAfterNaturalEndKeepsFocusForQueueAdvance()=runBlocking {
        val output=Output();val focus=Focus();val owner=UsbSessionOwner(UsbPlaybackController {output},focus)
        try {
            owner.enqueue(entry("first"));owner.enqueue(entry("next"));owner.open(7);assertTrue(owner.play())
            val first=ended(owner);assertTrue(focus.held)
            assertTrue(owner.setVolumeLimit(-20f));assertEquals(-20f,owner.snapshot.value.volumeLimitDb)
            assertTrue(focus.held);assertTrue(owner.onPlaybackState(first))
            assertEquals("next",owner.queue.value.current?.title);assertEquals(2,output.starts)
        } finally {owner.close()}
    }
    @Test fun volumeCompletionDuringNaturalStopKeepsFocusForQueueAdvance() = runBlocking {
        val endRead = CountDownLatch(1)
        val stopping = CountDownLatch(1)
        val finishStop = CountDownLatch(1)
        val output = Output().apply {
            usbVolume = VolumeRange(-60f, 0f, 1f, -20f)
            volumeAction = { endRead.countDown() }
            stopAction = { if (starts == 1) { stopping.countDown(); check(finishStop.await(5, TimeUnit.SECONDS)) } }
        }
        val focus = Focus(); val owner = UsbSessionOwner(UsbPlaybackController { output }, focus)
        try {
            owner.enqueue(entry("first") { Track("first", endRead = endRead) }); owner.enqueue(entry("next"))
            owner.open(7); assertTrue(owner.play()); assertTrue(output.writing.await(5, TimeUnit.SECONDS))
            val changing = async(start = CoroutineStart.UNDISPATCHED) { owner.setUsbVolume(-19f, owner.snapshot.value.generation) }
            // Hold the caller's result until the real writer's final cleanup reaches STOPPING.
            assertTrue(stopping.await(5, TimeUnit.SECONDS))
            assertEquals(UsbPlaybackState.STOPPING, owner.snapshot.value.state)
            assertTrue(withTimeout(5000) { changing.await() })
            assertTrue("Volume completion must leave EOF queue handling in control of focus", focus.held)
            finishStop.countDown()
            assertTrue(owner.onPlaybackState(ended(owner)))
            assertEquals(1, owner.queue.value.index); assertEquals(2, output.starts)
            assertFalse(owner.onPlaybackState(ended(owner))); assertFalse(focus.held)
        } finally { endRead.countDown(); finishStop.countDown(); owner.close() }
    }
    @Test fun refusedVolumeCommandAfterDrainKeepsFocusUntilQueueEndIsHandled() = runBlocking {
        val output = Output(); val focus = Focus(); val owner = UsbSessionOwner(UsbPlaybackController { output }, focus)
        try {
            owner.enqueue(entry("first")); owner.enqueue(entry("next")); owner.open(7); assertTrue(owner.play())
            val first = ended(owner)
            assertFalse(owner.adjustUsbVolume(1, first.generation))
            assertTrue("A refused late volume command must not consume a pending EOF advance", focus.held)
            assertTrue(owner.onPlaybackState(first))
            assertEquals(1, owner.queue.value.index); assertEquals(2, output.starts)
            assertFalse(owner.onPlaybackState(ended(owner))); assertFalse(focus.held)
        } finally { owner.close() }
    }
    @Test fun activeSkipAndRemovalStopJoinThenContinueButBrokenSourceStopsAtEntry() = runBlocking {
        val output=Output().apply { block=true };val owner=UsbSessionOwner(UsbPlaybackController { output },Focus())
        try {
            owner.enqueue(entry("a"));owner.enqueue(entry("b"));owner.enqueue(entry("bad") { throw java.io.IOException() });owner.open(7);assertTrue(owner.play())
            assertTrue(output.writing.await(5,TimeUnit.SECONDS));assertTrue(owner.skipQueue(1))
            assertEquals(1,owner.queue.value.index);assertEquals(2,output.starts)
            assertTrue(output.writing.await(5,TimeUnit.SECONDS))
            assertFalse(owner.removeQueue(owner.queue.value.current!!.id))
            assertEquals("bad",owner.queue.value.current?.title);assertTrue(owner.sourceSelectionFailed)
            assertEquals(2,output.starts);assertFalse(owner.play())
        } finally { owner.close() }
    }
    @Test fun legacySelectionBecomesFirstOccurrenceAndStopReleasesAllPreparedSources() = runBlocking {
        val output=Output();val owner=UsbSessionOwner(UsbPlaybackController { output },Focus());val legacy=Track("legacy");val next=Track("next")
        try {
            owner.selectTrack { legacy };owner.enqueue(entry("next") { next })
            assertEquals(2,owner.queue.value.items.size);assertSame(legacy,owner.selectedTrack)
            assertTrue(owner.skipQueue(1));assertEquals(0,legacy.closes)
            assertTrue(owner.skipQueue(-1));assertSame(legacy,owner.selectedTrack)
            assertTrue(owner.close());assertTrue(owner.queue.value.items.isEmpty());assertEquals(1,legacy.closes);assertEquals(1,next.closes)
        } finally { owner.close() }
    }
    @Test fun onePlayConversionNeverAuthorizesTheNextEntryAndErrorsDoNotAdvance() = runBlocking {
        val output=Output();val owner=UsbSessionOwner(UsbPlaybackController { output },Focus())
        val processed = format.copy(codec="MP3",significantBits=32,sourceBits=null,strictEligible=false)
        fun source(name:String)=entry(name) { Track(name,processed) }
        try {
            owner.enqueue(source("a"));owner.enqueue(source("b"));owner.open(7)
            assertFalse(owner.play());assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED,owner.snapshot.value.failure)
            assertFalse(owner.onPlaybackState(owner.snapshot.value));assertEquals(0,owner.queue.value.index)
            assertTrue(owner.play(true));val first=ended(owner)
            assertFalse(owner.onPlaybackState(first));assertEquals(1,owner.queue.value.index)
            assertEquals(1,output.starts);assertEquals(UsbPlaybackFailure.CONVERSION_REQUIRED,owner.snapshot.value.failure)
            assertFalse(owner.snapshot.value.conversionAllowed)
            assertFalse(owner.onPlaybackState(owner.snapshot.value));assertEquals(1,owner.queue.value.index)
        } finally { owner.close() }
    }
    @Test fun inactiveSourceCleanupFailureDoesNotAbandonFocusForCurrentPlayback() = runBlocking {
        val output=Output().apply {block=true};val focus=Focus();val owner=UsbSessionOwner(UsbPlaybackController {output},focus)
        val first=Track("first")
        try {
            owner.enqueue(entry("first"){first});owner.enqueue(entry("next"));owner.open(7);owner.play()
            assertTrue(output.writing.await(5,TimeUnit.SECONDS));assertTrue(owner.skipQueue(1))
            first.failClose=true
            assertFalse(owner.removeQueue(owner.queue.value.items[0].id))
            assertEquals(UsbPlaybackState.PLAYING,owner.snapshot.value.state)
            assertTrue(focus.held);assertFalse(owner.sourceSelectionFailed)
        }finally{first.failClose=false;owner.close()}
    }
    @Test fun activeItemSeekCannotReacquireFocusLostWhilePreparingItsSource() = runBlocking {
        val output=Output().apply{block=true};val focus=Focus();val owner=UsbSessionOwner(UsbPlaybackController{output},focus)
        try {
            owner.enqueue(entry("first"));owner.enqueue(entry("loss"){focus.abandon();Track("loss")});owner.open(7);owner.play()
            assertTrue(output.writing.await(5,TimeUnit.SECONDS));assertTrue(owner.seekQueue(1,0))
            assertEquals(UsbPlaybackState.PAUSED,owner.snapshot.value.state);assertFalse(focus.held);assertEquals(1,output.starts)
        }finally{owner.close()}
    }
    @Test fun pendingLibraryLookupCannotOvertakeLaterStop() = runBlocking {
        val owner=UsbSessionOwner(UsbPlaybackController{Output()},Focus())
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            val adding=async(Dispatchers.Default){owner.libraryQueue(true){entered.countDown();check(release.await(5,TimeUnit.SECONDS));entry("slow")}}
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val stopped=async(Dispatchers.Default){owner.close()}
            yield();release.countDown()
            assertTrue(adding.await());assertTrue(stopped.await());assertTrue(owner.queue.value.items.isEmpty());assertNull(owner.selectedTrack)
        }finally{release.countDown();owner.close()}
    }
    @Test fun appendingAfterFinishedPlayCannotRestartItWhenItsEndEventWasDelayed() = runBlocking {
        val output=Output();val owner=UsbSessionOwner(UsbPlaybackController{output},Focus())
        try {
            owner.enqueue(entry("first"));owner.open(7);assertTrue(owner.play());val first=ended(owner)
            assertTrue(owner.enqueue(entry("next")))
            assertFalse(owner.onPlaybackState(first));assertEquals(0,owner.queue.value.index);assertEquals(1,output.starts)
        }finally{owner.close()}
    }
    @Test fun cancelledPreparationRetainsAcquiredSourceForStopCleanup() = runBlocking {
        val owner=UsbSessionOwner(UsbPlaybackController{Output()},Focus())
        val next=Track("next");val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            owner.enqueue(entry("first"));owner.enqueue(entry("next"){entered.countDown();check(release.await(5,TimeUnit.SECONDS));next})
            val selecting=launch(Dispatchers.Default){owner.selectQueue(owner.queue.value.items[1].id)}
            assertTrue(entered.await(5,TimeUnit.SECONDS));selecting.cancel();release.countDown();selecting.join()
            assertTrue(owner.sourceSelectionFailed);assertEquals(0,next.closes)
            assertTrue(owner.close());assertEquals(1,next.closes);assertTrue(owner.queue.value.items.isEmpty())
        }finally{release.countDown();owner.close()}
    }
}
