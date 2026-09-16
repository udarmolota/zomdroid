# Handoff: Codex work on native macOS PopMan

Date: 2026-09-12. This continues `tools/macos-libs-blockers.md` and
`tools/macos-native-libs-brief.md`.

## Goal and current verdict

The goal was to continue past the blocker where the macOS arm64
`libPZPopMan.dylib` crashed in its static initializer because Apple libc++ and Android/NDK libc++
use different `std::string` layouts.

That first blocker is now passed. The real PopMan dylib loads and returns from its constructors in
a disposable process on Inna's phone. This is **not yet safe for the game**: production still
rejects PopMan because compact-unwind/exception handling and stream/save compatibility have not
been proved. Box64 remains the gameplay route. Do not remove the production guard yet.

## Source changes

### `app/src/main/cpp/macho_string.h` and `macho_string.cpp`

New explicit shims for Apple's arm64 alternate-layout `std::string`. The representation used is
24 bytes:

- short: 22 inline chars, NUL, then 7-bit size / high-bit long flag;
- long: data pointer, size, allocation count with bit 63 set.

Implemented the exact layout-sensitive imports found in PopMan:

- `basic_string::append(const char*)`
- `append(const char*, size_t)`
- `assign(const char*)`
- `insert(size_t, const char*)`
- `resize(size_t, char)`
- `push_back(char)`
- copy assignment
- D1/D2 destructor
- `operator+(const char*, const string&)`
- `to_string(int)`
- `random_device` constructor, destructor and `operator()`
- `runtime_error(const string&)`

Mutations allocate the replacement before releasing the old buffer. This deliberately handles
overlapping input such as `s.append(s.data())` and retains the old value on allocation/length
exceptions. It is correctness-first and does not reuse capacity yet.

`random_device` accepts `/dev/urandom`, stores no Android object in Apple's potentially tiny
object, and generates with bionic `arc4random_buf`. `runtime_error(string)` converts at the ABI
boundary and invokes Android's `runtime_error(const char*)` constructor.

Important ABI assumption: a 24-byte return value on AArch64 uses the indirect-result register x8,
so the C++ functions returning `macho_string` match Apple arm64 here. The code is explicitly
64-bit little-endian only.

### `app/src/main/cpp/macho_loader.c`

- Calls `macho_string_import()` before generic `std::__1` -> `std::__ndk1` rewriting.
- Corrected the misleading comment: namespace rewriting is symbol rewriting, not ABI conversion.
- The existing broad layout-conflict guard remains active in production.
- Added a compile-only `MACHO_POPMAN_CONSTRUCTOR_HARNESS` bypass for the exact basename
  `libPZPopMan.dylib`. It is only for a disposable test executable. **Never define it in app
  CMake.**

### `app/src/main/cpp/CMakeLists.txt`

Adds `macho_string.cpp` to `zomdroidlinker` and requests C++17. It does not define the unsafe
harness macro.

## Test tools added

### `tools/macho-string-test.cpp`

Tests independent raw Apple short/long byte images, short/long boundary (22/23), assignment,
copy/self-copy, grow/shrink, embedded NUL, aliased append/insert/assign, integer extremes,
out-of-range and overflow exception guarantees, random-device object canaries, and
`runtime_error` construction. Includes 1000 differential mutations against Android
`std::string`.

### `tools/test-macho-strings.ps1`

Cross-compiles that harness for Android arm64 with NDK29, UBSan and static test C++ runtime, then
optionally pushes/runs it on an explicitly named device.

### `tools/macho-popman-constructor-test.c` and
`tools/test-macho-popman-constructors.ps1`

Build a standalone loader with `MACHO_POPMAN_CONSTRUCTOR_HARNESS`, copy the dylib and an isolated
NDK `libc++_shared.so` to `/data/local/tmp/zomdroid-popman-probe`, load the real dylib, execute its
constructors and resolve `n_saveCell`. No JVM, game, world or save is touched. The test also probes
the unwind symbols visible through `dlsym`.

## Evidence collected

Tested dylib: `libPZPopMan.dylib` extracted under `build/` from Inna's
`C:\Users\user\Desktop\PZ\Macos imports.zip`.

