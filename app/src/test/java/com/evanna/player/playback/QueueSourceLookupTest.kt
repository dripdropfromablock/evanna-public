package com.evanna.player.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QueueSourceLookupTest {
    @Test fun restoredReferenceLookupDoesNotOpenAudioAcquireFocusOrSave() = runBlocking {
        val entry = SavedQueueEntry("evanna:local:queue:test", null, "content://local/file", "Song", null, null, 1000000)
        val source = QueueSource(null, entry.title, null, null, entry.durationUs, entry.fileUri) { error("No source should open") }
        var saves = 0
        val owner = UsbSessionOwner(UsbPlaybackController { error("No output should open") }, object : PlaybackFocus {
            override val held = false
            override fun request(): Boolean = error("No focus should be requested")
            override fun abandon() = Unit
        }, persistence = object : QueuePersistence {
            override fun load() = SavedQueue(listOf(entry), 0, 300000)
            override fun save(state: SavedQueue) { saves++ }
        }, restoreSource = { source })
        owner.initialize()
        val before = owner.queue.value
        assertSame(source, owner.queueSource(entry.id))
        assertNull(owner.queueSource("missing"))
        assertEquals(before, owner.queue.value)
        assertEquals(300000L, owner.positionUs)
        assertNull(owner.selectedTrack)
        assertEquals(0, saves)
    }
}
