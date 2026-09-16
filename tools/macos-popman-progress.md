# PopMan compatibility implementation — 2026-09-12

## Completed checkpoint, not game-ready

`macho_string.cpp` implements the exact imported Apple alternate-layout string operations,
random_device constructor/destructor/generator, and runtime_error(string) conversion. The
resolver checks these explicit bindings before namespace rewriting. The production layout
guard is deliberately retained: successful string binding does not establish exception or
stream compatibility. No gameplay fallback or native-library selection was changed.

The initial mutation implementation constructs replacement storage before releasing old storage,
so overlapping inputs and allocation/length exceptions cannot corrupt the original string.
Capacity reuse is not optimized yet. Allocation uses Android operator new/delete, matching the
runtime currently used for the other imported allocator functions. Only 64-bit little-endian
alternate-layout strings are supported. By-value 24-byte results use ARM64 indirect returns.

## Evidence

Analyzed `libPZPopMan.dylib` from the user's `Macos imports.zip`, extracted only under build/.
On device RFGYB08X13B with NDK29's shared libc++:

```
[macho] libPZPopMan.dylib loaded natively: arm64 slice, 59 exports, 156 binds, 2400 KB
PASS: PopMan constructors returned and n_saveCell resolved; no world or save operation attempted
```

This used a separate executable, built with `MACHO_POPMAN_CONSTRUCTOR_HARNESS`. That compile-only
switch bypasses the layout guard ONLY for a file named libPZPopMan.dylib and is NOT set in the
app CMake target. Do not add it to the app. No game process or save was opened. Scripts:

- `tools/test-macho-strings.ps1`: independent raw Apple short/long fixtures, 1000 differential
  mutations against Android std::string, 22/23-byte boundary, overlapping append/insert/assign,
  embedded NUL, copy/self-copy, shrink/grow, INT_MIN/MAX, invalid-position/overflow exceptions,
  random_device object canaries, runtime_error construction. Passed on phone with UBSan.
- `tools/test-macho-popman-constructors.ps1`: real loader and dylib constructor probe, using
  a dedicated shared libc++ under `/data/local/tmp/zomdroid-popman-probe`.

C loader syntax check and standalone ARM64 linking passed. APK not built. No commits/pushes.

## Next mandatory work

1. Translate/register PopMan compact unwind plus personality/LSDA data, not just stack CFI.
   Actual table: version 1, 17 common encodings, one personality (image offset 0x28080), two
   top-level index entries including sentinel. Encodings seen include FRAME (0x04...),
   FRAMELESS (0x02...), and FRAME with LSDA/personality (0x54...). Inspect every row before
   declaring supported; refuse unknown encodings.
2. Prove forced throw/catch and cleanup across actual mapped Mach-O frames. Current exception
   tests exercise the Android shim only, NOT unwinding through PopMan.
3. Prove stream compatibility and a real save/reload round trip in scratch storage, including
   failure paths and zombie state. Resolving n_saveCell is NOT proof of a valid save.
4. Only then narrow the production guard and enable gameplay, retaining the box64 alternative.

Runtime discovery nuance: NDK libc++_shared's own dynamic symbol list does not list
__register_frame/_Unwind_RaiseException, but dlsym(handle, ...) on the phone finds these through
its dependencies (also found with RTLD_DEFAULT). __gxx_personality_v0 resolves in libc++ itself.
Do not conclude the registration API is unavailable just from nm on libc++; verify that the
registered frames are visible to the actual throwing runtime in the forced-throw test.
