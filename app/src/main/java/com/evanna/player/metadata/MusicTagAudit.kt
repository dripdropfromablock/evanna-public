package com.evanna.player.metadata

import java.time.LocalDate
import java.time.YearMonth

internal enum class MusicTagField(val label: String, val defaultAudit: Boolean = false) {
    TITLE("Title", true), ARTIST("Artist", true), ALBUM("Album", true), ALBUM_ARTIST("Album artist"),
    TRACK_NUMBER("Track number", true), TRACK_TOTAL("Track total"), DISC_NUMBER("Disc number"), DISC_TOTAL("Disc total"),
    YEAR("Release date"), GENRE("Genre"), COMPOSER("Composer"), ARTWORK("Embedded cover", true);

    fun accepts(value: String): Boolean = when (this) {
        TRACK_NUMBER, TRACK_TOTAL, DISC_NUMBER, DISC_TOTAL -> value.toIntOrNull() in 1..999
        YEAR -> value.take(4).toIntOrNull() in 1000..2999 && when (value.length) {
            4 -> true
            7 -> runCatching { YearMonth.parse(value) }.isSuccess
            10 -> runCatching { LocalDate.parse(value) }.isSuccess
            else -> false
        }
        ARTWORK -> false
        else -> value.isNotBlank() && value.length <= 512 && value.none(Char::isISOControl)
    }
}

internal enum class TagFileStatus(val label: String) {
    READABLE("Readable"), UNAVAILABLE("Unavailable"), UNREADABLE("Unreadable"), CUE("CUE track")
}

internal data class MusicTagSource(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val root: String,
    val folder: String,
    val filename: String,
    val status: TagFileStatus,
    val durationUs: Long? = null,
)

internal data class TagAuditEntry(
    val source: MusicTagSource,
    val values: Map<MusicTagField, String>?,
    val embedded: Map<MusicTagField, String>? = values,
    val sidecarIssue: Boolean = false,
) {
    fun isMissing(field: MusicTagField, embeddedOnly: Boolean = false): Boolean {
        val checked = if (embeddedOnly) embedded else values.takeUnless { sidecarIssue }
        return source.status == TagFileStatus.READABLE && checked != null && checked[field].isNullOrBlank()
    }
}

internal enum class TagScopeKind(val label: String) {
    ALBUM("Album"), ARTIST("Track artist"), ALBUM_ARTIST("Album artist"), FOLDER("Music folder")
}

internal data class TagScope(val kind: TagScopeKind, val value: String?, val qualifier: String? = null) : java.io.Serializable {
    fun includes(source: MusicTagSource): Boolean = this == kind.scopeOf(source)
}

internal fun TagScopeKind.scopeOf(source: MusicTagSource): TagScope = when (this) {
    TagScopeKind.ALBUM -> TagScope(this, source.album, source.albumArtist?.takeUnless(String::isBlank) ?: source.artist)
    TagScopeKind.ARTIST -> TagScope(this, source.artist)
    TagScopeKind.ALBUM_ARTIST -> TagScope(this, source.albumArtist)
    TagScopeKind.FOLDER -> TagScope(this, source.root)
}

internal sealed interface TagChange {
    data class Set(val value: String) : TagChange
    data object Clear : TagChange
}

internal data class TagValueChange(val field: MusicTagField, val before: String?, val after: String?)
internal data class TagFilePreview(val source: MusicTagSource, val changes: List<TagValueChange>)

internal fun previewTagEdits(entries: List<TagAuditEntry>, edits: Map<MusicTagField, TagChange>, fillMissing: Boolean): List<TagFilePreview> {
    require(MusicTagField.ARTWORK !in edits && edits.all { (field, change) -> change !is TagChange.Set || field.accepts(change.value) })
    return entries.mapNotNull { (source, values, _, sidecarIssue) ->
        if (sidecarIssue) return@mapNotNull null
        if (values == null || source.status != TagFileStatus.READABLE) return@mapNotNull null
        val changes = edits.mapNotNull { (field, change) ->
            val before = values[field]?.takeUnless(String::isBlank)
            val after = (change as? TagChange.Set)?.value
            if (before == after || (fillMissing && before != null)) null else TagValueChange(field, before, after)
        }
        if (changes.isEmpty()) return@mapNotNull null
        val after = values.toMutableMap()
        for (change in changes) {
            if (change.after == null) after.remove(change.field) else after[change.field] = change.after
        }
        fun compatible(number: MusicTagField, total: MusicTagField): Boolean {
            if (changes.none { it.field == number || it.field == total }) return true
            val numberValue = after[number]?.toIntOrNull()
            val totalValue = after[total]?.toIntOrNull()
            return numberValue == null || totalValue == null || numberValue <= totalValue
        }
        if (compatible(MusicTagField.TRACK_NUMBER, MusicTagField.TRACK_TOTAL) &&
            compatible(MusicTagField.DISC_NUMBER, MusicTagField.DISC_TOTAL)) TagFilePreview(source, changes) else null
    }
}
