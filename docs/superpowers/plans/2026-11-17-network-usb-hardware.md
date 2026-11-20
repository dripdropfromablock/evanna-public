# Network playback on the physical USB DAC

User authorized continuing the prototype with M30s/JD1 hardware validation.
Phone is reachable over wireless ADB. User connected the JD1, granted USB access,
and was asked to keep the earphones out for the first test.

- [x] Extract the existing test-only network PCM track so emulator and hardware
  checks use the same proxy/decoder code and verify the actual decoded format.
- [x] Add `network-usb` and `network-usb-disconnect` instrumentation modes. Use only
  the generated chirp fixture, a standalone controller, no saved queue, and a
  read-back hardware gain at -80 dB or the DAC minimum if higher. Check the low
  gain and bounded sample amplitude before every physical PCM write.
- [x] Test the ordinary network checks on the phone, then native output format,
  processing flags, EQ off/on, seeking, pause/resume, delayed range reads, and
  transfer/clipping counters. Report conversion if the DAC needs it.
- [x] Coordinate a real unplug only after the probe reports READY_TO_UNPLUG;
  require the writer to stop and no Android output fallback. Timeout safely;
  allow 180 seconds for the user's physical action.
- [x] Preserve the original release APK, sign the temporary app/test APKs with its
  existing key, restore the original app afterward, and restore EQ config in finally.
- [x] Run unit/build checks and a scoped safety review; document actual evidence.

Results: M30s/API 30 synthetic network checks passed; JD1 direct USB passed at
44.1 kHz/24-bit stereo without resampling, hardware gain -74 dB (its minimum),
EQ off/on, both seek directions, pause/resume, delayed ranges, and zero reported
transfer/underrun/clipping/drop counters. Physical unplug passed on the repeat
with observed disconnect-to-stop 115 ms at 30 ms polling and no Android fallback.
First unplug attempt timed out before the user's action and was not counted.
All 466 unit tests passed; app/test builds passed. Scoped review fixes cover
format-failure cleanup, dropped-packet checks and immutable snapshot classification
through disconnect/end transitions. Original app and pre-existing test APK hashes
match after restoration; EQ/playback-settings/user DB hashes match. Queue DB byte
hash differed after app restarts and logical row equality was not checked.

No service integration, Qobuz correspondence, source push, public release, changes
to the production decoder/output path or saved DAC profile are part of this test.
