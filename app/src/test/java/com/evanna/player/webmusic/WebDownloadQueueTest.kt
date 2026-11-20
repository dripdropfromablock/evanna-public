package com.evanna.player.webmusic

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.evanna.player.sources.SourceReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebDownloadQueueTest {
    @get:Rule val temp = TemporaryFolder()

    private fun ref(id: Int) = SourceReference("11111111-1111-1111-1111-111111111111",
        "22222222-2222-2222-2222-${id.toString().padStart(12, '0')}")

    private fun candidate(provider: String, id: Int, format: String = "mp3") =
        SearchDownloadCandidate(provider, SearchDownloadFile(format, ref(id)))

    @Test fun albumRetryAfterRestartDownloadsOnlyTheMissingTrack() = runBlocking {
        val directory = temp.newFolder()
        val downloaded = mutableListOf<String>()
        val saved = mutableListOf<String>()
        fun queue(failSecond: Boolean) = WebDownloadQueue(directory, publishTrack = { folder, audio, _ ->
            val uri = "$folder/${audio.name}"
            assertFalse(uri in saved)
            saved.add(uri)
            uri
        }) { _, name, _, file, _, _ ->
            downloaded.add(name)
            file.writeText(if (failSecond && name == "two.mp3") "partial" else "verified")
            if (failSecond && name == "two.mp3") throw IOException("Connection interrupted")
            StagedWebAudio(file, name, "mp3", RecordingCheck.CONSISTENT)
        }
        val album = listOf("one", "two").mapIndexed { index, title ->
            SearchDownload(title, "$title.mp3", listOf(candidate("Jellyfin", index)), album = "Release")
        }
        val original = queue(true)
        assertEquals(2, original.enqueueSearch("music", album))
        withTimeout(5_000) { original.entries.first { rows -> rows.map { it.status } == listOf(WebDownloadStatus.SAVED, WebDownloadStatus.FAILED) } }
        assertEquals(listOf("music/one.mp3"), saved)
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".stage") })
        val resumed = queue(false)
        assertEquals(1, resumed.enqueueSearch("music", album))
        withTimeout(5_000) { resumed.entries.first { rows -> rows.size == 2 && rows.all { it.status == WebDownloadStatus.SAVED } } }
        assertEquals(listOf("one.mp3", "two.mp3", "two.mp3"), downloaded)
        assertEquals(listOf("music/one.mp3", "music/two.mp3"), saved)
        assertEquals(0, resumed.enqueueSearch("music", album))
    }

    @Test fun retryAfterRestartUsesThePersistedSourceAndRecordsTheSuccessfulFallback() = runBlocking {
        val directory = temp.newFolder()
        val primary = candidate("Jellyfin A", 1, "flac")
        val fallback = candidate("Jellyfin B", 2, "flac")
        val plan = SearchDownload("release:track", "Track.flac", listOf(primary, fallback))
        val attempted = mutableListOf<SourceReference>()
        fun queue(available: Boolean) = WebDownloadQueue(directory, publishTrack = { _, _, _ -> "saved" }) { source, name, _, file, _, _ ->
            attempted += source
            if (source == primary.file.ref || !available) throw IOException("Server unavailable")
            file.writeText("verified")
            StagedWebAudio(file, name, "flac", RecordingCheck.CONSISTENT)
        }
        val original = queue(false)
        assertEquals(1, original.enqueueSearch("music", listOf(plan)))
        val failure = withTimeout(5_000) { original.entries.first { it.single().status == WebDownloadStatus.FAILED }.single() }
        val reopened = queue(true)
        assertTrue(reopened.retry(failure.id))
        val saved = withTimeout(5_000) { reopened.entries.first { it.single().status == WebDownloadStatus.SAVED }.single() }
        assertEquals(listOf(ref(1), ref(2), ref(1), ref(2)), attempted)
        assertEquals("Jellyfin B", saved.provider)
        assertEquals("flac", saved.audioFormat)
        assertEquals("Jellyfin B", queue(true).entries.value.single().provider)
    }

    @Test fun wrongFormatAndMismatchedFallbackAreNeverPublished() = runBlocking {
        val directory = temp.newFolder()
        val queue = WebDownloadQueue(directory, publishTrack = { _, _, _ -> error("Unexpected publish") }) { source, _, _, file, _, _ ->
            file.writeText("staged")
            if (source == ref(1)) StagedWebAudio(file, "Track.mp3", "mp3", RecordingCheck.CONSISTENT)
            else StagedWebAudio(file, "Track.flac", "flac", RecordingCheck.TAG_MISMATCH)
        }
        val candidates = listOf(candidate("Primary", 1, "flac"), candidate("Fallback", 2, "flac"))
        queue.enqueueSearch("music", listOf(SearchDownload("track", "Track.flac", candidates)))
        val failed = withTimeout(5_000) { queue.entries.first { it.single().status == WebDownloadStatus.FAILED }.single() }
        assertEquals(RecordingCheck.TAG_MISMATCH.warning, failed.detail)
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".stage") })
    }

    @Test fun cancelsTheActiveServerDownloadAndStartsTheNextOne() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val queue = WebDownloadQueue(temp.newFolder(), publishTrack = { _, audio, _ -> audio.name }) { source, name, _, file, cancel, _ ->
            if (source == ref(1)) {
                val unregister = cancel.watch(released::countDown)
                started.countDown()
                try {
                    check(released.await(5, TimeUnit.SECONDS))
                    cancel.checkActive()
                } finally {
                    unregister()
                }
            }
            file.writeText("verified")
            StagedWebAudio(file, name, "mp3", RecordingCheck.CONSISTENT)
        }
        queue.enqueueSearch("music", listOf(
            SearchDownload("one", "One.mp3", listOf(candidate("Jellyfin", 1))),
            SearchDownload("two", "Two.mp3", listOf(candidate("Jellyfin", 2))),
        ))
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(queue.cancel(queue.entries.value.first().id))
        val rows = withTimeout(5_000) { queue.entries.first { it.last().status == WebDownloadStatus.SAVED } }
        assertEquals(listOf(WebDownloadStatus.FAILED, WebDownloadStatus.SAVED), rows.map { it.status })
    }

    @Test fun oldWebsiteJobsCannotRetryAndServerJobsSurvive() {
        val directory = temp.newFolder()
        val browser = ref(1).itemId
        val website = ref(2).itemId
        val server = ref(3).itemId
        val saved = ref(4).itemId
        val props = java.util.Properties()
        props.setProperty("order", "$browser,$website,$server,$saved")
        fun put(id: String, key: String, value: String) { props.setProperty("$id.$key", value) }
        put(browser, "url", "https://example.com/browser.mp3")
        put(browser, "status", "READY")
        put(website, "folder", "music")
        put(website, "url", "https://example.com/track.mp3")
        put(website, "status", "FAILED")
        put(server, "folder", "music")
        put(server, "sources", "2")
        put(server, "source.0.name", "Website")
        put(server, "source.0.format", "mp3")
        put(server, "source.0.url", "https://example.com/track.mp3")
        put(server, "source.1.name", "Jellyfin")
        put(server, "source.1.format", "mp3")
        put(server, "source.1.ref", ref(3).encoded)
        put(saved, "folder", "music")
        put(saved, "saved", "content://music/track")
        put(saved, "status", "SAVED")
        directory.resolve("queue.properties").outputStream().use { props.store(it, null) }

        val queue = WebDownloadQueue(directory) { _, _, _, _, _, _ -> error("Unexpected transfer") }
        assertEquals(listOf(server, saved), queue.entries.value.map { it.id })
        assertEquals(listOf("Jellyfin"), queue.entries.value.first().candidates.map { it.provider })
        assertFalse(queue.retry(browser))
        assertFalse(queue.retry(website))
        assertEquals("content://music/track", queue.entries.value.last().savedUri)
        assertFalse(queue.entries.value.last().canRetry)
    }
}
