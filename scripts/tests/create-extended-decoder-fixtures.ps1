param([Parameter(Mandatory)][string]$Ffmpeg)
$ErrorActionPreference = 'Stop'
$output = Join-Path $PSScriptRoot '../../app/src/androidTest/assets/decoder-extended'
$temporary = Join-Path $PSScriptRoot '../../.tools/decoder-fixtures'
New-Item -ItemType Directory -Force $output,$temporary | Out-Null
$raw = Join-Path $temporary 'tone.f32le'
$writer = [IO.BinaryWriter]::new([IO.File]::Create($raw))
try {
    for ($frame=0; $frame -lt 88200; $frame++) {
        $writer.Write([single](0.125 * [math]::Sin(2 * [math]::PI * 440 * $frame / 44100)))
        $writer.Write([single](-0.25 * [math]::Sin(2 * [math]::PI * 997 * $frame / 44100)))
    }
} finally { $writer.Dispose() }
foreach ($format in @(
    @{ File='tone.mp3'; Codec='libmp3lame'; Args=@('-b:a','192k') },
    @{ File='tone.m4a'; Codec='aac'; Args=@('-b:a','192k') },
    @{ File='tone.aac'; Codec='aac'; Args=@('-b:a','192k') },
    @{ File='tone.ogg'; Codec='libvorbis'; Args=@('-q:a','6') },
    @{ File='tone.opus'; Codec='libopus'; Args=@('-b:a','160k') }
)) {
    $encoded = Join-Path $output $format.File
    $extra = $format.Args
    & $Ffmpeg -hide_banner -loglevel error -y -f f32le -ar 44100 -ac 2 -i $raw -map_metadata -1 -c:a $format.Codec @extra $encoded
    if ($LASTEXITCODE -ne 0) { throw 'Lossy fixture encoding failed' }
    # FFmpeg 8.1 emits extra AAC tail padding for MP4; the input's known duration is exactly two seconds.
    $trim = if ($format.File -eq 'tone.m4a') { @('-t','2') } else { @() }
    & $Ffmpeg -hide_banner -loglevel error -y -i $encoded -map 0:a:0 @trim -f f32le -c:a pcm_f32le "$encoded.ref.f32le"
    if ($LASTEXITCODE -ne 0) { throw 'Reference decoding failed' }
}
foreach ($variant in 'finite','nan','infinity') {
    $values = switch ($variant) {
        'finite' { @(-2.0,-1.0,-0.5,-[math]::Pow(2,-32),0.0,[math]::Pow(2,-32),0.5,0.999999999999,1.0,2.0) }
        'nan' { @(0.0,[double]::NaN,0.0) }
        'infinity' { @(0.0,[double]::PositiveInfinity,0.0) }
    }
    $floating = Join-Path $temporary "$variant.f64le"
    $writer = [IO.BinaryWriter]::new([IO.File]::Create($floating))
    try { foreach ($value in $values) { $writer.Write([double]$value) } } finally { $writer.Dispose() }
    foreach ($bits in 32,64) {
        & $Ffmpeg -hide_banner -loglevel error -y -f f64le -ar 48000 -ac 1 -i $floating -map_metadata -1 -c:a "pcm_f${bits}le" (Join-Path $output "$variant-f$bits.wav")
        if ($LASTEXITCODE -ne 0) { throw 'Floating PCM fixture encoding failed' }
    }
}
Get-ChildItem $output -File | Get-FileHash -Algorithm SHA256 | Select-Object Hash, @{Name='File';Expression={Split-Path $_.Path -Leaf}}
