# Network playback feasibility test

This account-free prototype is instrumentation-only. It does not add streaming
services or a streaming screen to Evanna. The loopback check uses the generated
`decoder-seek/seek.flac` fixture, a loopback HTTP Range server, Android's seekable
file-proxy API, the unmodified native decoder, and the existing EQ/output controller.
The `network-streaming` mode uses a synthetic USB transport and produces no
hardware audio. The separate `network-usb` modes below open a physical DAC.

## Run on an emulator

Current debug builds install separately as **Evanna Dev** (`com.evanna.player.dev`)
so production queue/database migrations never affect the installed release app.

Build and install the debug app and instrumentation APK for the emulator's ABI:

```powershell
.\gradlew.bat -PevannaAbiSplits=true :app:assembleDebug :app:assembleDebugAndroidTest
adb -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-x86_64-debug.apk
adb -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -e probe network-streaming com.evanna.player.dev.test/com.evanna.player.playback.UsbAdapterProbe
```

Success requires `INSTRUMENTATION_RESULT: report=PASS: ...` and
`INSTRUMENTATION_CODE: -1`. The adb process exit code alone does not establish success.
The probe temporarily changes EQ settings and restores their logical value and
exact stored configuration string in finally.

## Verified on 2026-11-16

- 44.1 kHz, 24-bit stereo FLAC starts after 262,144 of 1,075,805 compressed bytes
  have transferred. The adapter keeps at most one 64 KiB range block.
- Complete decoded PCM and forward/backward seeks match the same local fixture.
- Delayed responses work. Ignored/incorrect ranges, truncated bodies, redirects,
  stalled responses and trickled bodies fail instead of being accepted as audio.
- Cancellation of a stalled request completes in 7 ms, below its one-second
  inactivity timeout. Descriptor release and callback-thread termination are checked.
- The existing USB controller receives unchanged PCM with EQ off, attenuated PCM
  with the preamp on, and handles pause/resume, seeking and a simulated disconnect.
  A disconnect must report DISCONNECTED, stop writing and close successfully.
- 460 app and 6 USB module unit tests pass; debug app/test builds pass. The release
  manifest excludes the debug-only loopback cleartext policy.

## Physical M30s/JD1 check on 2026-11-17

The ordinary `network-streaming` probe also passed on the Samsung M30s running
Android 11/API 30, including exact PCM/seek comparison and 7 ms cancellation.
An intentionally changed track format is rejected and its decoder/proxy released.

`network-usb` passed using the FIIO JD1 (VID 0x31b2/PID 0x3214):

- Source and reported USB output: 44,100 Hz, 24-bit stereo, no resampling.
- Hardware gain was read back at the JD1 minimum of -74 dB before PCM writes.
  Every input block was also checked against a 20% full-scale amplitude ceiling.
- Playback had started after 393,216 of 1,075,805 compressed bytes transferred.
- EQ off, EQ on with -6 dB preamp, pause/resume, forward seek to 2 seconds,
  backward seek to 0.5 seconds and 50 ms delayed range responses passed.
- All three phases reported zero underruns, transfer errors, clipping and dropped
  packets. The EQ-on stream is correctly marked processed; the native driver's
  no-conversion flag alone does not establish an unchanged sample path.
- `network-usb-disconnect` passed with a real physical unplug: missing device and
  disconnected error were both observed, accepted PCM stopped advancing, and no
  Android media fallback occurred. The observed disconnect-to-stop interval was
  115 ms with 30 ms polling; this is not an external cable-event measurement.
  The first 90-second attempt timed out before the user's unplug and was not
  counted as a pass. The repeat used a 180-second action window.

The physical modes use a standalone controller and generated audio. They do not
save a queue or DAC profile, and restore the exact saved EQ configuration in finally.
The release app and pre-existing test APK were backed up before temporary installation,
then restored with exact APK SHA-256 matches. EQ, playback-settings and user-database
file hashes matched their saved values. The queue database's byte hash changed
across app restarts; no logical row snapshot was taken, so exact queue equality
was not established. The probe itself never calls queue persistence.

