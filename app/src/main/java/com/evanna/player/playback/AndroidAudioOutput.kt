package com.evanna.player.playback

import com.truesample.usbaudio.StreamStats
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Platform seam: writes are nonblocking; pause freezes playbackHead before flushing it. */
internal interface AndroidTrackSink {
    val sampleRate: Int
    val channels: Int
    val primingFrames: Int
    val deviceId: Int
    val deviceType: Int
    val routeChanged: Boolean
    val playbackHead: Int
    val underruns: Int
    fun play()
    fun ready(): Boolean
    fun write(data: FloatArray, offset: Int, count: Int): Int
    /** Android 14+ bit-perfect sinks take the unchanged S32 samples [offset, offset+count) and pack them to [encodingBits]. */
    val bitPerfect: Boolean get() = false
    val encodingBits: Int get() = 0
    fun writePcm(data: ByteBuffer, offset: Int, count: Int): Int = error("Integer writes need a bit-perfect sink")
    fun pauseAndFlush()
    fun release()
}

internal class PlaybackHeadCounter {
    private var previous = 0L
    private var wraps = 0L
    fun update(value: Int): Long {
        val raw = value.toLong() and 0xffffffffL
        if (raw < previous) {
            check(previous >= 0xf0000000L && raw <= 0x10000000L) { "Unexpected playback-head reset" }
            wraps += 1L shl 32
        }
        previous = raw
        return wraps + raw
    }
}

/** Android owns mixer/device conversion and system volume; this backend makes no USB/fidelity claim. */
internal class AndroidAudioOutput(
    private val factory: (PcmFormat, Int?) -> AndroidTrackSink,
    private val clockNanos: () -> Long = System::nanoTime,
    private val idle: () -> Unit = { Thread.sleep(5) },
    /** Explicit Android 14+ route: only unchanged integer sources, through a mixer Android reports as bit-perfect. */
    val bitPerfect: Boolean = false,
) : PlaybackOutput {
    override val route = PlaybackRoute.ANDROID
    override val description = if (bitPerfect) "Android bit-perfect mixer (Android 14+) · USB DAC" else "Android audio · system volume · processed output"
    private var sink: AndroidTrackSink? = null
    private var pinnedDevice: Int? = null
    @Volatile private var active = false
    @Volatile private var failed = false
    @Volatile private var routeLost = false
    @Volatile private var sealed = false
    private var drained = false
    private var stopComplete = true
    private var closed = false
    private var head = PlaybackHeadCounter()
    private val progress = Any()
    private var completed = 0L
    private var observedUnderruns = 0L
    private val submitted = AtomicLong()
    private val errors = AtomicLong()
    private val writing = AtomicBoolean()
    private val floats = FloatArray(4096 * 2)

    @Synchronized override fun startOutput(source: PcmFormat): StartedOutput {
        check(!closed && !active && !writing.get() && stopComplete)
        require(source.sampleRate in 8000..384000 && source.channels in 1..2)
        sink?.release(); sink = null
        submitted.set(0); errors.set(0); completed = 0; observedUnderruns = 0
        head = PlaybackHeadCounter(); failed = false; routeLost = false; sealed = false; drained = false
        val current = factory(source, pinnedDevice).also { sink = it; stopComplete = false }
        try {
            check(current.sampleRate == source.sampleRate && current.channels == source.channels && current.bitPerfect == bitPerfect &&
                (!bitPerfect || source.strictEligible && current.encodingBits == source.significantBits))
            current.play()
            check(pinnedDevice == null || pinnedDevice == current.deviceId) { "Select Android output again after a device change" }
            pinnedDevice = current.deviceId
            active = true
            check(ready())
            return StartedOutput.Android(AndroidOutputInfo(current.sampleRate, current.channels, current.deviceType,
                bitPerfect, current.encodingBits.takeIf { bitPerfect }))
        } catch (failure: Exception) { failed = true; errors.incrementAndGet(); throw failure }
    }

    override fun ready(): Boolean {
        val current = sink ?: return false
        if (current.routeChanged) routeLost = true
        return active && !failed && !routeLost && current.ready()
    }

    override fun write(buffer: ByteBuffer, frames: Int): Int = writer {
        val current = sink ?: return@writer -1
        require(frames in 0..4096 && buffer.isDirect && buffer.order() == ByteOrder.LITTLE_ENDIAN &&
            buffer.position() == 0 && buffer.limit() == buffer.capacity() && buffer.capacity().toLong() >= frames.toLong() * current.channels * 4)
        if (sealed || !ready()) return@writer -1
        val samples = frames * current.channels
        if (!bitPerfect) repeat(samples) { floats[it] = (buffer.getInt(it * 4).toDouble() / 2147483648.0).toFloat() }
        val deadline = clockNanos() + 1_500_000_000L
        var offset = 0
        while (offset < samples) {
            if (!ready()) return@writer -1
            if (clockNanos() >= deadline) return@writer failWrite()
            val count = if (bitPerfect) current.writePcm(buffer, offset, samples - offset) else current.write(floats, offset, samples - offset)
            if (count < 0 || count > samples - offset || count % current.channels != 0) return@writer failWrite()
            if (count == 0) idle() else { submitted.addAndGet((count / current.channels).toLong()); offset += count }
        }
        if (ready()) frames else -1
    }

    override fun drain(): Boolean = writer {
        if (sealed) return@writer drained && ready()
        sealed = true
        val target = submitted.get()
        val deadline = clockNanos() + 1_500_000_000L
        while (ready()) {
            if (clockNanos() >= deadline) { failWrite(); return@writer false }
            if (stats().framesCompleted >= target && ready()) { drained = true; return@writer true }
            idle()
        }
        false
    }

    private fun capture(current: AndroidTrackSink) = synchronized(progress) {
        val frames = head.update(current.playbackHead) - current.primingFrames
        completed = frames.coerceIn(0, submitted.get())
        observedUnderruns = current.underruns.toLong().coerceAtLeast(0)
    }

    override fun stats(): StreamStats {
        val current = sink
        if (current != null && active) try { capture(current) } catch (_: Exception) { failWrite() }
        if (current?.routeChanged == true) routeLost = true
        return synchronized(progress) { StreamStats(submitted.get(), observedUnderruns, errors.get(), routeLost,
            submitted.get(), completed, sealed) }
    }

    @Synchronized override fun stop(): Boolean {
        active = false
        if (stopComplete) return true
        return try {
            sink?.let {
                it.pauseAndFlush()
                // Invalid progress cannot undo successful shutdown; retain the last valid position.
                try { capture(it) } catch (_: Exception) { failed = true; errors.incrementAndGet() }
            }
            stopComplete = true
            true
        } catch (_: Exception) { false }
    }

    @Synchronized override fun close() {
        check(!writing.get()) { "Join the writer before closing Android output" }
        if (closed) return
        check(stop()) { "Android cleanup incomplete" }
        sink?.release(); sink = null; closed = true
    }

    private fun failWrite(): Int {
        if (active) { failed = true; errors.incrementAndGet() }
        return -1
    }

    private fun <T> writer(action: () -> T): T {
        check(writing.compareAndSet(false, true)) { "Only one output writer is allowed" }
        try { return action() } finally { writing.set(false) }
    }
}
