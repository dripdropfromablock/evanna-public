package com.evanna.player.webmusic

import com.evanna.player.network.CancelScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MusicSearchResultsTest {
    @Test
    fun exactLocalRecordingComesFirstAndEditionsStaySeparate() {
        val studio = SearchTrack("server-a", "1", "A Song", "An Artist", "The Album", "", durationMs = 120_000)
        val local = studio.copy(provider = "library", id = "local", libraryId = "local", durationMs = 120_450)
        val live = studio.copy(id = "2", title = "A Song (Live)")
        val deluxe = studio.copy(id = "3", album = "The Album (Deluxe)")
        val extended = studio.copy(id = "4", durationMs = 153_000)
        val preview = studio.copy(id = "5", preview = true)
        val groups = groupSearchRecordings(listOf(studio, live, local, deluxe, extended, preview, studio))
        assertEquals(listOf(listOf(local, studio), listOf(live), listOf(deluxe), listOf(extended), listOf(preview)), groups)
    }

    @Test
    fun albumOffersCountEachFormatAndFallbackKeepsTheSelectedFormat() {
        fun file(id: Int, format: String) = SearchDownloadFile(format,
            com.evanna.player.sources.SourceReference("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-22222222222$id"))
        val first = SearchTrack("server-a", "1", "First", "Artist", "Album", "", downloads =
            listOf(file(1, "flac"), file(1, "mp3")))
        val firstOnServer = first.copy(provider = "server", id = "a", downloads = listOf(file(2, "flac")))
        val second = first.copy(id = "2", title = "Second", downloads = listOf(file(3, "mp3")))
        val recordings = listOf(listOf(first, firstOnServer), listOf(second))
        val choice = SearchDownloadChoice("server-a", "flac")
        assertEquals(1, searchDownloadOffers(recordings).single { it.choice == choice }.tracks)
        assertEquals(2, searchDownloadOffers(recordings).single { it.choice == SearchDownloadChoice("server-a", "mp3") }.tracks)
        val direct = planSearchDownloads(recordings, choice, fallback = false, label = { it }).single()
        val fallback = planSearchDownloads(recordings, choice, fallback = true, label = { it }).single()
        assertEquals(listOf("server-a"), direct.candidates.map { it.provider })
        assertEquals(listOf("server-a", "server"), fallback.candidates.map { it.provider })
        assertTrue(fallback.candidates.all { it.file.format == "flac" })
        assertEquals("server-a:1", fallback.key)
    }

    @Test
    fun deliversResponsiveProviderWhileAnotherTimesOut() = runBlocking {
        val stopped = CountDownLatch(1)
        val providers = listOf(provider("slow"), provider("fast"))
        val answers = withTimeout(5_000) {
            searchEachProvider(providers, debounceMs = 0, timeoutMs = 600) { source, scope ->
                if (source.id == "slow") {
                    scope.watch(stopped::countDown)
                    check(stopped.await(3, TimeUnit.SECONDS))
                    scope.checkActive()
                }
                SourceMatches()
            }.toList()
        }
        assertEquals("fast", answers.first().provider)
        assertEquals(SourceMatches(), answers.first().matches)
        assertEquals("slow timed out.", answers.last().problem)
        assertEquals(0, stopped.count)
    }

    @Test
    fun cancellingStaleSearchClosesItsActiveRequest() = runBlocking {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val stopped = CountDownLatch(1)
        val old = launch {
            searchEachProvider(listOf(provider("old")), 0) { _, scope ->
                scope.watch(stopped::countDown)
                started.complete(Unit)
                check(stopped.await(4, TimeUnit.SECONDS))
                scope.checkActive()
                SourceMatches()
            }.collect { error("Stale results returned") }
        }
        withTimeout(5_000) {
            started.await()
            old.cancel()
            old.join()
        }
        assertTrue(old.isCancelled)
        assertEquals(0, stopped.count)
    }

    private fun provider(key: String) = object : MusicSearchProvider {
        override val id = key
        override val label = key
        override fun search(query: String, cancel: CancelScope) = SourceMatches()
        override fun artist(artist: SearchArtist, cancel: CancelScope) = SourceMatches()
        override fun tracks(release: SearchRelease, cancel: CancelScope) = emptyList<SearchTrack>()
    }
}
