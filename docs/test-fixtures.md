# Test fixture provenance

All media under `app/src/androidTest/assets/` is test data, packaged only in the separate instrumentation APK. The fixture generation code is included in `scripts/` and `scripts/tests/`.

| Fixtures | Original inputs and generators |
| --- | --- |
| Decoder PCM and lossless files | Literal signed sample arrays in `scripts/tests/create-decoder-fixtures.ps1`, then lossless container conversions in `create-lossless-fixtures.ps1`. |
| Extended decoder and seeking files | Synthesized tones, chirps, floating-point edge cases and generated silence from the corresponding `create-extended-decoder-fixtures.ps1`, `create-seek-fixtures.ps1` and `create-long-seek-seeds.ps1` scripts. |
| DSD files | Algorithmic data from `scripts/generate-dsd-fixtures.py`. |
| Embedded artwork | An original two-colour PNG and solid blue JPEG from `scripts/generate-artwork-fixtures.py`, attached to the generated decoder media. |
| Tags, sort tags and loudness tags | Generated decoder media with synthetic metadata from the corresponding Python fixture generators. |
| Lyrics | Original short test lines from `scripts/generate-lyrics-fixtures.py`. |
| CUE and library fixtures | Generated decoder media and synthetic index/tag data. |

Existing SHA256SUMS files identify the generated assets. Generated artwork and metadata are original project test material covered by the project licence. Third-party encoder tools used to produce fixtures retain their own licences and are outside the distributed app.

Some decoder fixtures contain signed extrema and are unsuitable for listening. Keep these files out of a personal music library or hardware playback test.
