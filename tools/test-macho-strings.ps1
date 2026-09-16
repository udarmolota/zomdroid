param(
    [Parameter(Mandatory=$true)][string]$Ndk,
    [string]$Adb,
    [string]$Serial
)
$ErrorActionPreference = 'Stop'
$repoPath = Split-Path $PSScriptRoot -Parent
$outputPath = Join-Path $repoPath 'build/macho-popman'
New-Item -ItemType Directory -Force $outputPath | Out-Null
$compilerPath = Join-Path $Ndk 'toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android29-clang++.cmd'
& $compilerPath -std=c++17 -Wall -Wextra -Werror -O1 -g -static-libstdc++ `
    -fsanitize=undefined -fno-sanitize-recover=all -static-libsan `
    "-I$repoPath/app/src/main/cpp" "$repoPath/app/src/main/cpp/macho_string.cpp" `
    "$repoPath/tools/macho-string-test.cpp" -o "$outputPath/string-test"
if ($LASTEXITCODE -ne 0) { throw 'String harness compilation failed' }
if ($Adb) {
    if (!$Serial) { throw 'Specify the test device serial explicitly' }
    & $Adb -s $Serial push "$outputPath/string-test" /data/local/tmp/zomdroid-macho-string-test
    if ($LASTEXITCODE -ne 0) { throw 'adb push failed' }
    & $Adb -s $Serial shell 'chmod 700 /data/local/tmp/zomdroid-macho-string-test && /data/local/tmp/zomdroid-macho-string-test'
    if ($LASTEXITCODE -ne 0) { throw 'Device harness failed' }
}
