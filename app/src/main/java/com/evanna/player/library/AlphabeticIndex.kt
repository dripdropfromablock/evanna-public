package com.evanna.player.library

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import com.evanna.player.EvannaDesign
import com.evanna.player.ui.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** A slim bar: touch it and drag to move through the letters, with a bubble naming the current one.
 *  TalkBack's double tap opens separate accessible 48 dp letter buttons instead.
 *  It starts at [rowsTop], the first list row (-1 until rows show), so the page lines above keep the full width. */
@Composable
internal fun AlphabeticIndex(rowsTop:()->Int,modifier:Modifier=Modifier,slim:Boolean=false,onLetter:(Char)->Unit) {
    var dialog by remember {mutableStateOf(false)}
    var letter by remember {mutableStateOf('#')}
    val select by rememberUpdatedState(onLetter)
    // A jump reloads the rows and can move them, so the bar keeps its place while a finger is on it.
    var held by remember {mutableStateOf<Int?>(null)}
    var finger by remember {mutableFloatStateOf(-1f)}
    Box(modifier.layout {measurable,constraints ->
            val top=alphabetBarTop(held ?: rowsTop(),constraints.maxHeight,200.sp.roundToPx())
            if(top==null)layout(0,0){} else {
                val bar=measurable.measure(constraints.copy(minHeight=constraints.maxHeight-top,maxHeight=constraints.maxHeight-top))
                layout(bar.width,constraints.maxHeight){bar.place(0,top)}
            }
        }.width(28.dp)
        // The bar sits at the screen edge, so a drag on it should not start Android's back gesture (Android honours up to 200 dp).
        .systemGestureExclusion()
        .semantics {role=Role.Button;contentDescription="Jump to letter; tap to choose or drag vertically";stateDescription=letter.toString()
            onClick(label="choose a letter") {dialog=true;true}}
        .pointerInput(Unit) {
            awaitEachGesture {
                val down=awaitFirstDown()
                var picked: Char? = null
                fun pick(y: Float) {
                    finger = y
                    val target = alphabetLetter(y, size.height.toFloat()) ?: return
                    if (target == picked) return
                    picked = target
                    letter = target
                    select(target)
                }
                try {
                    held=rowsTop();down.consume();pick(down.position.y)
                    // Every move is taken, so a sideways wobble cannot switch tabs mid-drag.
                    drag(down.id) {change -> change.consume();pick(change.position.y)}
                } finally {
                    held=null;finger=-1f
                }
            }
        },contentAlignment=Alignment.Center) {
        // Every letter when there is room (the mockup's bar), seven marks otherwise; the touch mapping is the same either way.
        // A letter needs its line height plus 3 dp (15 dp at the normal font size), so large font sizes fall back sooner.
        if (slim) BoxWithConstraints(Modifier.fillMaxHeight().width(5.dp).padding(vertical = 4.dp)) {
            val tint = EvannaDesign.colorScheme.onSurfaceVariant
            val thumbHeight = minOf(48.dp, maxHeight)
            Box(Modifier.align(Alignment.TopCenter).fillMaxHeight().width(2.dp).background(tint.copy(alpha = 0.16f), CircleShape))
            Box(Modifier.align(Alignment.TopCenter).offset {
                val travel = (maxHeight - thumbHeight).roundToPx()
                IntOffset(0, if (held == null) 0 else (finger - thumbHeight.toPx() / 2).roundToInt().coerceIn(0, travel))
            }.fillMaxWidth().height(thumbHeight).background(tint.copy(alpha = 0.64f), CircleShape))
        } else BoxWithConstraints(Modifier.fillMaxHeight()) {
            val step=with(LocalDensity.current){12.sp.toDp()}+3.dp
            val marks=if(maxHeight>=step*27)listOf('#')+('A'..'Z') else listOf('#','A','F','K','P','U','Z')
            val tint=if(com.evanna.player.LocalLookStyle.current.look.apple)EvannaDesign.colorScheme.primary else EvannaDesign.colorScheme.onSurfaceVariant
            Column(Modifier.fillMaxHeight().padding(vertical=8.dp),verticalArrangement=Arrangement.SpaceEvenly,horizontalAlignment=Alignment.CenterHorizontally) {
                for(mark in marks)Text(mark.toString(),style=EvannaDesign.typography.labelSmall.copy(fontSize=9.5.sp,lineHeight=12.sp),
                    color=tint,modifier=Modifier.clearAndSetSemantics {})
            }
        }
        // The bubble sits left of the bar at the finger's height, so the thumb does not hide it.
        if(held!=null)Box(Modifier.align(Alignment.TopEnd).offset {IntOffset(-44.dp.roundToPx(),(finger-32.dp.toPx()).roundToInt())}
            .wrapContentSize(Alignment.TopEnd,unbounded=true).size(64.dp).background(EvannaDesign.colorScheme.primaryContainer,CircleShape)
            .clearAndSetSemantics {},contentAlignment=Alignment.Center) {
            Text(letter.toString(),style=EvannaDesign.typography.headlineMedium,color=EvannaDesign.colorScheme.onPrimaryContainer)
        }
    }
    if(dialog)AlertDialog(onDismissRequest={dialog=false},title={Text("Jump to letter")},text={
        Column {for(row in libraryAlphabet.toList().chunked(4))Row {
            for(mark in row)TextButton(onClick={letter=mark;dialog=false;select(mark)},modifier=Modifier.size(48.dp)
                .semantics {contentDescription=if(mark=='#')"Start of list" else "Jump to $mark"}) {Text(mark.toString())}
        }}
    },confirmButton={TextButton(onClick={dialog=false},modifier=Modifier.heightIn(min=48.dp)){Text("Cancel")}})
}
