param(
    [Parameter(Mandatory)][string]$DeviceSerial,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [switch]$TransportSilence,
    [switch]$AllModes,
    [switch]$Disconnect,
    [ValidateSet('Usb', 'UsbMusic', 'VolumeSettings', 'VolumeSettingsRestore', 'VolumeKeys', 'VolumeKeysUsb', 'SessionMetadata', 'DacProfiles', 'Playlists', 'Favorites', 'History', 'RecentlyAdded', 'LibrarySort', 'LibraryPaging', 'ArtistLibrary', 'GenreComposer', 'FolderManagement', 'Loudness', 'LoudnessStore', 'CrossfeedStore', 'Android', 'PlayerUi', 'PlayerFlow', 'Artwork', 'ArtworkLibrary', 'Lyrics', 'Tags', 'Cue', 'LibraryMigration', 'AlbumLibrary', 'AlbumUi', 'LibraryContext', 'ContextUi', 'LibraryState', 'Gapless', 'GaplessAndroid', 'AudioEndurance', 'AudioRouteLoss', 'Modes', 'Queue', 'QueueLibrary', 'Shuffle', 'ShuffleStore', 'ShuffleSeed', 'ShuffleCheck', 'ShuffleClear', 'RestoreStore', 'RestoreSeed', 'RestoreCheck', 'RestoreMissing', 'RestoreResume', 'RestoreEmpty', 'RestoreDirectSeed', 'RestoreDirectCheck', 'Library', 'LibraryBrowse', 'LibraryBrowseUi', 'LibraryGrant', 'LibraryRevoke', 'Session', 'SessionUsb', 'Diagnostics', 'DiagnosticsCrash', 'DiagnosticsHistory', 'Decoder', 'FileGrant', 'Picker')][string]$Probe = 'Usb'
)

$ErrorActionPreference = 'Stop'
if ($AllModes -and -not $TransportSilence) { throw '-AllModes requires -TransportSilence.' }
if ($Disconnect -and -not $TransportSilence) { throw '-Disconnect requires -TransportSilence.' }
if ($Disconnect -and $AllModes) { throw '-Disconnect cannot be combined with -AllModes.' }
if ($Probe -ne 'Usb' -and ($TransportSilence -or $AllModes -or $Disconnect)) { throw 'Session/diagnostics probes cannot use USB transport switches.' }

function Assert-UsbProbeResult([string[]]$Lines, [int]$ExitCode) {
    $result = $Lines -join "`n"
    $codes = [regex]::Matches($result, '(?m)^INSTRUMENTATION_CODE:\s*(-?\d+)\s*$')
    if ($ExitCode -ne 0 -or $codes.Count -ne 1 -or $codes[0].Groups[1].Value -ne '-1' -or
        $result -match '(?m)^INSTRUMENTATION_RESULT:\s*failure=') {
        throw 'USB adapter probe failed. ADB exit zero alone does not indicate instrumentation success.'
    }
}

$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $AndroidSdk) {
    $sdkLine = Get-Content -LiteralPath (Join-Path $projectRoot 'local.properties') |
        Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if (-not $sdkLine) { throw 'Set ANDROID_HOME or pass -AndroidSdk.' }
    $AndroidSdk = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
}
$adb = Join-Path $AndroidSdk 'platform-tools/adb.exe'
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) { throw "Missing tool: $adb" }
$probeName = if ($Probe -ne 'Usb') { $Probe } elseif ($TransportSilence) { 'UsbSilentTransportProbe' } else { 'UsbAdapterProbe' }
$logDirectory = if ($Probe -eq 'Decoder') { '.tools/decoder-probe' }
    elseif ($Probe -like 'Diagnostics*') { ".tools/diagnostics/$($Probe.ToLowerInvariant())" }
    elseif ($Probe -ne 'Usb') { ".tools/playback-$($Probe.ToLowerInvariant())" }
    elseif ($Disconnect) { '.tools/usb-silent-disconnect' }
    elseif ($AllModes) { '.tools/usb-silent-matrix' }
    elseif ($TransportSilence) { '.tools/usb-silent-transport' } else { '.tools/usb-adapter-probe' }
