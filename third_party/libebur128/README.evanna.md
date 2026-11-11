# libebur128

Unmodified library files from version1.2.6, commit `67b33abe1558160ed76ada1322329b0e9e058b02` of [jiixyj/libebur128](https://github.com/jiixyj/libebur128/tree/67b33abe1558160ed76ada1322329b0e9e058b02), retrieved2026-11-04. Built statically only into the offline decoder's loudness measurement support. No playback sample scaling or resampling is added by this library.

| Local file | Upstream path | SHA-256 |
| --- | --- | --- |
| ebur128.c | ebur128/ebur128.c | c2fc562f1088cacab4d21250b6e04996ef36c7694ea901e08cc4d64eb542b78d |
| ebur128.h | ebur128/ebur128.h | a988fa03828bcdd6258e6c52300d709a58727272a9ce6cd3fbf351f090517111 |
| COPYING | COPYING | d6b4754bb67bdd08b97d5d11b2d7434997a371585a78fe77007149df3af8d09c |
| queue/sys/queue.h | ebur128/queue/sys/queue.h | 8b284031772b1ba2035d9b05b24f2cb9b23e7bd324bcccb5e3fcc57d34aafa48 |

The library's COPYING is MIT, copyright Jan Kokemüller. The bundled queue header carries the Regents of the University of California's three-clause BSD notice. Original notices remain in these files; exact copies are bundled in the app's licenses assets. Both the host tests and Android build use this same queue header. No optional reference programs or plugins are imported.

Evanna uses bounded histogram integration and sample peak. It serializes all libebur128 calls because this pinned release rewrites global constants during every state initialization. The measurement consumes decoded S32 PCM and never opens an audio output. Reference tests use generated samples and are independent of the Android/USB route. Integration changes belong in Evanna's own LoudnessMeter.h; keep these upstream files unmodified and update hashes/notices on any version change.
