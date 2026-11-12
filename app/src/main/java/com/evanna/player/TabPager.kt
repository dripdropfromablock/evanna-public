package com.evanna.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    LaunchedEffect(selected) {
        val index = playerTabs.indexOf(selected)
        // A user drag can cancel the animation. Keep the observer alive so it can report where the drag settles.
        launch { pager.animateScrollToPage(index) }.join()
        snapshotFlow { pager.settledPage to pager.isScrollInProgress }.collect { (index, scrolling) ->
            if (scrolling || current != selected) return@collect
            val tab = playerTabs[index]
            if (tab != current) {
                // A keyboard left open on Search must not follow the user to another tab.
                focus.clearFocus()
                select(tab)
            }
        }
    }
    val covered = LocalCovered.current
    HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = playerTabs.size,
        userScrollEnabled = selected != PlayerScreen.CAROUSEL, key = { playerTabs[it].name }) { index ->
        // Pages out of view stay alive but are silent to screen readers and Back.
        CompositionLocalProvider(LocalCovered provides (covered || index != pager.currentPage)) {
            Box(Modifier.fillMaxSize().then(if (index == pager.currentPage) Modifier else Modifier.clearAndSetSemantics {})) { page(playerTabs[index]) }
        }
    }
}
