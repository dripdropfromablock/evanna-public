package com.evanna.player

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.evanna.player.playback.EvannaPlayer

/** Presentation only; the session remains the single source of playback state. */
@UnstableApi
internal fun playerPresentation(player: Player): PlayerPresentation {
    val extras = player.playlistMetadata.extras
    val shuffle = player.shuffleModeEnabled
    val itemCount = player.mediaItemCount
    val order = if (shuffle) {
        val timeline = player.currentTimeline
        generateSequence(timeline.getFirstWindowIndex(true)) { timeline.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, true) }
            .takeWhile { it in 0 until itemCount }.take(itemCount).toList()
    } else (0 until itemCount).toList()
    val items = order.map { index ->
        val item = player.getMediaItemAt(index)
        PlayerQueueItem(item.mediaId, item.mediaMetadata.title?.toString() ?: "Local audio file", item.mediaMetadata.artist?.toString())
    }.filter { it.id.startsWith("evanna:local:queue:") }
    val currentId = player.currentMediaItem?.mediaId
    return PlayerPresentation(id = currentId, title = player.mediaMetadata.title?.toString().orEmpty(),
        artist = player.mediaMetadata.artist?.toString(), album = player.mediaMetadata.albumTitle?.toString(), connected = true,
        playing = player.playWhenReady, buffering = player.playbackState == Player.STATE_BUFFERING, ended = player.playbackState == Player.STATE_ENDED,
        positionMs = player.currentPosition.coerceAtLeast(0), durationMs = player.duration.takeIf { it >= 0 },
        canPlayPause = player.isCommandAvailable(Player.COMMAND_PLAY_PAUSE), canSeek = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
        canPrevious = player.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM), canNext = player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM),
        canShuffle = player.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE), canRepeat = player.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE),
        shuffle = shuffle, repeat = player.repeatMode, queue = items, queueIndex = items.indexOfFirst { it.id == currentId },
        route = extras?.getString(EvannaPlayer.OUTPUT_ROUTE) ?: "NONE", sourceQuality = extras?.getString(EvannaPlayer.SOURCE_QUALITY).orEmpty(),
        error = player.playerError?.message, queueWarning = extras?.getString(EvannaPlayer.QUEUE_STATUS).takeIf { extras?.getBoolean(EvannaPlayer.QUEUE_WARNING) == true },
        usbGeneration=extras?.getLong(EvannaPlayer.USB_GENERATION) ?: 0,
        sleepLabel=extras?.getString(EvannaPlayer.SLEEP_LABEL).orEmpty(),
        favorite=extras?.takeIf {it.getBoolean(EvannaPlayer.FAVORITE_AVAILABLE)}?.getBoolean(EvannaPlayer.FAVORITE),
        signalStages=extras?.let {bundle ->
            val labels=bundle.getStringArrayList(EvannaPlayer.SIGNAL_LABELS)
            val values=bundle.getStringArrayList(EvannaPlayer.SIGNAL_VALUES)
            val changed=bundle.getBooleanArray(EvannaPlayer.SIGNAL_CHANGED)
            val brief=bundle.getBooleanArray(EvannaPlayer.SIGNAL_BRIEF)
            if(labels!=null && labels.size<=32 && values?.size==labels.size && changed?.size==labels.size && brief?.size==labels.size &&
                labels.all {it.length<=200} && values.all {it.length<=2048})
                labels.indices.map {com.evanna.player.playback.SignalStage(labels[it],values[it],changed[it],brief[it])} else emptyList()
        } ?: emptyList(),
        soundPath=extras?.let {bundle ->
            val verdict=bundle.getString(EvannaPlayer.SOUND_VERDICT)?.takeIf {it.length<=200}
            val why=bundle.getString(EvannaPlayer.SOUND_WHY)?.takeIf {it.length<=1000}
            val stops=bundle.getStringArrayList(EvannaPlayer.SOUND_STOPS)?.takeIf {it.size<=12}?.map {com.evanna.player.playback.decodeStop(it)}
            if(verdict==null || why==null || stops==null || stops.any {it==null}) null
            else com.evanna.player.playback.SoundPath(verdict,why,bundle.getBoolean(EvannaPlayer.SOUND_PLAYING),stops.filterNotNull(),
                bundle.getBoolean(EvannaPlayer.SOUND_OFFER_OUTPUT))
        },
        usbVolume=if(extras?.containsKey(EvannaPlayer.USB_VOLUME)==true)com.truesample.usbaudio.VolumeRange(
            extras.getFloat(EvannaPlayer.USB_VOLUME_MIN),extras.getFloat(EvannaPlayer.USB_VOLUME_MAX),
            extras.getFloat(EvannaPlayer.USB_VOLUME_STEP),extras.getFloat(EvannaPlayer.USB_VOLUME)) else null)
}
