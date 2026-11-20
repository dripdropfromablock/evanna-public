package com.evanna.player

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.launch

/**
 * Tabs stay alive side by side. A swipe follows the finger and settles with a spring,
 * so going back to a tab finds its scroll spot and loaded lists as they were.
 */
@Composable
internal fun TabPager(selected: PlayerScreen, onSelect: (PlayerScreen) -> Unit, page: @Composable (PlayerScreen) -> Unit) {
    val pager = rememberPagerState(initialPage = playerTabs.indexOf(selected)) { playerTabs.size }
    val focus = LocalFocusManager.current
    val select by rememberUpdatedState(onSelect)
    val current by rememberUpdatedState(selected)
    val dragging by pager.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(selected) {
        val target = playerTabs.indexOf(selected)
        var movingToTab by mutableStateOf(true)
        launch {
            try {
                pager.animateScrollToPage(target)
            } finally {
                movingToTab = false
            }
        }
        var swiped = false
        snapshotFlow { Triple(dragging, pager.settledPage, movingToTab || pager.isScrollInProgress) }.collect { (dragged, index, moving) ->
            if (dragged) swiped = true
            if (moving || dragged || current != selected) return@collect
            // Focus and keyboard layout can scroll the pager without a tab gesture.
            if (index != target && swiped) {
                focus.clearFocus()
                select(playerTabs[index])
            } else if (index != target) {
                pager.requestScrollToPage(target)
            }
            swiped = false
        }
    }
    val covered = LocalCovered.current
    HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = playerTabs.size,
        userScrollEnabled = selected != PlayerScreen.CAROUSEL, key = { playerTabs[it].name }) { index ->
        // Pages out of view stay alive but are silent to screen readers and Back.
        CompositionLocalProvider(LocalCovered provides (covered || index != pager.currentPage)) {
            Box(Modifier.fillMaxSize().playerFocusScope(blocked = index != pager.currentPage)) { page(playerTabs[index]) }
        }
    }
}
