param([Parameter(Mandatory)][string]$Ffmpeg)
$ErrorActionPreference = 'Stop'
$output = Join-Path $PSScriptRoot '../../app/src/androidTest/assets/decoder-seek'
New-Item -ItemType Directory -Force $output | Out-Null
foreach ($format in @(
    @{ File='seek.flac'; Codec='flac' },
    @{ File='seek-alac.m4a'; Codec='alac' },
    @{ File='seek.mp3'; Codec='libmp3lame' },
    @{ File='seek.m4a'; Codec='aac' },
    @{ File='seek.aac'; Codec='aac' },
    @{ File='seek.ogg'; Codec='libvorbis' },
    @{ File='seek.opus'; Codec='libopus' }
)) {
    & $Ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'aevalsrc=0.1*sin(2*PI*(230*t+3*t*t))|0.1*sin(2*PI*(477*t+5*t*t)):s=44100:d=12' -map_metadata -1 -c:a $format.Codec (Join-Path $output $format.File)
    if ($LASTEXITCODE -ne 0) { throw "Seek fixture encoding failed: $($format.File)" }
}
