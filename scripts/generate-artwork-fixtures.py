"""Generate original embedded-cover fixtures using the existing decoder audio and host FFmpeg."""
import hashlib
from pathlib import Path
import struct
import subprocess
import zlib

root = Path(__file__).resolve().parent.parent
assets = root / "app/src/androidTest/assets"
output = assets / "artwork"
output.mkdir(exist_ok=True)


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))


rows = b"".join(b"\0" + b"".join(bytes((131, 198, 181) if x < 160 else (112, 69, 45))
                                 for x in range(320)) for _ in range(160))
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 320, 160, 8, 2, 0, 0, 0))
png += chunk(b"IDAT", zlib.compress(rows)) + chunk(b"IEND", b"")
(output / "front.png").write_bytes(png)


def run(*args):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", *map(str, args)], check=True)


run("-f", "lavfi", "-i", "color=c=0x31548f:s=128x128", "-frames:v", 1, output / "back.jpg")
audio = assets / "decoder/stereo-24.flac"
run("-i", audio, "-i", output / "front.png", "-map", "0:a", "-map", "1:v", "-c", "copy",
    "-disposition:v", "attached_pic", "-metadata:s:v", "comment=Cover (front)",
    "-metadata", "title=Evanna embedded cover", "-metadata", "artist=Local artist",
    "-metadata", "album=Embedded artwork check", output / "cover.flac")
run("-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo", "-i", output / "front.png", "-t", 2,
    "-map", "0:a", "-map", "1:v", "-c:a", "flac", "-sample_fmt", "s16", "-c:v", "copy",
    "-disposition:v", "attached_pic", "-metadata:s:v", "comment=Cover (front)",
    "-metadata", "title=Evanna embedded cover", "-metadata", "artist=Local artist",
    "-metadata", "album=Embedded artwork check", output / "cover-silence.flac")
run("-i", assets / "decoder-extended/tone.mp3", "-i", output / "back.jpg", "-i", output / "front.png",
    "-map", "0:a", "-map", "1:v", "-map", "2:v", "-c", "copy", "-id3v2_version", 3,
    "-metadata:s:v:0", "comment=Cover (back)", "-metadata:s:v:1", "comment=Cover (front)", output / "covers.mp3")
run("-i", assets / "decoder/stereo-24.m4a", "-i", output / "back.jpg", "-map", "0:a", "-map", "1:v",
    "-c", "copy", "-disposition:v", "attached_pic", output / "cover.m4a")

# Vorbis picture comments use the FLAC picture block, independent of the FFmpeg extraction accessor.
picture = struct.pack(">II", 3, 9) + b"image/png" + struct.pack(">I", 0)
picture += struct.pack(">IIIII", 320, 160, 24, 0, len(png)) + png
import base64
run("-i", assets / "decoder-extended/tone.ogg", "-c", "copy", "-metadata",
    "METADATA_BLOCK_PICTURE=" + base64.b64encode(picture).decode("ascii"), output / "cover.ogg")

lines = [f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}" for path in sorted(output.iterdir())
         if path.suffix in (".png", ".jpg", ".flac", ".mp3", ".m4a", ".ogg")]
(output / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
