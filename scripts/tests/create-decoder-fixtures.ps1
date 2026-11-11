param([Parameter(Mandatory)][string]$Ffmpeg)
$ErrorActionPreference = 'Stop'
$output = Join-Path $PSScriptRoot '../../app/src/androidTest/assets/decoder'
New-Item -ItemType Directory -Force $output | Out-Null
$temporary = Join-Path $PSScriptRoot '../../.tools/decoder-fixtures'
New-Item -ItemType Directory -Force $temporary | Out-Null
# Literal source samples; FFmpeg is only the independent container/codec encoder.
foreach ($bits in 16, 24, 32) {
    $low = [long][math]::Pow(2, 32 - $bits)
    $samples = [long[]]@(-2147483648, (2147483648 - $low), -$low, $low, 0, 0, (3 * $low), (-5 * $low))
    $raw = Join-Path $temporary "stereo-$bits.s32le"
    $stream = [System.IO.File]::Create($raw)
    $writer = [System.IO.BinaryWriter]::new($stream)
    try { foreach ($value in $samples) { $writer.Write([int]$value) } } finally { $writer.Dispose() }
    $formats = @(@{ Extension='wav'; Codec="pcm_s${bits}le" }, @{ Extension='aiff'; Codec="pcm_s${bits}be" })
    if ($bits -le 24) { $formats += @{ Extension='flac'; Codec='flac' }, @{ Extension='m4a'; Codec='alac' } }
    foreach ($format in $formats) {
        $sampleFormat = if ($bits -eq 16) { 's16' } else { 's32' }
        if ($format.Codec -eq 'alac') { $sampleFormat += 'p' }
        & $Ffmpeg -hide_banner -loglevel error -y -f s32le -ar 44100 -ac 2 -i $raw -map_metadata -1 -c:a $format.Codec -sample_fmt $sampleFormat -bits_per_raw_sample $bits (Join-Path $output "stereo-$bits.$($format.Extension)")
        if ($LASTEXITCODE -ne 0) { throw 'Fixture encoding failed.' }
    }
}
& $Ffmpeg -hide_banner -loglevel error -y -f s32le -ar 44100 -ac 1 -i (Join-Path $temporary 'stereo-16.s32le') -map_metadata -1 -c:a pcm_s16le (Join-Path $output 'mono-16.wav')
if ($LASTEXITCODE -ne 0) { throw 'Mono fixture encoding failed.' }
Get-ChildItem $output -File | Get-FileHash -Algorithm SHA256 | Select-Object Hash, @{Name='File';Expression={Split-Path $_.Path -Leaf}}
