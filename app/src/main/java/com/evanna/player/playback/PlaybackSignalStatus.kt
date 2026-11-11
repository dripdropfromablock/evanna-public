package com.evanna.player.playback

internal fun UsbPlaybackSnapshot.usbSamplePathEligible(): Boolean =
    route == PlaybackRoute.DIRECT_USB && state in listOf(UsbPlaybackState.PLAYING, UsbPlaybackState.DRAINING) &&
        failure == null && readiness.clockConfigured && readiness.feedbackReady && readiness.volumeVerified &&
        source?.strictEligible == true && stream?.bitPerfect == true && stream.processed==false &&
        stats?.let { !it.disconnected && it.underruns == 0L && it.transferErrors == 0L && it.clippedSamples == 0L } != false

/** [brief] rows are the plain lines of the quality window's Minimal view; the rest are its Advanced rows. */
internal data class SignalStage(val label:String,val value:String,val changed:Boolean=false,val brief:Boolean=false)

/** What changes the samples on Direct USB; the full stage and the short summary both read this one list. */
private fun UsbPlaybackSnapshot.usbConversions(source: PcmFormat, rate: Int, bits: Int): List<String> = buildList {
    if (!source.strictEligible) add("processed decode / S32 quantization")
    if (rate != source.sampleRate) add("resampling ${source.sampleRate} → $rate Hz")
    if (bits < source.significantBits) add("precision reduction ${source.significantBits} → $bits bits")
    if(eqProcessed)add("EQ · preamp $eqPreampDb dB")
    if(crossfeedProcessed)add("crossfeed ${crossfeed.label.lowercase()}")
}

private fun UsbPlaybackSnapshot.usbUnhealthy(): Boolean =
    failure != null || stats?.let { it.disconnected || it.underruns > 0 || it.transferErrors > 0 || it.clippedSamples > 0 } == true

