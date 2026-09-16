param(
    [Parameter(Mandatory=$true)][string]$Ndk,
    [Parameter(Mandatory=$true)][string]$Dylib,
    [Parameter(Mandatory=$true)][string]$Adb,
    [Parameter(Mandatory=$true)][string]$Serial
)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path $PSScriptRoot -Parent
$outputPath = Join-Path $repoPath 'build/macho-popman'
$llvmPath = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64'
New-Item -ItemType Directory -Force $outputPath | Out-Null
if ([IO.Path]::GetFileName($Dylib) -ne 'libPZPopMan.dylib') { throw 'Expected libPZPopMan.dylib' }
& "$llvmPath/bin/aarch64-linux-android29-clang.cmd" -std=c11 -O1 -g `
    -DMACHO_POPMAN_CONSTRUCTOR_HARNESS "-I$repoPath/app/src/main/cpp/box64/src/include" `
    -c "$repoPath/app/src/main/cpp/macho_loader.c" -o "$outputPath/loader-test.o"
if ($LASTEXITCODE -ne 0) { throw 'Loader compilation failed' }
& "$llvmPath/bin/aarch64-linux-android29-clang.cmd" -std=c11 -O1 -g `
    "-I$repoPath/app/src/main/cpp" "-I$repoPath/app/src/main/cpp/box64/src/include" `
    -c "$repoPath/tools/macho-popman-constructor-test.c" -o "$outputPath/main-test.o"
if ($LASTEXITCODE -ne 0) { throw 'Harness compilation failed' }
& "$llvmPath/bin/aarch64-linux-android29-clang++.cmd" -std=c++17 -O1 -g `
    "$repoPath/app/src/main/cpp/macho_string.cpp" "$outputPath/loader-test.o" "$outputPath/main-test.o" `
    -ldl -llog -lm -o "$outputPath/constructor-test"
if ($LASTEXITCODE -ne 0) { throw 'Harness linking failed' }
& $Adb -s $Serial shell mkdir -p /data/local/tmp/zomdroid-popman-probe
if ($LASTEXITCODE -ne 0) { throw 'Cannot create probe directory' }
& $Adb -s $Serial push "$outputPath/constructor-test" $Dylib `
    "$llvmPath/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" /data/local/tmp/zomdroid-popman-probe/
if ($LASTEXITCODE -ne 0) { throw 'adb push failed' }
& $Adb -s $Serial shell 'chmod 700 /data/local/tmp/zomdroid-popman-probe/constructor-test && cd /data/local/tmp/zomdroid-popman-probe && LD_LIBRARY_PATH=. ./constructor-test ./libPZPopMan.dylib'
if ($LASTEXITCODE -ne 0) { throw 'Constructor harness failed' }
