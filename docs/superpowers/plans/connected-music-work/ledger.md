# Connected music execution

Authorization: user approved the rest of the roadmap on 2026-11-17, excluding
commercial online providers. Both phone/JD1 playback and DLNA speaker casting.
Baseline `86f2ce8`, branch `v1.6`; unrelated untracked files are preserved.

Ruling: work on the user's existing v1.6 feature branch in this checkout, retaining
its native build/fixture setup and unrelated files. No switch of the user's
workspace or publication. Cost if wrong: changes need a local branch transplant.

Sequence: shared identity/storage/transport and migrations; source browsing/UI;
UPnP and Plex; renderer sessions; Jellyfin/OpenSubsonic; private/local relay,
WebDAV/SMB, radio and bounded owned-media offline support. Optional separate
products (multiroom, Chromecast/AirPlay, Emby, provider integrations) stay deferred.

Every increment has tests, build/device evidence and a local checkpoint. Hardware
or server acceptance that cannot be exercised must remain explicitly unverified.

Identity contract RED: SourceReference tests failed because the type was absent.
Initial dependency check rejected OkHttp 5.5.1 (requires compileSdk 37). Inspected
published AAR metadata: 5.4.0 supports compileSdk 36; selected it without changing
Android/Gradle targets or using the JVM artifact to evade Android requirements.

Implemented so far (not yet a completed increment): opaque SourceReference,
QueueSource/SavedQueueEntry reference field and queue v4 migration; OkHttp-backed
HttpAccess, CancelScope and one-block RangeReader. Initial unit suite passed.
Then two behavioral RED tests reproduced HTTPS downgrade admission and blocked
source-open pause. Fixed NetworkPolicy permitsHttp, added cancellable PcmTrack
open and controller opening-token/epoch; owner invalidates it before its lock.
Full unit suite passed after fixes (session 2003, BUILD SUCCESSFUL in 4m6s).

Ruling: debug APK is now com.evanna.player.dev, label Evanna Dev. Queue v4 and
future personal-data migrations must never be applied to the user's v1.5 app then
"restored" to incompatible older schemas. Release application ID is unchanged.
Update test runner/scripts/docs to the dev package before device use.

User reports no real servers or network speakers. Continue with controlled
servers/software renderer fixtures; physical NAS/speaker compatibility and actual
Plex account sign-in cannot be claimed. M30s/JD1 were used earlier; this task has
not installed or played anything yet. Wireless address last 192.168.29.141:40027
is historical, must recheck before device work.

Current build: connected-foundation Android probe references SourceStore not yet
implemented (intentional RED). Probe tests legacy queue v3 preservation, repeated
remote occurrences and stable encrypted connection/item storage. Next implement
SourceStore + private authenticated source registry, HTTP FD bridge/native track,
playlist/user-data source references, then UI/connectors/casting and remaining
owned-media features. No final review or checkpoint of implementation yet.

Known follow-ups before final verification: make RangeReader IO failures sticky
(cancel scope on every IO failure); fast cancel on direct controller.close too;
source factory preparation must be cancellable before UsbSessionOwner's lock
(its QueueSource.create currently remains non-cancellable); remote history/favorite
and playlist paths still need source-qualified identity; production resolver is
not wired to saved sourceRef yet. No feature completeness claims are justified.

Foundation correction: session 82817 unit run found one existing queued-coroutine
fixture race, not a network failure; fast IO could finish before suspension.
The fixture now gates output acquisition with a latch. All new cancellation,
HTTPS-policy and terminal truncated-read assertions passed in that run.
Session 77981 reruns full units and app/test builds with lifecycle corrections.

Ruling: use scoped parallel adapter implementers now that SourceClient is stable,
per dispatching-parallel-agents/subagent-driven-development — UPnP, Plex and
personal-server adapters own disjoint packages; root owns shared transport,
persistence, session and UI. This replaces the self-chosen inline-only strategy;
the user did not mandate inline. Cost if wrong: integration rework, constrained
by the shared API and root's single serialized build/device lane.

Preflight contract scan:
| Work | Shared input/output | Ruling |
| Foundation/UPnP | SourceClient, CancelScope, HttpAccess | Adapters only in own package; root integrates manifest/store/UI. |
| Foundation/Plex | SourceClient, encrypted config | Token only private config; opaque IDs in public state. |
| Foundation/Jellyfin/Subsonic/WebDAV | same contract | Independent source-specific auth/parsers, shared transport mandatory. |
| All adapters/browser | SourcePage RemoteEntry, SourceItem parent | Root assigns opaque identity and persists metadata; adapters never write DB. |
| Local/server queue & personal data | SourceReference, personalId | Preserve strict local identity checks; source-qualified personal IDs opt in. |
| Renderer/local engine | output handoff | Root serializes stop acknowledgement; no speaker fallback or dual playback. |
| Offline/radio | finite versus unknown length | Finite reader must not fake radio size/seek; separate streaming contract. |
| Each task/test | controlled fixtures | No real servers or speakers available; fixture results cannot claim device interoperability. |

