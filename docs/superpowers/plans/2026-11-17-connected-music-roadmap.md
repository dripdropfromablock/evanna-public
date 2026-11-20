# Connected Music Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans
> to implement the approved owned-music increments. On 2026-11-17 the user asked
> to defer online providers and implement the rest. Publishing is not authorized.

**Goal:** Add server music playback and network-speaker casting without weakening
Evanna's native audio path or promising unavailable commercial-provider access.

**Architecture:** Built-in source adapters resolve stable remote identities into
short-lived media requests. Native-capable audio enters the existing decoder/EQ
path; network renderers and vendor-controlled players use explicit separate
session routes. Persistence migrates before remote tracks enter saved queues.

**Tech Stack:** Kotlin/Android, existing Compose/Media3 service, native decoder,
SQLite and current USB module; one validated shared HTTP transport and protocol
adapters. Add SDKs/dependencies only in the increment that needs them.

**Spec:** `../specs/2026-11-17-connected-music-design.md`

## Global constraints

- minSdk 28; current compile/targetSdk 36. Preserve existing local playback/grants.
- Both local server playback and casting are required; they are separate routes.
- No source URL/token/password in saved song IDs, media-session metadata or logs.
- Preserve verified hardware volume, conversion policy, EQ and USB-unplug behavior.
- No provider shown as full/native playback until its access and one-track gates pass.
- No automatic local-speaker fallback after network or remote-renderer failure.
- Implement owned-music features; commercial providers and publishing are deferred.

## Review focus

1. Old queue/playlist/favorite/history data and document grants survive migration.
2. Bad ranges, changed media, interrupted reads and auth expiry cannot corrupt PCM.
3. Discovery/metadata/redirects cannot leak credentials or expose arbitrary files.
4. Renderer loss, external controls and handoff cannot create double playback.
5. Remote playback is not labelled as processed by the phone EQ/JD1 or bit-perfect
   without appropriate evidence; provider SDK playback is not assumed native PCM.

All paths below are relative to `app/src/main/java/com/evanna/player/` unless
specified. New filenames are proposed; follow existing patterns and split only
where the implementation warrants it. An unchecked item is planned, not built.

## Increment 1 — Production network source and safe persistence

**Deliverable:** A user-added finite HTTP(S) audio file plays through the native
engine, survives queue restore by stable reference, and handles network failure.

**Files/ownership:** new `network/HttpRangeSource.kt`, `decoder/NetworkAudioTrack.kt`,
`sources/SourceReference.kt`, `sources/SourceStore.kt`; update
`playback/{SavedQueue,PlaybackQueue,PlaybackQueueStore,PlaybackRuntime,UsbSessionOwner}.kt`,
`user/{PlaylistStore,UserDatabase}.kt`, relevant favorite/history lookup, and
`app/src/main/AndroidManifest.xml`/network security config. Reuse the tested source
reader contract from `PcmSource.kt`; don't loosen `DecodedAudioTrack.local()`.

**Interfaces:** a serializable reference distinguishes local/library/document
from `connectionId + itemId + revision`; a resolver produces a fresh `PcmTrack`
with no secrets in public state. Resolve URLs/credentials when opening, not when
serializing a queue. Add only capabilities consumers use (seek/native playback/
original or transformed media/remote-resource eligibility), not a plugin registry.

- [ ] Map every local-ID assumption in queues, playlists, favorites, history,
  artwork, lyrics and source lookup; write old-version migration fixtures first.
- [ ] Test invalid/colliding provider IDs, unavailable connection, logout and
  process-death restore, including repeated occurrences of one song and equal
  item IDs on different servers. Keep old rows/order/position and local grants.
- [ ] Select a maintained cancellable HTTP client, verify API/license/ABI impact,
  and test TLS, header/overall deadlines and cancellation before adopting it.
- [ ] Build finite-file range reading with validators/64-bit offsets and bounded
  caching. Test >2 GiB sparse resources, partial/truncated responses, no ranges,
  changed ETag, redirects, HTML errors and wrong size. Don't fake seek support.
- [ ] Add explicit LAN-HTTP trust, origin-scoped credentials, encrypted secret
  storage, redacted diagnostics and connection removal. Exercise cross-origin
  redirects, auth refresh failure and malicious metadata URLs.
- [ ] Integrate cancellable source preparation and buffering state without
  blocking the main thread or racing old/new queue selection. Test bounded retry
  on a stable representation; otherwise stop and expose explicit retry.
- [ ] Add the smallest Sources/add-URL flow and per-source state. Test TalkBack,
  text scaling, offline launch, screen-off playback, focus loss and restore.
- [ ] Run JVM/native/Android checks, then actual HTTPS -> M30s/JD1 at verified
  safe gain: EQ off/on, both seek directions, pause/resume, connection loss and
  DAC unplug. Compare settings and queue before/after; record format/conversion.
