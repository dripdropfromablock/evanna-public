package com.evanna.player.library

internal fun folderPathsOverlap(first: String, second: String): Boolean {
    val a = first.trimEnd('/')
    val b = second.trimEnd('/')
    return a == b || a.startsWith("$b/") || b.startsWith("$a/")
}
