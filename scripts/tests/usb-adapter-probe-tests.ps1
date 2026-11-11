$ErrorActionPreference = 'Stop'
$path = Join-Path $PSScriptRoot '../run-usb-adapter-probe.ps1'
$ast = [System.Management.Automation.Language.Parser]::ParseFile($path, [ref]$null, [ref]$null)
$function = $ast.Find({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq 'Assert-UsbProbeResult' }, $true)
if (-not $function) { throw 'Probe result validator not found.' }
# Load only the real result validator; these tests must never invoke ADB.
. ([scriptblock]::Create($function.Extent.Text))
Assert-UsbProbeResult @('INSTRUMENTATION_RESULT: report=passed', 'INSTRUMENTATION_CODE: -1') 0
$cases = @(
    @{ Lines = @('INSTRUMENTATION_RESULT: failure=No DAC', 'INSTRUMENTATION_CODE: 0'); Code = 0 },
    @{ Lines = @('INSTRUMENTATION_CODE: -1'); Code = 1 },
    @{ Lines = @(); Code = 0 },
    @{ Lines = @('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_CODE: 0'); Code = 0 },
    @{ Lines = @('INSTRUMENTATION_RESULT: failure=failed', 'INSTRUMENTATION_CODE: -1'); Code = 0 }
)
foreach ($case in $cases) {
    $rejected = $false
    try { Assert-UsbProbeResult $case.Lines $case.Code } catch { $rejected = $true }
    if (-not $rejected) { throw 'The probe validator accepted a failed or incomplete run.' }
}
$rejected = $false
try { & $path -DeviceSerial 'test-no-device-access' -AllModes }
catch { $rejected = $_.Exception.Message -eq '-AllModes requires -TransportSilence.' }
if (-not $rejected) { throw 'Matrix mode must require explicit digital-silence selection before accessing ADB.' }
$guards = @(
    @{ Args = @{ Disconnect = $true }; Message = '-Disconnect requires -TransportSilence.' },
    @{ Args = @{ TransportSilence = $true; Disconnect = $true; AllModes = $true }; Message = '-Disconnect cannot be combined with -AllModes.' }
    @{ Args = @{ Probe = 'Session'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' },
    @{ Args = @{ Probe = 'Diagnostics'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'Android'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'Gapless'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'GaplessAndroid'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'AudioEndurance'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'AudioRouteLoss'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'Modes'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'Library'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'Artwork'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'ArtworkLibrary'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'Lyrics'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'Tags'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'Cue'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'LibraryMigration'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'AlbumLibrary'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'AlbumUi'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'LibraryContext'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'ContextUi'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'VolumeSettings'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'VolumeSettingsRestore'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'VolumeKeys'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'VolumeKeysUsb'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'SessionMetadata'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'DacProfiles'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'Playlists'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'Favorites'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'History'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'RecentlyAdded'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'LibrarySort'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'LibraryPaging'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'ArtistLibrary'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'GenreComposer'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'FolderManagement'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'Loudness'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'LoudnessStore'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'EqualizerStore'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'CrossfeedStore'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'LibraryState'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'PlayerUi'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'PlayerFlow'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'LibraryBrowse'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'LibraryBrowseUi'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'LibraryGrant'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'LibraryRevoke'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'UsbMusic'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'Queue'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'QueueLibrary'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'RestoreStore'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'RestoreSeed'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'RestoreCheck'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'RestoreMissing'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'RestoreResume'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'RestoreEmpty'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'RestoreDirectSeed'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'RestoreDirectCheck'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'Shuffle'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'ShuffleStore'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'ShuffleSeed'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'ShuffleCheck'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
    @{ Args = @{ Probe = 'ShuffleClear'; TransportSilence = $true }; Message = 'Session/diagnostics probes cannot use USB transport switches.' }
)
foreach ($case in $guards) {
    $rejected = $false
    try { $parameters = $case.Args; & $path -DeviceSerial 'test-no-device-access' @parameters }
    catch { $rejected = $_.Exception.Message -eq $case.Message }
    if (-not $rejected) { throw 'Invalid disconnect switches must be rejected before accessing ADB.' }
}
# The accepted result, the rejected results, the -AllModes guard and the switch guards; counted so the total cannot drift.
"Probe result validator and mode guards: $(2 + $cases.Count + $guards.Count) cases passed; no device accessed."