- [ ] Review, document and checkpoint. No claim that the existing synthetic HTTPS
  result already satisfies this physical internet-playback gate.

## Increment 2 — UPnP/DLNA server browsing and phone playback

**Deliverable:** Discover or manually add a MediaServer, browse its music and play
an original resource on Evanna/JD1. Depends on increment 1.

**Files:** new `sources/upnp/{SsdpDiscovery,ContentDirectoryClient,DidlParser,UpnpSource}.kt`;
new source browser/setup UI in `sources/ui/`; connect it through `SourceStore`
and the existing queue/session command lane. Add manifest permissions only after
checking target-SDK behavior; do not change the target SDK as part of discovery.

- [ ] Write bounded SSDP/description/SOAP/DIDL fixtures: duplicate UUIDs,
  expiry/reboot, service versions, relative URLs, paging, huge XML, entities,
  missing sizes/durations, unsupported codecs and bogus resource locations.
- [ ] Implement user-triggered discovery with lifecycle-bound multicast use,
  interface changes and manual entry. No endless LAN scan or router IGD calls.
- [ ] Implement ContentDirectory browse, pagination and capability-driven Search
  where supported. Resolve resource metadata into a source reference and reader.
- [ ] Add browse/play/queue actions, artwork and clear offline/auth/format states.
  Test large catalogs without forcing a full local import or merging library IDs.
- [ ] Verify originals and format reporting against at least two independent
  server implementations, including one actual NAS/server reachable by the M30s.
  Record server versions, codecs, range support and seek limitations.
- [ ] Run local playback regressions and source-removal/restart tests; review and
  checkpoint. Keep server search capability optional when the server lacks it.

## Increment 3 — Plex personal music

**Deliverable:** Sign in, choose an owned/shared permitted music library and play
its tracks locally. Depends on increment 1; independent of DLNA discovery.

**Files:** new `sources/plex/{PlexAuth,PlexClient,PlexSource}.kt`, source login/server
selection UI and secret-store integration; reuse shared paging/queue/artwork paths.

- [ ] Revalidate current Plex auth/API documentation. Prefer its browser PIN/device
  authentication and token lifecycle; Evanna never collects the Plex password.
- [ ] Fixture-test cancellation, expired/revoked credentials, server/account
  switching, unavailable libraries, paging and multiple media versions.
- [ ] Implement token-scoped server requests, library browse/search/artwork and
  original-track resolution. Verify actual media rather than assuming Direct Play.
- [ ] Test local and permitted remote connections, seek and resume, server loss,
  concurrent playback policy and any music-specific entitlement behavior. Do not
  infer music restrictions from Plex's remote-video products.
- [ ] Validate mixed local/server queues, metadata and physical DAC playback;
  review/checkpoint with a documented supported server/auth matrix.

