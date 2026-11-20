# Network playback spike

User approved an account-free streaming-to-DAC feasibility test on `v1.6`.
No subscription, provider SDK, login screen or production streaming feature is included.

Use Android's seekable `StorageManager.openProxyFileDescriptor` with bounded HTTP
Range reads to feed the existing native decoder. Keep the prototype in androidTest;
keep the local-file decoder validation and all production playback code unchanged.
Allow loopback HTTP in debug builds only; release network policy stays unchanged.

- [x] Add an instrumentation probe: stream the existing generated seek FLAC
  through a loopback HTTP server; compare decoded samples to the local fixture,
  verify playback begins before the full file is transferred, and compare seeks.
- [x] Implement a one-block bounded range cache with strict response validation,
  finite timeouts and cancellation that disconnects an in-flight request.
- [x] Exercise delayed reads, invalid/short responses, cancellation, repeated closes,
  and proxy resource release. Use the existing controller with a synthetic USB
  transport to prove EQ processing, pause/seek/resume and output-disconnect handling.
- [x] Run the probe on an emulator plus the unit suite/build checks; document exactly
  which results are synthetic and which require a later physical USB-DAC test.

Ruling: choose Android's file proxy over a new JNI/network decoder interface because
it preserves the existing decoder and output engine. This is a feasibility spike,
not proof that a streaming service grants full-track playback or permits raw audio.

No push, release, phone install or change to the user's saved queue/settings is part
of this spike. Tests must restore any temporarily changed EQ state.

Completed: first emulator run verified streaming/PCM/seeking but caught decoder
header retries extending a failed trickle request. Marking a proxy read failure as
terminal fixed it. Final probe passes all scenarios, with 262144/1075805 bytes at
startup and 7 ms cancellation. 466 unit tests pass; app/test builds and release
manifest isolation verified. See `docs/network-playback-spike.md` for run steps and
limits. No production decoder/output code, service integration, phone install,
commit, push or release was added.