```powershell
adb -s PHONE_SERIAL shell am instrument -w -e probe network-usb com.evanna.player.dev.test/com.evanna.player.playback.UsbAdapterProbe
adb -s PHONE_SERIAL shell am instrument -w -e probe network-usb-disconnect com.evanna.player.dev.test/com.evanna.player.playback.UsbAdapterProbe
```

Use a compatible, signed debug target/test pair and grant USB access after app
replacement. Keep earphones out during initial tests. For the disconnect mode,
unplug only after `READY_TO_UNPLUG`; the probe waits up to 180 seconds, requires an
actual missing USB device and disconnected error, and checks that writing stops
without Android playback. A timeout is a failure, not evidence of safe unplug.

## Limits

No analog output measurement, service login, DRM, signed service URL, token
refresh or provider playback access is tested.
Only the generated 44.1 kHz/24-bit stereo source and this JD1 were checked on hardware.
The test adapter accepts loopback HTTP and HTTPS on `raw.githubusercontent.com`
using the platform's normal certificate/hostname verification. It rejects URL
credentials, redirects, other HTTPS hosts/ports and external cleartext HTTP.
Header parsing has an inactivity
timeout; its body deadline is not a universal end-to-end HTTP deadline. Production
network handling and provider permissions need separate work before this can ship.

## Internet HTTPS probe

```powershell
adb -s PHONE_WIRELESS_ADDRESS shell am instrument -w -e probe network-https com.evanna.player.dev.test/com.evanna.player.playback.UsbAdapterProbe
```

This mode downloads a bounded reference copy of the CC0
[IETF CELLAR FLAC test file](https://github.com/ietf-wg-cellar/flac-test-files/blob/aa7b0c6cf32994c106ae517a08134c28a96ff5b2/subset/01%20-%20blocksize%204096.flac),
then opens independent HTTPS range requests through the decoder and EQ/controller.
The reference is only a test oracle: it is not used as the streaming source.
The 549,954-byte file is pinned by commit and SHA-256
`02c7e60ae7788c2cb6898a0a46985d759f1e62482ddd965a374dfd09a2a8390f`.
It contains seven seconds of 44.1 kHz/16-bit stereo audio. No fixture audio is
bundled into the production app, and output is synthetic: this mode never opens
an Android audio stream or USB device.

Both probe modes also check continued progress across delayed range responses,
active-source connection loss stopping as SOURCE_READ_FAILED without fallback,
explicit retry with a fresh source, and pause cancellation during a confirmed
stalled request. Faults are injected by the loopback fixture server through the
same range adapter; the internet host and phone network are not disrupted.
There is no automatic retry policy or production buffering UI in this spike.

Verified on 2026-11-17 on the M30s (Android 11/API 30), over wireless debugging:

- HTTPS started decoding after 262,144 of 549,954 compressed bytes transferred.
  Complete PCM and forward/backward seeks matched the checksum-verified reference.
- EQ-off PCM was exact and EQ-on attenuation matched the -6 dB preamp.
- Delayed active reads, dropped connection, stopped writer, no Android fallback,
  explicit fresh-source retry and simulated USB disconnect all passed.
- Stalled-open cancellation took 13 ms; pausing during a confirmed stalled read
  took 16 ms, both below the ordinary one-second fixture timeout.
- The updated loopback regression probe also passed (11 ms cancellation,
  20 ms blocked-read pause). All 466 unit tests and debug app/test builds passed.
- EQ, playback-settings, queue DB/journal and user DB/WAL byte hashes matched
  their saved values. The queue was additionally captured before/after and matched
  byte-for-byte. Both original APKs were restored with exact SHA-256 matches.

This round used synthetic output throughout. The physical JD1 evidence above
belongs to the earlier generated-audio loopback test, not an internet hardware run.
