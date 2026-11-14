# Evanna UI plan: Liquid Glass, Apple rules, BitChord lessons

Status: phases 0–4 built (phases 3 and 4 on 2026-11-14). Written 2026-11-13.
Each phase gets a mockup first, then code, then emulator and phone checks (same flow as the sound path sheet).

## 1. What Apple's Liquid Glass actually is

Summarised from Apple's WWDC25 talk [Meet Liquid Glass](https://developer.apple.com/videos/play/wwdc2025/219/),
the [Apple newsroom announcement](https://apple.com/newsroom/2025/06/apple-introduces-a-delightful-and-elegant-new-software-design)
and the [Apple design forums](https://developer.apple.com/forums/topics/design-topic). Content was rephrased for compliance with licensing restrictions.

| Rule | What it means for Evanna |
|---|---|
| Two layers | Lists, covers and lyrics are the *content layer* and stay solid. Glass is only for the *control layer* that floats on top: tab bar, mini player, floating buttons, menus, sheet chrome. |
| No glass on glass | Things placed on a glass surface use plain fills and transparency, not a second glass layer. |
| Lensing, not just blur | The edge of the glass bends what is behind it. That bend, plus a thin bright rim, is what makes it read as glass. A plain blur is the old iOS 7 look. |
| Two kinds | *Regular* works anywhere and adapts. *Clear* is see-through and only for controls over rich media (a cover) with a dimming layer under them. Never mix them in one place. |
| Adapts to what is behind | Shadows deepen over text and lighten over plain areas. Small glass (tab bar) flips light/dark with the content under it; big glass (menus, sidebars) does not flip. |
| Alive on touch | A pressed control glows from the finger outward and flexes a little. At rest it stays quiet. Some controls only "lift into glass" while touched. |
| Morphs | Controls reshape into each other between screens instead of fading. A menu pops open out of the button that was tapped. |
| Scroll edge effect | Content scrolling under a floating bar dissolves (light pages) or dims (dark pages) at the edge, instead of a hard bar background. |
| Rounded, concentric, floating | Capsules and continuous ("squircle") corners. Inner corners follow outer corners. The tab bar floats inset from the screen edge, with Search as its own circle. |
| Tint sparingly | Colour only the one primary action. Put colour in the content layer, not on every control. |
| Accessibility built in | Reduce Transparency makes it frostier. Increase Contrast makes it black/white with a border. Reduce Motion removes the elastic parts. |

## 2. What BitChord does well (and how)

BitChord ([kushagrasinghx/BitChord](https://github.com/kushagrasinghx/BitChord)) is GPL-3.0, like Evanna, so its code may be reused with its notices kept.
Its glass renderer is a vendored copy of [Kyant0/backdrop](https://github.com/Kyant0/backdrop) (Apache-2.0, also fine inside a GPL-3.0 app).

| Piece (BitChord file) | Technique worth taking |
|---|---|
| `LiquidGlass.kt` + `backdrop/` | Real lens glass via RenderEffect + AGSL shaders. Only on Android 12+ (`isGlassSupported`). Records the backdrop at **1/3 resolution** to keep GPU cost down. One Settings switch turns it off; off means a solid filled control. |
| `GlassNavBar.kt` | Tab bar and mini player are **one component**. Scrolling down folds it into a single row (selected tab + slim player + Search); scrolling up expands it again. A selection pill slides between tabs. |
| `FloatingBottomBar.kt` | Measured against Apple's frames: 19 dp side margin, selection 4 dp inside the pill, 28 dp glyphs, 10 pt labels. |
| `TopFadeBlur.kt` | The scroll edge effect at the top: progressive blur, strongest at the top edge, eased out, sampled at 1/3 resolution (cut ~5 ms/frame of GPU). |
| `BottomFadeScrim.kt` | At the bottom a **gradient, not a blur**: page colour fading in, cut into many colour stops so it doesn't band. Costs almost nothing while scrolling. |
| `ContinuousCornerShape.kt`, `SquirclePillShape.kt` | Apple's continuous corners (about 60% smoothing) and squircle capsules instead of plain rounded rectangles. |
| `IosMenu.kt`, `HeldContextMenu.kt` | Menus as a frosted slab. A long press **lifts the item itself** out of the list, blurs the page, and hangs the actions off it (Apple Music style). |
| `IosSwitch.kt`, `PillSegmentedControl.kt`, `ThinSlider.kt` | iOS switch whose thumb stretches while pressed; segmented control with a sliding pill; hairline scrubber with no knob that thickens under the finger. |
| `MeshGradient.kt`, `ArtworkMeshBackdrop.kt` | Now Playing backdrop from the cover's colours. Blobs **settle** instead of moving forever (the endless motion was their biggest battery and frame cost). A 128 px pre-blurred bitmap stretched by the GPU instead of a live full-screen blur. |
| `PlayerFrost.kt` | Panels over the player blur a **still** taken when the panel opens, not the live player, so nothing re-blurs on every progress tick. |
| `Skeletons.kt` | Grey placeholder rows with a shimmer, same size as the real rows, so nothing jumps when data arrives. |
| Haze library | Blur for bars and menus; below Android 12 it falls back to a translucent scrim. |

## 3. Evanna today, and the hard limit

| Area | Now |
|---|---|
| Looks | Evanna dark, Apple dark, Apple light (`EvannaTheme.kt`). |
| Tab bar | Already floats, with Search as its own circle (`PlayerScreens.kt`). Solid fill, no glass. |
| Now Playing backdrop | Three blurred cover copies turning forever, blurred on the CPU (`PlayerBackdrop.kt`). Measured ~130% CPU and heavy dropped frames on the M30s. |
| Sheets | Output sheet and sound path sheet, solid surfaces. |
| Press feedback | Every control now sinks on press (`PressIndication.kt`). |
| Controls | Own switch, slider, segmented row; plain rounded corners (43 `RoundedCornerShape` uses). |

**Device limit:** the Samsung M30s runs **Android 11 (API 30)**. It has no RenderEffect (blur needs API 31) and no AGSL (lens needs API 33).
Evanna's minimum is API 28. So glass needs three tiers:

| Tier | Android | Glass looks like |
|---|---|---|
| Full | 13+ (API 33+), e.g. the Xiaomi on 16 | Lens edge, blur, rim light, adaptive shadow |
| Blur | 12 (API 31–32) | Blur + tint + rim, no lens bend |
| Frosted | 9–11 (API 28–30), e.g. the M30s | Translucent tinted fill + rim + shadow; menus blur a CPU still (Evanna already has a CPU blur) |

Plus a Settings switch "Liquid glass" and a "Reduce transparency" switch that force the Frosted tier.

## 4. The plan, in order

### Phase 0 — Make it fast first (do before any glass)
Glass costs GPU time, and the M30s already drops frames.
1. Now Playing backdrop: stop endless turning; drift on open / song change, then settle (BitChord `MeshGradient` lesson).
2. Draw it from a small pre-blurred bitmap stretched by the GPU (BitChord `ArtworkMeshBackdrop` lesson).
3. Measure frames on the M30s with the 1,469-song library before and after.

### Phase 1 — Foundations
1. `ContinuousShape` / `SquirclePill` shape tokens; swap the shape tokens in `EvannaTheme.kt` (Apple looks first).
2. Concentric radius rule: inner radius = outer radius − padding.
3. One `GlassSurface` composable with the three tiers above, Regular and Clear kinds, rim + adaptive shadow, 1/3-resolution sampling.
4. Scroll edge effect: top progressive blur (or fade on API ≤30), bottom gradient floor under the tab bar.
5. Settings: "Liquid glass" and "Reduce transparency" switches; honour system animator scale for Reduce Motion (already read in `MainActivity`).

### Phase 2 — Floating navigation layer
1. Tab bar + mini player as one glass component (BitChord `GlassNavBar` shape).
2. Scroll down folds it to one row; scroll up expands.
3. Selection pill slides between tabs; lens over the pill on API 33+.
4. Mini player expands into Now Playing with the cover flying (existing sheet motion).

### Phase 3 — Controls (built)
1. iOS-style switch (stretching thumb, haptic on change). Built: held, the knob swells into clear glass; ticks on flip; a press anywhere on a Settings row lifts it.
2. Sliding-pill segmented control replacing `SingleChoiceSegmentedButtonRow`. Built: the capsule can be slid with a finger, ticks per segment, lifts into glass while held; the EQ's Graphic | Parametric uses it too.
3. Hairline scrubber that thickens under the finger (Now Playing and EQ). Built: Now Playing's bar thickens from the first touch and stretches a little; EQ band sliders keep their knobs (precision) but the track thickens and the knob lifts.
4. "Lift into glass" on key controls while pressed (play/pause, quality badge), on top of the current sink. Built for Previous, Play/Pause, Next and the quality badge (`ui/GlassLift.kt`). Drawn, not sampled; off with Liquid glass off.

### Phase 4 — Menus and sheets (built)
1. Popover menus that pop out of the button tapped (song "…", sort, add folder). Built: every menu grows from its button with a small overshoot.
2. Long-press lift menu for songs and albums (BitChord `HeldContextMenu`). Built for songs (`LiftMenu`): the row lifts on its page colour, the page blurs (a still) and dims. Albums have no menu yet, so none lifts.
3. Output sheet and sound path sheet: glass chrome, frosted still behind (BitChord `PlayerFrost`). Built (`ui/Frost.kt`): a 1/8-size PixelCopy of the window, box-blurred once, under a thinner tint and a lit rim. Works on Android 11 too; off with glass off or Reduce transparency on.

### Phase 5 — Now Playing
1. Clear glass for the round buttons over the cover, with a dimming layer.
2. Regular glass for the queue/lyrics bar.
3. Tint only Play; everything else neutral.

### Phase 6 — Polish
1. Skeleton rows instead of spinners in Library and Search.
2. Haptics on switch flips, tab changes and long-press lift.
3. Increase Contrast mode: solid black/white glass with a border.

## 5. Decisions needed

| Question | Options | Pick suggested |
|---|---|---|
| Which looks get glass | Apple looks only / all three | Apple looks only; Evanna dark keeps its hairline cards |
| Glass renderer | Vendor Kyant0 backdrop / Gradle dependency / write our own AGSL | Vendor it (as BitChord did: AARs broke against newer Compose) |
| Blur library for bars/menus | Haze / own RenderEffect code | Haze (has the API 30 fallback built in) |
| Start point | Phase 0 / Phase 2 (most visible) | Phase 0, then Phase 1–2 |

## 6. Risks

- Glass on the M30s is the Frosted tier only; it will look flatter than on the Xiaomi.
- GPU cost: every glass surface re-samples what is behind it each frame. Keep to ≤6 surfaces on screen, 1/3 resolution.
- Reusing BitChord or Kyant0 code means keeping their copyright headers and listing them in the app's notices.
- Text over Clear glass must still pass the 4.5:1 contrast floor; check each look.
