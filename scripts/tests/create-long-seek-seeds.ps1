param([Parameter(Mandatory)][string]$Ffmpeg)
$ErrorActionPreference = 'Stop'
$output = Join-Path $PSScriptRoot '../../app/src/androidTest/assets/decoder-seek'
New-Item -ItemType Directory -Force $output | Out-Null
foreach ($format in @(
    @{ File='scan-seed.mp3'; Codec='libmp3lame'; Args=@('-write_xing','0','-id3v2_version','0','-write_id3v1','0') },
    @{ File='scan-seed.aac'; Codec='aac'; Args=@() }
)) {
    # Raw constant-format frames can repeat on-device without a large packaged song or stale duration tag.
    & $Ffmpeg -hide_banner -loglevel error -y -f lavfi -i 'anoisesrc=amplitude=0.05:sample_rate=44100:duration=2:seed=8142[a];anoisesrc=amplitude=0.05:sample_rate=44100:duration=2:seed=8143[b];[a][b]amerge=inputs=2' -ac 2 -map_metadata -1 -c:a $format.Codec -b:a 320k @($format.Args) (Join-Path $output $format.File)
    if ($LASTEXITCODE -ne 0) { throw "Long seek seed encoding failed: $($format.File)" }
}