Current implementation through the integrated UI:
- Library and Settings expose Sources. Direct finite HTTP(S), UPnP/DLNA, Plex,
  Jellyfin, OpenSubsonic/Navidrome, WebDAV, SMB2/3 and live radio work through
  the shared catalog and queue. Source URLs and credentials stay behind opaque
  references; radio has its own native nonseekable pipe.
- User DB v8→9 preserves local playlist rows and adds server refs. Favorite
  and history IDs accept strict source-qualified IDs. Offline saves are
  bounded and atomically published. Disconnect preserves offline copies.
- DLNA uses a separate Media3 route with a peer-bound item relay. Selection
  stops the current output before handoff. Unknown remote stop blocks local
  handoff. Real speaker compatibility is not established.
- Public internet providers remain deferred. Remote gapless stays gated on
  continuity evidence. No native DAC or actual-account claim for this change.

Checks on 2026-11-17:
- Build 98301 compiled initial Sources integration.
- Build 89505 passed JVM suites, then hit an OSGi resource duplication from
  SMBJ and jspecify. Excluded that non-Android metadata specifically.
- Build 54762 passed app and USB JVM suites and produced four debug ABI
  APKs plus instrumentation. Installed Dev.apk and test APK by wireless
  ADB only after checking the package names and M30s serial.
- M30s probe connected-foundation returned RESULT_OK. Queue/playlist v3/v8
  migrations, opaque favorites/history, ciphertext row binding, byte-exact
  offline download/cancel/unlink, full native FLAC PCM and seek all passed.
- M30s adapter probe returned RESULT_OK for UPnP, Plex, Jellyfin, Subsonic and
  WebDAV fixtures; radio probe returned RESULT_OK for chunked ICY AAC PCM
  identity and cancellation. The first wrapper incorrectly demanded a
  literal PASS prefix for each report; now standardized in UsbAdapterProbe.
- Literal IP validation was added because OkHttp resolves IP literals
  without calling the custom DNS. External plaintext and loopback are
  explicitly checked, and user-selected/test policies are separate.
- Build 17728 found a nullable renderer link compile error; fixed it.
- Build 98112 reruns all JVM/debug/test APK work and checks the unsigned
  minified release build. Casting probe and UI picker included. No push,
  publication or production-app install.

Review fixes: remembered DAC attachment cannot interrupt selected/connecting
DLNA; local gapless never opens a remote successor early; opening cancellation
stays registered through the initial seek; reconnect keeps stable encrypted
server item metadata; Plex search is available inside a music library; clean
radio EOF stops the live stream. The first renderer probe also detected a
Content-Range length shadowing bug in the relay, fixed and verified.

Build 26107: 490 app and 6 USB JVM tests passed, including sparse 64-bit HTTP
ranges, remote successor drain, seek cancellation, SMB socket/read contracts,
radio parsing and renderer completion. Its package passed all three M30s
instrumentation groups via wireless ADB: connected-adapters (including Media3
route handoff and actual relay/SOAP), connected-foundation, and radio. The
simulated renderer verified both automatic DAC paths remain blocked while
casting, and an explicit handoff only after Stop acknowledgement. The M30s UI
showed all eight source setup choices and the Jellyfin setup form.

Build 58125 also passed minified unsigned release assembly and release lint for
all four ABIs after accounting for optional JVM-only SMB dependencies. The
installed production v1.5 APK SHA-256 matches its original recorded value
772a32d1188004a9b6b22a643dcb7ba468b7ba343d5250b4d42f40f05983d953.
Explicit LAN HTTP opt-in is required even after automatic discovery. The
speaker picker is reachable from Settings on an empty library. Sleep timer
options are hidden on the DLNA route, where the speaker owns its playback.

The focused final re-review found no remaining concrete regressions in its six
fixes. UI screenshots on the M30s confirm source types and manual/discovered
speaker setup render with the existing app theme and visible control labels.
Full MIT/Bouncy Castle notices are bundled with the SMB dependencies.

The last license rebuild exposed the same pre-existing fast-IO continuation
race in two start-result cancellation fixtures. Both now hold simulated start
until the caller has suspended, using the Output fixture's existing latch.
This changes test synchronization only. Build 74846 reruns the complete checks.

Final checkpoint evidence:
Build 74846 finished successfully (14m26s): 490 app + 6 USB unit tests,
four-ABI debug assembly, instrumentation assembly, four-ABI minified unsigned
release assembly and release vital lint. The final installed Dev app passed
connected-foundation, connected-adapters (including renderer/Media3 handoff)
and radio on the M30s over wireless ADB; all returned instrumentation code -1.
Reports are retained locally under .tools/connected-music/*-final.log.

The implemented source/browser/queue/offline/casting work is checkpointed locally
on v1.6. Commercial providers remain deferred as requested. Acceptance using
actual server accounts, a real NAS/speaker and physical DAC streams remains
unverified because none are available now. Network gapless stays disabled;
DLNA uses the phone relay on the same IPv4 subnet and does not contribute to
local listening counts or run the phone sleep timer. No publication was made.