/** Render typed evidence only: never locations, track IDs or guessed hardware precision. */
internal fun signalStages(snapshot: UsbPlaybackSnapshot): List<SignalStage> = buildList {
    fun stage(label:String,value:String,changed:Boolean=false){add(SignalStage(label,value,changed))}
    val source = snapshot.source
    source?.let {
        stage("Source","${it.codec} · ${it.sampleRate} Hz · ${it.sourceBits?.let { bits -> "$bits-bit" } ?: "original precision unknown"} · ${it.channels} channels")
        stage("Decoder input",if (it.strictEligible) "S32 container · ${it.significantBits} significant bits"
            else "processed S32 · processed decode / S32 quantization",!it.strictEligible)
    }
    val live = snapshot.state in listOf(UsbPlaybackState.PLAYING, UsbPlaybackState.DRAINING)
    val observed = if (live) "Active" else "Last"
    when (snapshot.route) {
        null -> stage("","No output selected. Select a local file and an output.")
        PlaybackRoute.ANDROID -> if (snapshot.androidStream?.bitPerfect == true) {
            val stream = snapshot.androidStream
            stage("$observed Android output","${stream.sampleRate} Hz · ${stream.bits}-bit integer · ${stream.channels} channels")
            stage("Processing","Android reports its bit-perfect USB mixer for this stream; Evanna sends unchanged samples. Not verified on hardware by Evanna.")
            stage("Volume","Android does not scale samples on this route; DAC volume behavior is unverified.")
            stage("Equalizer","Off on this route.")
            stage("Crossfeed","Off on this route.")
        } else {
            snapshot.androidStream?.let { stage("$observed Android output","${it.sampleRate} Hz · PCM float · ${it.channels} channels",true) }
                ?: stage("Output","Android output selected; stream not started.")
            stage("Processing","S32 → float; device rate/precision and mixer processing unknown.",true)
            stage("Volume","Android system volume. No USB sample-path guarantee.")
            stage("Leveling","Off on Android audio.")
            stage("Equalizer","Off on Android audio.")
            stage("Crossfeed","Off on Android audio.")
        }
        PlaybackRoute.DIRECT_USB -> {
            val stream = snapshot.stream
            val plan = snapshot.plan
            if (stream != null) {
                stage("$observed USB output","${stream.outputRate} Hz · ${stream.format.bitResolution}-bit · ${stream.channels} channels",!stream.bitPerfect)
                stage("Device-reported rate","${stream.deviceRate.takeIf { it > 0 }?.let { "$it Hz" } ?: "unknown"}")
                stage("USB endpoint","Interface ${stream.format.interfaceNumber} · alt ${stream.format.altSetting} · endpoint ${stream.format.endpointAddress} · ${stream.format.syncType}")
            } else if (plan != null) stage("Planned USB output","${plan.outputRate} Hz · ${plan.dacBits}-bit",!plan.bitPerfect)
            else stage("Output","USB output selected; format not planned yet.")
            val rate = stream?.outputRate ?: plan?.outputRate
            val bits = stream?.format?.bitResolution ?: plan?.dacBits
            if (source != null && rate != null && bits != null) {
                val processing = snapshot.usbConversions(source, rate, bits)
                stage("Rate","${source.sampleRate} → $rate Hz${if(rate!=source.sampleRate && snapshot.maxUsbRate!=null)" · saved ceiling ${snapshot.maxUsbRate} Hz" else ""}",rate!=source.sampleRate)
                stage("Bit depth","${source.significantBits} significant bits → $bits output bits${if(bits<source.significantBits)" · reduced precision" else ""}",bits<source.significantBits)
                stage("${if (stream == null) "Planned" else observed} processing",processing.ifEmpty {listOf("no sample conversion")}.joinToString("; "),processing.isNotEmpty())
            }
            val waiting=!live || stream==null && plan==null
            val feed=snapshot.crossfeed
            val crossfeed=when {
                feed==com.evanna.player.equalizer.Crossfeed.OFF -> "crossfeed off"
                snapshot.crossfeedProcessed -> "crossfeed ${feed.label.lowercase()} · ${feed.cutHz} Hz · ${feed.feedDb} dB"
                waiting -> "crossfeed ${feed.label.lowercase()} · waiting for the next playback start"
                else -> "crossfeed ${feed.label.lowercase()} · bypassed: stereo songs only"
            }
            stage("Processing controls",when {
                !snapshot.eqEnabled -> "EQ off · $crossfeed"
                waiting -> "EQ enabled · ${snapshot.equalizer?.name ?: "Flat"} · waiting for the next playback start · $crossfeed"
                snapshot.eqProcessed -> "EQ on · ${snapshot.equalizer?.name ?: "Flat"} · preamp ${snapshot.eqPreampDb} dB · $crossfeed"
                else -> "EQ enabled · flat or inactive filters bypass sample processing at this rate · $crossfeed"
            })
            stage("Leveling",if(snapshot.levelingMode==com.evanna.player.loudness.LevelingMode.OFF)"Off" else
                if(snapshot.leveling.mode!=snapshot.levelingMode)"${snapshot.levelingMode.name.lowercase().replaceFirstChar {it.uppercase()}} selected · waiting for the next playback start."
                else "${snapshot.levelingMode.name.lowercase().replaceFirstChar {it.uppercase()}} · ${snapshot.leveling.offsetDb} dB · DAC hardware volume · ${snapshot.leveling.evidence}. Samples unchanged.")
            stage("Volume","USB hardware volume · ${if (live && snapshot.readiness.volumeVerified) "control readback verified for this stream" else "control unverified for playback"}${snapshot.usbVolume?.let{" · base ${it.currentDb} dB"}.orEmpty()}${snapshot.appliedGainDb?.let {" · applied $it dB"}.orEmpty()}.")
            val readiness=snapshot.readiness
            stage("Health","Clock ${if(readiness.clockConfigured)"configured" else "unverified"} · feedback ${if(readiness.feedbackReady)"ready" else "unverified"}"+
                (snapshot.stats?.let {" · underruns ${it.underruns} · transfer errors ${it.transferErrors} · clipped float samples ${it.clippedSamples}${if(it.disconnected)" · disconnected" else ""}"} ?: " · counters unavailable"),
                snapshot.usbUnhealthy())
            stage("Sample path",if (snapshot.usbSamplePathEligible()) "eligible at USB input; not a physical bit-perfect measurement or a claim about DAC DSP."
                else "no active strict eligibility confirmed.",!snapshot.usbSamplePathEligible())
            if (snapshot.conversionAllowed) stage("","Conversion authorized for this play only; saved USB mode is unchanged.",true)
            if (!snapshot.followSongRate) stage("Follow song rate","off · songs above ${(snapshot.maxUsbRate ?: FOLLOW_SONG_RATE_CAP) / 1000} kHz play at a lower supported rate.")
        }
    }
}