$outputDir = Join-Path $projectRoot $logDirectory
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$arguments = @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w')
if ($TransportSilence) {
    $mode = if ($Disconnect) { 'silence-disconnect' } elseif ($AllModes) { 'silence-matrix' } else { 'silence' }
    $arguments += @('-e', 'probe', $mode)
}
if ($Probe -ne 'Usb') {
    $mode = switch ($Probe) {
        'UsbMusic' { 'usb-music' }
        'SessionMetadata' { 'session-metadata' }
        'DacProfiles' { 'dac-profiles' }
        'Playlists' { 'playlists' }
        'Favorites' { 'favorites' }
        'History' { 'history' }
        'RecentlyAdded' { 'recently-added' }
        'LibrarySort' { 'library-sort' }
        'LibraryPaging' { 'library-paging' }
        'ArtistLibrary' { 'artist-library' }
        'GenreComposer' { 'genre-composer' }
        'FolderManagement' { 'folder-management' }
        'Loudness' { 'loudness' }
        'LoudnessStore' { 'loudness-store' }
        'CrossfeedStore' { 'crossfeed-store' }
        'VolumeSettings' { 'volume-settings' }
        'VolumeSettingsRestore' { 'volume-settings-restore' }
        'VolumeKeys' { 'volume-keys' }
        'VolumeKeysUsb' { 'volume-keys-usb' }
        'Tags' { 'tags' }
        'LibraryMigration' { 'library-migration' }
        'AlbumLibrary' { 'album-library' }
        'AlbumUi' { 'album-ui' }
        'LibraryContext' { 'library-context' }
        'ContextUi' { 'context-ui' }
        'LibraryState' { 'library-state' }
        'GaplessAndroid' { 'gapless-android' }
        'AudioEndurance' { 'audio-endurance' }
        'AudioRouteLoss' { 'audio-route-loss' }
        'SessionUsb' { 'session-usb' }
        'LibraryGrant' { 'library-grant' }
        'LibraryBrowse' { 'library-browse' }
        'LibraryBrowseUi' { 'library-browse-ui' }
        'ArtworkLibrary' { 'artwork-library' }
        'PlayerUi' { 'player-ui' }
        'PlayerFlow' { 'player-flow' }
        'QueueLibrary' { 'queue-library' }
        'ShuffleStore' { 'shuffle-store' }
        'ShuffleSeed' { 'shuffle-seed' }
        'ShuffleCheck' { 'shuffle-check' }
        'ShuffleClear' { 'shuffle-clear' }
        'RestoreStore' { 'restore-store' }
        'RestoreSeed' { 'restore-seed' }
        'RestoreCheck' { 'restore-check' }
        'RestoreMissing' { 'restore-missing' }
        'RestoreResume' { 'restore-resume' }
        'RestoreEmpty' { 'restore-empty' }
        'RestoreDirectSeed' { 'restore-direct-seed' }
        'RestoreDirectCheck' { 'restore-direct-check' }
        'LibraryRevoke' { 'library-revoke' }
        'DiagnosticsCrash' { 'diagnostics-crash' }
        'DiagnosticsHistory' { 'diagnostics-history' }
        default { $Probe.ToLowerInvariant() }
    }
    $arguments += @('-e', 'probe', $mode)
}
$arguments += 'com.evanna.player.test/com.evanna.player.playback.UsbAdapterProbe'
$reply = [System.Collections.Generic.List[string]]::new()
# Show readiness immediately; keep the complete output for final result validation.
& $adb @arguments 2>&1 | Tee-Object -FilePath (Join-Path $outputDir 'results.log') | ForEach-Object {
    $reply.Add([string]$_)
    Write-Host $_
}
$code = $LASTEXITCODE
Assert-UsbProbeResult $reply.ToArray() $code
Write-Output "$probeName passed. See the report for synthetic versus physical output evidence."
