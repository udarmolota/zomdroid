#!/usr/bin/env bash
#
# Builds an empty arm64 libBink2x64.so stub and injects it into the bundled
# libs.tar.xz, so PZ's System.loadLibrary("Bink2x64") stops spamming the log.
#
# Run from git-bash:  bash tools/bink-stub/build_and_pack.sh
#
set -euo pipefail

# --- config (adjust if your paths differ) ---
NDK="/c/Users/user/AppData/Local/Android/Sdk/ndk/27.3.13750724"
REPO="/c/Users/user/Desktop/PZ/zomdroid_v1.2.3"
TARBALL="$REPO/app/src/main/assets/bundles/libs.tar.xz"

HERE="$(cd "$(dirname "$0")" && pwd)"
CLANG="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"

# --- 1) compile the empty stub for arm64 / minSdk 30 ---
echo ">> building libBink2x64.so"
"$CLANG" --target=aarch64-linux-android30 -shared -fPIC -O2 \
    -o "$HERE/libBink2x64.so" "$HERE/bink_stub.c"
ls -l "$HERE/libBink2x64.so"

# --- 2) extract the current bundle ---
WORK="$HERE/_work"
rm -rf "$WORK"; mkdir -p "$WORK"
echo ">> extracting $TARBALL"
tar -xJf "$TARBALL" -C "$WORK"

# --- 3) drop the stub next to the other arm64 libs ---
#     (java.library.path includes .../dependencies/libs/android-arm64-v8a)
cp "$HERE/libBink2x64.so" "$WORK/android-arm64-v8a/libBink2x64.so"

# --- 4) back up the original and repack (relative paths preserve the layout) ---
echo ">> repacking (backup -> $TARBALL.bak)"
cp "$TARBALL" "$TARBALL.bak"
# Use max xz compression (-9e) so the bundle does not bloat the APK; GNU tar -J
# would only use the default level and inflate the archive by several MB.
( cd "$WORK" && tar -cf - android-arm64-v8a linux-x86_64 | xz -9e -T0 -c ) > "$TARBALL"

# --- 5) verify & clean up ---
echo ">> done. bink entry now in the bundle:"
tar -tJf "$TARBALL" | grep -i bink || echo "!! stub NOT found — check the repack"
rm -rf "$WORK"
