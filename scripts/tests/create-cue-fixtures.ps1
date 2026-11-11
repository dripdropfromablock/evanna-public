param([Parameter(Mandatory)][string]$Ffmpeg)
$ErrorActionPreference = 'Stop'
# Original fixture: frame n of a 3 s 16-bit stereo FLAC holds L = n & 0xFFFF, R = n >> 16, so every sample names its frame.
$output = Join-Path $PSScriptRoot '../../app/src/androidTest/assets/cue'
$temporary = Join-Path $PSScriptRoot '../../.tools/decoder-fixtures'
New-Item -ItemType Directory -Force $output, $temporary | Out-Null
$raw = Join-Path $temporary 'cue.s16le'
$stream = [System.IO.File]::Create($raw)
$writer = [System.IO.BinaryWriter]::new($stream)
try { for ($n = 0; $n -lt 132300; $n++) { $writer.Write([int16][BitConverter]::ToInt16([BitConverter]::GetBytes([uint16]($n -band 0xFFFF)), 0)); $writer.Write([int16]($n -shr 16)) } }
finally { $writer.Dispose() }
& $Ffmpeg -hide_banner -loglevel error -y -f s16le -ar 44100 -ac 2 -i $raw -map_metadata -1 -c:a flac (Join-Path $output 'album.flac')
if ($LASTEXITCODE -ne 0) { throw 'CUE fixture encoding failed.' }
Set-Content -LiteralPath (Join-Path $output 'album.cue') -Encoding ascii -Value @(
    'PERFORMER "Evanna"', 'TITLE "CUE fixture"', 'FILE "album.flac" WAVE',
    '  TRACK 01 AUDIO', '    TITLE "One"', '    INDEX 01 00:00:00',
    '  TRACK 02 AUDIO', '    TITLE "Two"', '    INDEX 00 00:00:70', '    INDEX 01 00:01:05',
    '  TRACK 03 AUDIO', '    TITLE "Three"', '    INDEX 01 00:02:13')
Get-ChildItem $output -File | Get-FileHash -Algorithm SHA256 | Select-Object Hash, @{Name='File';Expression={Split-Path $_.Path -Leaf}}