Reference: [Plex API and authentication](https://developer.plex.tv/pms/).

## Increment 4 — Basic DLNA speaker casting

**Deliverable:** Select a DLNA MediaRenderer and cast a compatible, reachable
server resource with play/pause/stop/seek/volume where advertised. Depends on
increment 2; Plex casting remains gated on safe auth and URL reachability.

**Files:** new `casting/{RendererDiscovery,DlnaRendererClient,RemotePlaybackSession}.kt`;
adapt `playback/{EvannaPlayer,PlaybackService,PlaybackRuntime}.kt`, output selector
and signal-path presentation. Keep remote session logic out of the PCM writer.

- [ ] Define the route handoff/state contract and test it with a controllable
  renderer fixture before altering the MediaSession integration.
- [ ] Retain Evanna ownership of queue/order/shuffle/repeat, sending one item at
  a time initially. Test explicit previous/next and natural-end progression;
  STOPPED alone must not trigger auto-next. Require a tested completion signal
  with consistent item/position/duration evidence, otherwise offer manual Next.
- [ ] Exercise mixed queues: an incompatible next/repeated item stops with a
  reason and explicit skip/output choice. Never discard it, leak auth or change
  routes automatically. This is required before casting without relay support.
- [ ] Implement capability discovery and AVTransport/RenderingControl operations;
  use bounded event subscriptions or polling fallback. Reconcile external changes.
- [ ] Test rejected commands, unsupported seeking, device restarts/loss, stale
  events, response timeout and a different controller changing the track/volume.
- [ ] On external URI takeover, cancel app-driven progression, show external
  playback and preserve the app queue/history. Test explicit takeover back to
  Evanna and process restoration while the speaker continues independently;
  unknown ownership must not restart a track or accidentally advance the queue.
- [ ] Test stop acknowledgement before handoff, stale commands, double playback,
  background controls and reconnection. If state is unknown, show that state and
  require an explicit action; never auto-play through the phone speaker.
- [ ] Identify the source/renderer pair's compatible resource; reject inaccessible
  authenticated resources without handing out broad credentials. Label phone EQ
  and direct USB as inactive during direct casting; don't claim gapless/multiroom.
- [ ] Validate on two renderer implementations, one physical speaker/receiver,
  and the M30s. Include phone DAC detach while casting and screen-off operation.
- [ ] Review/checkpoint. Release candidate gate: increments 1–4 acceptance passes,
  local player regressions pass, and no unsupported source/output promise in UI.

## Increment 5 — Jellyfin and OpenSubsonic/Navidrome

**Deliverable:** Two independently tested source adapters, one at a time.

**Files:** `sources/jellyfin/` then `sources/subsonic/`; same shared source store,
browser/queue/reader contracts. Choose a focused REST adapter versus official SDK
by compatibility/dependency cost; don't introduce a second playback engine.

- [ ] Jellyfin: server/user auth, browse/search/artwork, original audio resolution,
  credential renewal, server version compatibility and playback reporting.
- [ ] OpenSubsonic: negotiate supported extensions/auth, browse/search/artwork,
  request original media, handle API error bodies and optional playback reporting.
- [ ] For each: repeat transport/auth/migration/restore/large-library tests and
  real-server DAC acceptance. Add casting only for safely resolvable resources.
- [ ] Keep local playlists/favorites independent; remote writeback or server-side
  playlist editing is a later explicit feature. Review/checkpoint each adapter.

References: [Jellyfin SDK](https://kotlin-sdk.jellyfin.org/guide/getting-started.html),
[OpenSubsonic](https://opensubsonic.github.io/docs/),
[Navidrome](https://www.navidrome.org/docs/developers/subsonic-api/).

## Increment 6 — Local/private casting relay and other owned sources

Each item below gets its own small design and acceptance pass; they are not
prerequisites for the first connected-music release.

- [ ] Session-scoped relay for granted local phone files and owned authenticated
  server media: item-only read access, correct ranges, bounded I/O, selected LAN
  interface, session expiry/stop and no credential/path leakage. Test that a second
  LAN client cannot enumerate files or continue after the session is revoked.
- [ ] WebDAV read-only folders, then SMB2/3 read-only shares: explicit account/
  folder selection, bounded/lazy metadata, secure auth, grants and original-file
  seek. Verify chosen library on Android/minSdk before adding it; no SMB1 fallback.
- [ ] Direct stream URLs and M3U/PLS radio: bounded playlist parsing, forward-only
  live decoding, cancellation/reconnect and honest no-seek/no-duration state.
  Unknown-size live audio must not reuse a fictitious fixed-size proxy file.
- [ ] Network gapless/read-ahead only after continuity tests; optional bounded
  owned-media downloads/offline cache with quota, incomplete-file handling and
  source-change invalidation. Never cache provider media beyond its permissions.
- [ ] Consider Emby, Chromecast/AirPlay, processed-audio casting, podcasts and
  multiroom only when separately specified; they are not implied by DLNA support.

## Parallel provider access track — no full browsing before one-track proof

Planning gates can run alongside owned-server work; implementation must not block
the server increments while access is missing.

| Provider | First gate | Product decision after proof |
|---|---|---|
| Qobuz | User's access request approved; confirm current agreement, login and one full track | Native engine only if permitted and technically verified; respect provider UI/queue rules |
| TIDAL | Current full-track authorization and playback SDK/rights evaluated | SDK-owned playback may require a separate route; custom PCM/JD1 not assumed |
| Spotify | Decide whether explicitly labelled remote or web playback is useful; obtain developer/account access and test | Public Android remote controls Spotify; web embedding is an unverified separate mode, not native PCM |
| Deezer | Confirm new application access and a supported SDK/partner agreement | One full track, then prove native-audio compatibility or label the SDK route |
| Apple Music/Amazon/other | Explicit user priority plus official API/SDK, account and distribution review | Backlog only; no universal-streaming promise |

For every provider: test authorization cancellation/revocation/expiry, region and
subscription eligibility, required reporting/branding, source/output permissions,
credentials in a public APK, provider separation/mixing restrictions, error states
and full-length playback. Public client IDs may be configured; reusable confidential
app secrets must not be embedded in a public APK. A backend, if required, needs a
separate scope/hosting decision. No shared/reused third-party credentials.

## Completion and release evidence

For every increment record the commit, migrations tested, protocol/server/device
versions, fixture and real-world results, permissions, source codec/rate/bits,
actual output/conversion, failure/cancel timing and remaining limitations. Build
release artifacts and inspect manifests/secret exposure before any publishing
request. Keep implementation reviews scoped and attach evidence to that increment.

No implementation, new dependency, account change, server setup or release was
performed to create this plan. Exact delivery dates depend on server/speaker test
access and provider approvals; this roadmap does not guess approval dates.
