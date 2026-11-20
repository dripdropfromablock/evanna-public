package com.evanna.player.sources

private val opaqueId = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** Durable identity only. Locations and authorization live in the private source store. */
internal data class SourceReference(val connectionId: String, val itemId: String) {
    init { require(opaqueId.matches(connectionId) && opaqueId.matches(itemId)) }
    val encoded get() = "$connectionId:$itemId"
    val personalId get() = "remote:$encoded"
    companion object {
        fun parse(value: String?): SourceReference? {
            if (value == null || value.length != 73) return null
            return runCatching { SourceReference(value.substring(0, 36), value.substring(37).also { require(value[36] == ':') }) }.getOrNull()
        }
        fun fromPersonalId(value: String) = value.takeIf { it.startsWith("remote:") }?.removePrefix("remote:")?.let(::parse)
    }
}
