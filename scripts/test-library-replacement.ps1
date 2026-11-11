
param(
    [Parameter(Mandatory)][string]$AppApk,
    [Parameter(Mandatory)][string]$TestApk,
    [Parameter(Mandatory)][string]$AvutilLibrary,
    [Parameter(Mandatory)][string]$LibusbLibrary,
    [Parameter(Mandatory)][string]$DeviceSerial,
    [string]$AndroidSdk = $env:ANDROID_HOME
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $AndroidSdk) {
    $sdkLine = Get-Content -LiteralPath (Join-Path $projectRoot 'local.properties') |
        Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if (-not $sdkLine) { throw 'Set ANDROID_HOME or pass -AndroidSdk.' }
    $AndroidSdk = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
}
$tools = Join-Path $AndroidSdk 'build-tools/36.0.0'
$adb = Join-Path $AndroidSdk 'platform-tools/adb.exe'
$aapt = Join-Path $tools 'aapt2.exe'
$align = Join-Path $tools 'zipalign.exe'
$sign = Join-Path $tools 'apksigner.bat'
$keytool = (Get-Command keytool -ErrorAction Stop).Source
foreach ($file in @($AppApk, $TestApk, $AvutilLibrary, $LibusbLibrary, $adb, $aapt, $align, $sign)) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing file: $file" }
}
function Invoke-Checked([string]$Tool, [string[]]$ToolArgs) {
    $output = & $Tool @ToolArgs 2>&1
    if ($LASTEXITCODE -ne 0) { throw "$Tool failed ($LASTEXITCODE): $($output -join [Environment]::NewLine)" }
    $output
}
$package = 'com.evanna.player.lgplreplacement'
$badging = Invoke-Checked $aapt @('dump', 'badging', $AppApk)
if (($badging -join [Environment]::NewLine) -notmatch "(?m)^package: name='com\.evanna\.player\.lgplreplacement' ") {
    throw 'Build the isolated test application ID described in docs/native-library-replacement.md.'
}
$testBadging = Invoke-Checked $aapt @('dump', 'badging', $TestApk)
if (($testBadging -join [Environment]::NewLine) -notmatch "(?m)^package: name='com\.evanna\.player\.lgplreplacement\.test' ") {
    throw 'The instrumentation APK must target the isolated replacement application.'
}
$state = Invoke-Checked $adb @('-s', $DeviceSerial, 'get-state')
if (($state -join '').Trim() -ne 'device') { throw 'The explicitly selected device is not ready.' }

$outputDir = Join-Path $projectRoot '.tools/lgpl-replacement'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$repacked = Join-Path $outputDir 'replacement-unaligned.apk'
$aligned = Join-Path $outputDir 'replacement-aligned.apk'
$signed = Join-Path $outputDir 'replacement-signed.apk'
$signedTest = Join-Path $outputDir 'replacement-tests-signed.apk'
$key = Join-Path $outputDir 'replacement-test.p12'
$log = Join-Path $outputDir 'replacement-results.log'
$entries = @{
    'lib/arm64-v8a/libavutil.so' = $AvutilLibrary
    'lib/arm64-v8a/libusb-1.0.so' = $LibusbLibrary
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
function Get-ApkLibraryHash([string]$Apk, [string]$EntryName) {
    $zip = [IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($Apk))
    try {
        $entry = $zip.GetEntry($EntryName)
        if (-not $entry) { throw "Missing APK library: $EntryName" }
        $stream = $entry.Open()
        $hash = [Security.Cryptography.SHA256]::Create()
        try { -join ($hash.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') }) }
        finally { $hash.Dispose(); $stream.Dispose() }
    } finally { $zip.Dispose() }
}
foreach ($entry in $entries.GetEnumerator()) {
    if ((Get-ApkLibraryHash $AppApk $entry.Key) -eq (Get-FileHash -LiteralPath $entry.Value -Algorithm SHA256).Hash.ToLowerInvariant()) {
        throw "Supply an actually modified library for $($entry.Key)."
    }
}
Copy-Item -LiteralPath $AppApk -Destination $repacked -Force
$zip = [IO.Compression.ZipFile]::Open($repacked, [IO.Compression.ZipArchiveMode]::Update)
try {
    foreach ($entry in $entries.GetEnumerator()) {
        $zip.GetEntry($entry.Key).Delete()
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, [IO.Path]::GetFullPath($entry.Value), $entry.Key, [IO.Compression.CompressionLevel]::NoCompression) | Out-Null
    }
} finally { $zip.Dispose() }
Invoke-Checked $align @('-P', '16', '-f', '4', $repacked, $aligned) | Out-Host
if (-not (Test-Path -LiteralPath $key)) {
    Invoke-Checked $keytool @('-genkeypair', '-keystore', $key, '-storetype', 'PKCS12',
        '-storepass', 'changeit', '-keypass', 'changeit', '-alias', 'replacement',
        '-dname', 'CN=Evanna library replacement test', '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650') | Out-Host
}
foreach ($pair in @(@($aligned, $signed), @($TestApk, $signedTest))) {
    Invoke-Checked $sign @('sign', '--ks', $key, '--ks-pass', 'pass:changeit', '--key-pass', 'pass:changeit',
        '--ks-key-alias', 'replacement', '--out', $pair[1], $pair[0]) | Out-Host
    Invoke-Checked $sign @('verify', '--verbose', $pair[1]) | Out-Host
}
Invoke-Checked $align @('-c', '-P', '16', '4', $signed) | Out-Host
foreach ($entry in $entries.GetEnumerator()) {
    $expected = (Get-FileHash -LiteralPath $entry.Value -Algorithm SHA256).Hash.ToLowerInvariant()
    if ((Get-ApkLibraryHash $signed $entry.Key) -ne $expected) { throw "Repacked library hash mismatch: $($entry.Key)" }
}
Invoke-Checked $adb @('-s', $DeviceSerial, 'install', '--no-incremental', '-r', $signed) | Out-Host
Invoke-Checked $adb @('-s', $DeviceSerial, 'install', '--no-incremental', '-r', $signedTest) | Out-Host
$result = Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w',
    '-e', 'probe', 'native-library-replacement', "$package.test/com.evanna.player.playback.UsbAdapterProbe")
$result | Set-Content -LiteralPath $log
$result | Out-Host
if (($result -join [Environment]::NewLine) -notmatch 'INSTRUMENTATION_CODE: -1' -or
    ($result -join [Environment]::NewLine) -notmatch '17 lossless files') {
    throw "Native replacement instrumentation did not pass. See $log"
}
foreach ($entry in $entries.GetEnumerator()) {
    $hash = (Get-FileHash -LiteralPath $entry.Value -Algorithm SHA256).Hash
    "$($entry.Key) SHA256 $hash" | Add-Content -LiteralPath $log
}
"Modified FFmpeg and libusb loaded in the isolated, re-signed app; lossless decoding passed. Log: $log"
