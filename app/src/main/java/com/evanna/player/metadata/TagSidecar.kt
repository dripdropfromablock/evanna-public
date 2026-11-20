package com.evanna.player.metadata

import com.evanna.player.library.LibraryAudio
import org.json.JSONObject

internal const val TAG_SIDECAR_SUFFIX = ".evanna-tags.json"
internal const val TAG_SIDECAR_LIMIT = 32 * 1024

internal fun tagSidecarName(audioName: String) = audioName + TAG_SIDECAR_SUFFIX

internal data class TagOrigin(val provider: OnlineTagProvider, val id: String)

internal data class TagSidecar(
    val values: Map<MusicTagField, String?> = emptyMap(),
    val origins: Map<MusicTagField, TagOrigin> = emptyMap(),
) {
    fun encode(): ByteArray {
        val fields = JSONObject()
        val sources = JSONObject()
        for ((field, value) in values) {
            fields.put(field.sidecarKey, value ?: JSONObject.NULL)
            origins[field]?.let {
                sources.put(field.sidecarKey, JSONObject().put("provider", it.provider.name).put("id", it.id))
            }
        }
        return JSONObject().put("version", 1).put("tags", fields).put("sources", sources)
            .toString(2).encodeToByteArray()
    }

    fun edited(changes: List<TagValueChange>, origin: TagOrigin?): TagSidecar {
        require(origin == null || (origin.provider.canSave && origin.provider.validId(origin.id)))
        val fields = values.toMutableMap()
        val sources = origins.toMutableMap()
        for (change in changes) {
            require(change.field != MusicTagField.ARTWORK && (change.after == null || change.field.accepts(change.after)))
            fields[change.field] = change.after
            if (origin == null || change.after == null) sources.remove(change.field) else sources[change.field] = origin
        }
        return TagSidecar(fields, sources)
    }

    fun applyTo(embedded: Map<MusicTagField, String>): Map<MusicTagField, String> =
        (embedded + values).mapNotNull { (field, value) -> value?.let { field to it } }.toMap()

    fun applyTo(audio: LibraryAudio): LibraryAudio {
        val tags = audio.musicTags
        fun value(field: MusicTagField, existing: String?) = if (field in values) values[field] else existing
        fun number(field: MusicTagField, existing: Int?) = if (field in values) values[field]?.toInt() else existing
        return audio.copy(
            title = value(MusicTagField.TITLE, audio.title),
            artist = value(MusicTagField.ARTIST, audio.artist),
            album = value(MusicTagField.ALBUM, audio.album),
            musicTags = tags.copy(
                albumArtist = value(MusicTagField.ALBUM_ARTIST, tags.albumArtist),
                trackNumber = number(MusicTagField.TRACK_NUMBER, tags.trackNumber),
                totalTracks = number(MusicTagField.TRACK_TOTAL, tags.totalTracks),
                discNumber = number(MusicTagField.DISC_NUMBER, tags.discNumber),
                totalDiscs = number(MusicTagField.DISC_TOTAL, tags.totalDiscs),
                year = if (MusicTagField.YEAR in values) values[MusicTagField.YEAR]?.take(4)?.toInt() else tags.year,
                genre = value(MusicTagField.GENRE, tags.genre),
                composer = value(MusicTagField.COMPOSER, tags.composer),
            ),
            sortTags = audio.sortTags.copy(
                title = audio.sortTags.title.takeUnless { MusicTagField.TITLE in values },
                artist = audio.sortTags.artist.takeUnless { MusicTagField.ARTIST in values },
                album = audio.sortTags.album.takeUnless { MusicTagField.ALBUM in values },
                albumArtist = audio.sortTags.albumArtist.takeUnless { MusicTagField.ALBUM_ARTIST in values },
            ),
        )
    }

    companion object {
        fun decode(bytes: ByteArray): TagSidecar {
            require(bytes.size in 1..TAG_SIDECAR_LIMIT)
            val json = JSONObject(bytes.decodeToString(throwOnInvalidSequence = true))
            require(json.opt("version") == 1)
            val fields = json.getJSONObject("tags")
            val sources = json.optJSONObject("sources")
            val values = mutableMapOf<MusicTagField, String?>()
            val origins = mutableMapOf<MusicTagField, TagOrigin>()
            for (field in MusicTagField.entries.filter { it != MusicTagField.ARTWORK }) {
                val key = field.sidecarKey
                if (!fields.has(key)) continue
                val value = if (fields.isNull(key)) null else fields.get(key).let {
                    require(it is String && field.accepts(it))
                    it
                }
                values[field] = value
                val origin = sources?.optJSONObject(key) ?: continue
                val provider = OnlineTagProvider.entries.firstOrNull { it.name == origin.optString("provider") } ?: continue
                val id = origin.optString("id")
                if (value != null && provider.canSave && provider.validId(id)) origins[field] = TagOrigin(provider, id)
            }
            return TagSidecar(values, origins)
        }
    }
}

private val MusicTagField.sidecarKey: String
    get() = if (this == MusicTagField.YEAR) "release_date" else name.lowercase(java.util.Locale.ROOT)
