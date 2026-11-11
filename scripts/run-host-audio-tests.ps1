param()
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$vswhere=Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
if(-not (Test-Path -LiteralPath $vswhere)){throw 'Visual Studio C++ Build Tools are required for host audio tests.'}
$install=(& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath)
if($LASTEXITCODE -ne 0 -or -not $install){throw 'Visual Studio C++ compiler was not found.'}
$setup=Join-Path $install 'VC/Auxiliary/Build/vcvars64.bat'
$output=Join-Path $projectRoot '.tools/host-audio-tests'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$vendor=Join-Path $projectRoot 'third_party/libebur128'
$native=Join-Path $projectRoot 'app/src/main/cpp'
$test=Join-Path $projectRoot 'app/src/test/cpp/loudness_tests.cpp'
$object=Join-Path $output 'ebur128.obj';$binary=Join-Path $output 'loudness-tests.exe'
$uacTest=Join-Path $projectRoot 'usbaudio/src/test/cpp/uac_tests.cpp'
$descriptors=Join-Path $projectRoot 'usbaudio/src/main/cpp/uac/UacDescriptors.cpp'
$uacBinary=Join-Path $output 'uac-tests.exe'
foreach($path in @($setup,$output,$vendor,$native,$test,$uacTest,$descriptors)) {if($path -match '[%`"!\r\n]'){throw 'The host compiler paths contain unsupported command characters.'}}
$commands=@"
@echo off
call "$setup" >nul
if errorlevel 1 exit /b 1
cl /nologo /TC /std:c11 /O2 /D_USE_MATH_DEFINES /I"$vendor/queue" /c "$vendor/ebur128.c" /Fo"$object"
if errorlevel 1 exit /b 1
cl /nologo /std:c++17 /EHsc /W4 /WX /O2 /I"$native" /I"$vendor" "$test" "$object" /Fe"$binary" /Fo"$output/loudness-tests.obj"
if errorlevel 1 exit /b 1
"$binary"
if errorlevel 1 exit /b 1
pushd "$output"
cl /nologo /std:c++17 /EHsc /W4 /D_USE_MATH_DEFINES /O2 "$uacTest" "$descriptors" /Fe"$uacBinary"
if errorlevel 1 exit /b 1
popd
"$uacBinary"
exit /b %errorlevel%
"@
$commandFile=Join-Path $output 'run.cmd'
Set-Content -LiteralPath $commandFile -Value $commands -Encoding ascii
& cmd.exe /d /c $commandFile
if($LASTEXITCODE -ne 0){throw 'Host audio tests failed. No device was accessed.'}
