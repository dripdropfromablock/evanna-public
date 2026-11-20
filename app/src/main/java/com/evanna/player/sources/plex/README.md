# Plex personal music adapter

`PlexClient` implements the shared `SourceClient`. All HTTP uses `HttpAccess`,
`NetworkPolicy` and the caller's `CancelScope`; redirects are refused. Credentials
are headers scoped to exactly one origin. Metadata is bounded, server file paths
and media URLs are never copied into catalog entries, and playback re-fetches
metadata to resolve the selected original Part on every open.

## Setup integration

1. Generate/persist one random `clientId` per Evanna install.
2. `PlexAuth.createPin(clientId, cancel)` returns a transient `PlexPin`.
   Open `PlexAuth.browserUrl(clientId, pin)` in the user's browser. Never log,
   persist in queues or share that URL. Evanna never collects a Plex password.
3. Schedule `pollPin(pin, clientId, cancel)` on a worker at least one second
   apart. It returns null while unclaimed and a token when authorized. Polls
   reject early calls, expire at the server lifetime (capped at ten minutes),
   and honor cancellation. Cancel/drop the PIN when the setup screen closes.
4. `account(clientId, token, cancel)` verifies the token and returns account ID.
   `servers(clientId, account, cancel, allowLanHttp)` lists permitted servers and
   connection choices without probing them. HTTPS is preferred; relay endpoints
   are omitted. LAN HTTP requires the user's per-connection choice and shared
   DNS policy still refuses public HTTP and loopback.
5. `PlexClient.selectServer(server, chosenUrl, account, clientId, allowLanHttp,
   cancel)` checks the machine ID and returns config for `SourceStore`. Persist
   only with the encrypted source store, never preferences or UI saved state.
6. Save the connection. `libraries(connection, cancel)` lists music sections.
   Optionally call `selectLibrary(connection, libraryId, cancel)` and save the
   resulting config under the same connection ID. Otherwise root browse lists
   all music sections. Search requires a chosen section or library parent.

Private connection config:

| Field | Meaning |
|---|---|
| `url` | Explicitly selected, validated server origin, ending in `/` |
| `allowLanHttp` | User-approved LAN cleartext choice |
| `clientId` | Stable install client identifier |
| `token` | Resource's server access token, not the Plex account token |
| `accountId` | Verified numeric Plex account ID |
| `serverId` | Resource clientIdentifier, checked against machineIdentifier |
| `libraryId` | Optional verified music section ID |

Account-token and PIN objects are transient and intentionally have no generated
`toString()`. The account token is only needed for setup/server enumeration;
reconnect runs browser authorization again. A different account/server gets a
different source connection; the shared store guards identity changes.

Catalog details contain only type, sectionId/ratingKey, mediaId and partId.
The store assigns opaque queue identities. Multiple Media versions have separate
provider keys; if the selected version disappears playback fails rather than
substituting a different version. Artist/album children and library albums are
paged at 100 server records, with section track-title search. Multipart files,
server transcoding and relay access are unsupported. Actual codec/precision,
ranges and native-decoder support are checked by the shared playback engine;
metadata never establishes bit-perfect or original PCM quality.

## Authentication choice and evidence

Checked the [official Plex API documentation](https://developer.plex.tv/pms/)
on 2026-11-17. This implementation uses its still-documented **Traditional Token
Authentication (Legacy)** browser strong-PIN flow at plex.tv. Resources use the
documented clients.plex.tv origin with a separately scoped request policy. Plex
recommends Ed25519 device JWT for new apps; that requires an explicit compatible
crypto implementation on this app's minSdk 28. No crypto dependency was added.
Legacy tokens do not have a local refresh timer; 401/403 requires reconnect.
No claim is made that legacy login or any server version passed a live test.

## Checks

`PlexSafetyTest` supplies five JVM checks for origin/path/header validation,
auth failures, already-cancelled login and JSON depth. The connected adapters
probe checks fixture JSON for PIN expiry/cancel, auth failure, pagination, distinct
versions, selected-version loss, private-path stripping and malicious URLs.
After building/installing the app and test APKs, run:

```
adb -s PHONE_WIRELESS_ADDRESS shell am instrument -w -e probe connected-adapters com.evanna.player.dev.test/com.evanna.player.playback.UsbAdapterProbe
```

No real Plex account/server is available. Browser authorization, LAN/remote
server versions, artwork, concurrent playback policy, server entitlement,
native decode, seeking/resume and physical DAC acceptance remain unverified.
In particular recent PMS may require playback decisions before serving a Part;
HTTP failures are surfaced rather than silently requesting a transcode.
