# HTTPS streaming feasibility plan

> Execute inline using executing-plans; one scoped review at the end.

**Goal/design:** Extend the existing instrumentation-only spike to an actual
public HTTPS FLAC URL, retaining the decoder/EQ/controller and synthetic output.
Use a commit-pinned CC0 IETF CELLAR test file, verify its SHA-256 and bounded
length for the local reference, then stream independent HTTP ranges and compare
PCM/seeks. Existing controlled loopback faults test the same adapter. No audible
hardware test is needed again for this transport-only change.

**Scope:** NetworkStreamingProbe (HTTPS reference, shared assertions and loss/
restart/cancel checks), HttpRangeProxy (HTTPS admission/time budget), fixture
server (dropped connection), runner (network-https mode), documentation.
No production code, credentials, new dependencies, provider features or UI.
Preserve app data and restore original phone/test APKs after wireless testing.

- [x] Checkpoint prior verified prototype on v1.6 (01caf78).
- [x] Add HTTPS probe and observe rejection by the loopback-only adapter.
- [x] Allow HTTPS with platform certificate/hostname verification, bounded ranges,
  no redirects, and a finite timeout suitable for internet requests.
- [x] Verify remote PCM/forward-backward seeks, incremental startup, EQ, transient
  buffering, source loss stopping without fallback, explicit fresh retry and pause
  cancellation while a request is blocked. Keep invalid-response regressions.
- [x] Build/run on device via wireless debugging, restore APKs, review and commit.

Results: new HTTPS probe first failed at the existing URL admission guard, then
passed after support was added. Final HTTPS run on M30s/API 30 decoded after
262144/549954 bytes; exact PCM/seeks and EQ passed. Controlled loopback faults
proved active buffering, source loss stopping without fallback, fresh retry and
blocked-read pause (16 ms); stalled-open cancel was 13 ms. Updated loopback run
passed too (11 ms cancel, 20 ms pause). 460 app + 6 USB unit tests passed; builds
passed. Scoped review caught and fixed a fixture latch race by signalling only
for a captured stall. No important findings remain. All compared private state
hashes, including the queue, match before/after; original APK hashes match after
wireless restoration. No physical audio, provider login or Qobuz access tested.

Risks to check: unsafe cleartext expansion; stale/truncated range data accepted;
unbounded network waits; cancel waiting for timeout; orphaned descriptors/threads;
user settings altered by the probe. Timeout/transport limitations must remain
documented. Synthetic USB checks do not establish Qobuz or analog behavior.