/** A few plain lines for the Minimal view, from the same snapshot as [signalStages]. Same honesty: no physical bit-perfect claim. */
internal fun signalSummary(snapshot: UsbPlaybackSnapshot): List<SignalStage> = buildList {
    fun line(label:String,value:String,changed:Boolean=false){add(SignalStage(label,value,changed,brief=true))}
    val live = snapshot.state in listOf(UsbPlaybackState.PLAYING, UsbPlaybackState.DRAINING)
    val output = if (live) "Output" else "Last output"
    when (snapshot.route) {
        null -> line("","No output selected. Select a local file and an output.")
        PlaybackRoute.ANDROID -> {
            val stream = snapshot.androidStream
            val bitPerfect = stream?.bitPerfect == true
            line(if (stream != null) output else "Output",
                if (stream == null) "Android audio · not started" else "Android audio · ${stream.sampleRate} Hz${stream.bits?.let { " · $it-bit" }.orEmpty()}",
                stream != null && !bitPerfect)
            line("Processing",if (bitPerfect) "None, per Android. Not verified by Evanna" else "Android may change the samples",!bitPerfect)
            line("Volume",if (bitPerfect) "Not changed by Android. DAC volume not verified" else "Android system volume")
        }
        PlaybackRoute.DIRECT_USB -> {
            val stream = snapshot.stream
            val plan = snapshot.plan
            when {
                stream != null -> line(output,"Direct USB · ${stream.outputRate} Hz · ${stream.format.bitResolution}-bit",!stream.bitPerfect)
                plan != null -> line("Planned output","Direct USB · ${plan.outputRate} Hz · ${plan.dacBits}-bit",!plan.bitPerfect)
                else -> line("Output","Direct USB · not started")
            }
            val source = snapshot.source
            val rate = stream?.outputRate ?: plan?.outputRate
            val bits = stream?.format?.bitResolution ?: plan?.dacBits
            val conversions = if (source != null && rate != null && bits != null) snapshot.usbConversions(source, rate, bits) else emptyList()
            line("Processing",when {
                conversions.isNotEmpty() -> conversions.joinToString("; ").replaceFirstChar { it.uppercase() }
                snapshot.usbSamplePathEligible() -> "None. Samples unchanged at the USB input"
                else -> "Not confirmed yet"
            },conversions.isNotEmpty())
            val level = snapshot.appliedGainDb ?: snapshot.usbVolume?.currentDb
            line("Volume",if (live && snapshot.readiness.volumeVerified && level != null) "DAC hardware volume $level dB" else "DAC hardware volume not confirmed yet")
            if (stream != null) {
                val problem = snapshot.usbUnhealthy()
                val confirmed = snapshot.stats != null && snapshot.readiness.clockConfigured && snapshot.readiness.feedbackReady
                line("Health",when { problem -> "Problem found. See Advanced"; confirmed -> "No problems found"; else -> "Not confirmed yet" },problem)
            }
        }
    }
}

internal fun playbackSignalStatus(snapshot:UsbPlaybackSnapshot):String=signalStages(snapshot)
    .joinToString("\n"){if(it.label.isEmpty())it.value else "${it.label}: ${it.value}"}