- size: 517232 bytes
- SHA-256: `4d39a6964ea1ab65a399620512836f3ee0b5e525e6ab846f3e9a0073b3a2a216`
- device: `RFGYB08X13B`
- NDK: `29.0.14206865`

Device results:

```text
PASS: Apple string fixtures, 1000 differential mutations, aliases, boundaries, exceptions, random_device
[macho] UNSAFE CONSTRUCTOR HARNESS: bypassing PopMan layout guard
[macho] libPZPopMan.dylib loaded natively: arm64 slice, 59 exports, 156 binds, 2400 KB
PASS: PopMan constructors returned and n_saveCell resolved; no world or save operation attempted
UNWIND PROBE __register_frame: libc++=<non-null> default=<non-null>
UNWIND PROBE __deregister_frame: libc++=<non-null> default=<non-null>
UNWIND PROBE _Unwind_RaiseException: libc++=<non-null> default=<non-null>
UNWIND PROBE __gxx_personality_v0: libc++=<non-null> default=<non-null>
```

`macho_loader.c` also passed an NDK clang C syntax check. No APK was built, and nothing was
committed or pushed.

## What remains and where to continue

### 1. Compact unwind and C++ exceptions — next blocker

`llvm-objdump --macho --arch=arm64 --unwind-info` reports for this PopMan:

- version 1;
- 17 common encodings;
- one personality pointer at image offset `0x28080`;
- two top-level index entries (the second is the sentinel);
- real LSDA descriptors;
- observed encodings include arm64 FRAME (`0x04...`), FRAMELESS (`0x02...`) and
  FRAME + personality + LSDA (`0x54...`).

Do not implement only stack CFI. A forced throw/catch requires the personality and LSDA to remain
associated with each generated frame. `__register_frame`, `__deregister_frame`,
`_Unwind_RaiseException`, and `__gxx_personality_v0` are all discoverable at runtime on the tested
phone, even though `llvm-nm -D libc++_shared.so` shows only the personality directly; the other
symbols resolve through its loaded dependencies.

Possible approaches remain those in `macos-libs-blockers.md`: translate compact unwind into
registered DWARF CFI, including personality/LSDA augmentation, or use a compact-unwind-capable
LLVM libunwind. Whichever route is chosen must reject unknown encodings and be tested with a
forced throw/catch and destructor cleanup through an actual mapped Mach-O frame.

### 2. Narrow the layout guard only after import audit

The current guard rejects all string and iostream-related imports even though the explicit string
imports now resolve to shims. Keep it broad during unwind work. After exceptions work, audit the
remaining `basic_ostream`, `basic_istream`, `basic_streambuf`, `basic_ios`, `basic_filebuf`,
`ifstream` and `ofstream` crossings. A symbol resolving after namespace rewrite does not prove
object-layout compatibility.

### 3. Save compatibility

Resolving `n_saveCell` is not proof that it writes a valid world. Before game enablement:

1. exercise the PopMan save path against scratch storage;
2. force its I/O failure/exception paths;
3. compare output and reload behavior with Linux PopMan through box64;
4. perform a disposable in-game save/reload test and verify zombie population/state.

### 4. Production integration

Only after the above, narrow the production guard for PopMan and permit it behind the existing
macOS-library per-instance switch. Preserve explicit fallback to Linux PopMan through box64 when
loading, unwind registration, initialization, or validation fails. Do not silently fall back to
the stale TIS Android PopMan missing `n_saveCell`.

## Related correction discovered during review

The statement in `macos-libs-blockers.md` that Bullet, PathFind and Clipper all currently run
natively is too broad:

- audited Android Bullet is enabled only in debug by `prepareBulletDiagnostic`; release still
  disables it and uses box64;
- B42.12+ currently forces `Pathfind.UseNativeCode=false`, so Java pathfinding is selected even
  though the Android library has a complete export list;
- Clipper can be native.

These are separate from the PopMan work but should not be lost when choosing priorities.

## Dirty-tree warning

This workspace contains simultaneous work from Inna/Fable/Claude, including the Mach-O loader,
Steam library downloads, server work and other patches. Do not reset, revert or broadly reformat
the tree. The files specifically created/edited by this PopMan checkpoint are listed above.
