param(
    [Parameter(Mandatory=$true)][string]$Ndk,
    [Parameter(Mandatory=$true)][string]$Adb,
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$ProbeDirectory
)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path $PSScriptRoot -Parent
$outPath = Join-Path $repoPath 'build/macho-integration'
$llvmPath = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin'
$nativePath = Join-Path $repoPath 'app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a'
New-Item -ItemType Directory -Force $outPath | Out-Null
$objects = @()
foreach ($source in @('app/src/main/cpp/macho_loader.c','app/src/main/cpp/macho_bridge.c','app/src/main/cpp/macho_unwind.c','app/src/main/cpp/macho_jnienv.c','app/src/main/cpp/macho_pathfind.c','tools/macho-integration-test.c')) {
    $object = Join-Path $outPath ([IO.Path]::GetFileNameWithoutExtension($source) + '.o')
    & "$llvmPath/aarch64-linux-android29-clang.cmd" -std=c11 -O1 -g "-I$repoPath/app/src/main/cpp" "-I$repoPath/app/src/main/cpp/box64/src/include" -c "$repoPath/$source" -o $object
    if ($LASTEXITCODE -ne 0) { throw "Compile failed: $source" }
    $objects += $object
}
& "$llvmPath/aarch64-linux-android29-clang++.cmd" -std=c++17 -O1 -g "$repoPath/app/src/main/cpp/macho_string.cpp" @objects -ldl -llog -lm -o "$outPath/integration-test"
if ($LASTEXITCODE -ne 0) { throw 'Link failed' }
& $Adb -s $Serial shell mkdir -p /data/local/tmp/zomdroid-macho-integration
if ($LASTEXITCODE -ne 0) { throw 'Cannot create test directory' }
& $Adb -s $Serial push "$outPath/integration-test" "$nativePath/libzomdroid_macho_eh.so" "$nativePath/libc++_shared.so" "$ProbeDirectory/libprobe.dylib" "$ProbeDirectory/libprobe2.dylib" "$repoPath/build/analysis_macos_dylibs/libLighting.dylib" "$repoPath/build/analysis_macos_dylibs/libPZPopMan.dylib" "$repoPath/build/analysis_macos_dylibs/libPZPathFind.dylib" /data/local/tmp/zomdroid-macho-integration/
if ($LASTEXITCODE -ne 0) { throw 'Push failed' }
& $Adb -s $Serial shell 'chmod 700 /data/local/tmp/zomdroid-macho-integration/integration-test && cd /data/local/tmp/zomdroid-macho-integration && LD_LIBRARY_PATH=. ./integration-test libprobe.dylib libprobe2.dylib libLighting.dylib libPZPopMan.dylib libPZPathFind.dylib'
if ($LASTEXITCODE -ne 0) { throw 'Integration tests failed' }
