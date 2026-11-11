param(
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$DeviceSerial
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $AndroidSdk) {
    $properties = Get-Content -LiteralPath (Join-Path $projectRoot 'local.properties')
    $sdkLine = $properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if (-not $sdkLine) { throw 'Set ANDROID_HOME or pass -AndroidSdk.' }
    $AndroidSdk = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
}
$compiler = Join-Path $AndroidSdk 'ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android28-clang++.cmd'
$adb = Join-Path $AndroidSdk 'platform-tools/adb.exe'
foreach ($tool in @($compiler, $adb)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Missing tool: $tool" }
}
$outputDir = Join-Path $projectRoot '.tools/native-tests'
New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools/android-user'
$log = Join-Path $outputDir 'results.log'
Set-Content -LiteralPath $log -Value 'Evanna native tests: ARM64/API 28, test binaries use UBSan trap mode; no USB device is opened.'

function Invoke-Checked([string]$Tool, [string[]]$ToolArgs) {
    & $Tool @ToolArgs 2>&1 | Tee-Object -FilePath $log -Append | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "$(Split-Path -Leaf $Tool) failed with exit code $LASTEXITCODE. See $log" }
}

# Refuse an ambiguous target instead of deploying a test to an arbitrary phone.
if (-not $DeviceSerial) {
    $inventory = & $adb devices
    if ($LASTEXITCODE -ne 0) { throw 'adb devices failed.' }
    $connected = @($inventory | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($connected.Count -ne 1) { throw 'Connect exactly one authorized device, or pass -DeviceSerial.' }
    $DeviceSerial = $connected[0]
}
$abi = & $adb -s $DeviceSerial shell getprop ro.product.cpu.abi
if ($LASTEXITCODE -ne 0 -or ($abi -join '').Trim() -ne 'arm64-v8a') { throw 'The test target must be an authorized ARM64 device.' }

$binary = Join-Path $outputDir 'uac-tests'
Invoke-Checked $compiler @(
    '-std=c++17', '-Wall', '-Wextra', '-Werror', '-O1', '-g',
    '-fsanitize=undefined', '-fsanitize-trap=undefined', '-static-libstdc++',
    (Join-Path $projectRoot 'usbaudio/src/test/cpp/uac_tests.cpp'),
    (Join-Path $projectRoot 'usbaudio/src/main/cpp/uac/UacDescriptors.cpp'),
    '-o', $binary
)
$remoteDir = '/data/local/tmp/evanna-native-tests'
Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', 'mkdir', '-p', $remoteDir)
$remote = "$remoteDir/uac-tests"
Invoke-Checked $adb @('-s', $DeviceSerial, 'push', $binary, $remote)
Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', 'chmod', '700', $remote)
Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', $remote)

$libraries = Join-Path $projectRoot 'usbaudio/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a'
foreach ($name in @('libusbaudio.so', 'libusb-1.0.so')) {
    $library = Join-Path $libraries $name
    if (-not (Test-Path -LiteralPath $library)) { throw 'Build :app:assembleDebug before running the JNI tests.' }
    Invoke-Checked $adb @('-s', $DeviceSerial, 'push', $library, "$remoteDir/$name")
}
$jniBinary = Join-Path $outputDir 'jni-write-tests'
Invoke-Checked $compiler @(
    '-std=c++17', '-Wall', '-Wextra', '-Werror', '-O1', '-g',
    '-fsanitize=undefined', '-fsanitize-trap=undefined', '-static-libstdc++',
    (Join-Path $projectRoot 'usbaudio/src/test/cpp/jni_write_tests.cpp'),
    "-L$libraries", '-lusbaudio', '-lusb-1.0', '-Wl,-rpath,$ORIGIN', '-o', $jniBinary
)
Invoke-Checked $adb @('-s', $DeviceSerial, 'push', $jniBinary, "$remoteDir/jni-write-tests")
Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', 'chmod', '700', "$remoteDir/jni-write-tests")
Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', "$remoteDir/jni-write-tests")

# Compile the real streamer/JNI with a test-only libusb transport. Its callbacks
# can be withheld past shutdown's deadline without opening the attached DAC.
$archives = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'usbaudio/.cxx/Debug') -Recurse -Filter 'libsamplerate.a' |
    Where-Object { $_.Directory.Name -eq 'arm64-v8a' })
if ($archives.Count -ne 1) { throw 'Expected one ARM64 debug libsamplerate archive; build :app:assembleDebug and check for stale native build directories.' }
$lifecycleBinary = Join-Path $outputDir 'streamer-lifecycle-tests'
Invoke-Checked $compiler @(
    '-std=c++17', '-Wall', '-Wextra', '-Werror', '-O1', '-g',
    '-fsanitize=undefined', '-fsanitize-trap=undefined', '-static-libstdc++',
    "-I$(Join-Path $projectRoot 'third_party/libusb/libusb')",
    "-I$(Join-Path $projectRoot 'third_party/libsamplerate/include')",
    (Join-Path $projectRoot 'usbaudio/src/test/cpp/streamer_lifecycle_tests.cpp'),
    (Join-Path $projectRoot 'usbaudio/src/main/cpp/UsbStreamer.cpp'),
    (Join-Path $projectRoot 'usbaudio/src/main/cpp/jni_bridge.cpp'),
    (Join-Path $projectRoot 'usbaudio/src/main/cpp/uac/UacDescriptors.cpp'),
    (Join-Path $projectRoot 'usbaudio/src/main/cpp/Log.cpp'),
    $archives[0].FullName, '-llog', '-o', $lifecycleBinary
)
Invoke-Checked $adb @('-s', $DeviceSerial, 'push', $lifecycleBinary, "$remoteDir/streamer-lifecycle-tests")
Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', 'chmod', '700', "$remoteDir/streamer-lifecycle-tests")
Invoke-Checked $adb @('-s', $DeviceSerial, 'shell', "$remoteDir/streamer-lifecycle-tests")
Write-Output "Native tests passed. Log: $log"
