"""Builds tiny lyrics fixtures from Evanna's own decoder fixtures with host FFmpeg (stream copy, no new audio)."""
import hashlib
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parent.parent
assets = root / "app/src/androidTest/assets"
output = assets / "lyrics"
output.mkdir(exist_ok=True)

# Keep these texts identical to LyricsProbe.kt.
M4A = "[00:00.50]First line\n[00:01.25]Second line é"
FLAC = "[ar:Evanna]\n[offset:+250]\n[00:01.00][00:03.50]Hello <00:01.20>again\n[00:02.00]"
OGG = "Plain first\n\nPlain second"


def run(*args):
    subprocess.run(["ffmpeg", "-v", "error", "-y", *map(str, args)], check=True)


run("-i", assets / "decoder/stereo-16.m4a", "-c", "copy", "-metadata", f"lyrics={M4A}", output / "timed.m4a")
run("-i", assets / "decoder/stereo-16.flac", "-c", "copy", "-metadata", f"LYRICS={FLAC}", output / "timed.flac")
run("-i", assets / "decoder-extended/tone.ogg", "-c", "copy", "-metadata", f"UNSYNCEDLYRICS={OGG}", output / "plain.ogg")

lines = [f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}" for path in sorted(output.iterdir())
         if path.suffix in (".m4a", ".flac", ".ogg")]
(output / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
