package com.evanna.player.library

internal fun folderPathsOverlap(first: String, second: String): Boolean =
    folderPathContains(first, second) || folderPathContains(second, first)

internal fun folderPathContains(parent: String, child: String): Boolean =
    child.trimEnd('/').let { it == parent.trimEnd('/') || it.startsWith(parent.trimEnd('/') + "/") }
