# Handoff: macOS PopMan + PathFind — exceptions done, integration left

Date: 2026-09-12. Continues `tools/macos-popman-codex-handoff.md` (Codex, string shims) and
`tools/macos-libs-blockers.md`. Nothing here is committed; no APK was built with these changes.
Approval gates as always: no commit / push / APK build without Inna's explicit OK.

## Goal (Inna's decision)

Run PopMan and PathFind from the macOS arm64 dylibs, like Lighting already does. Clipper stays on
the TIS Android arm64 build (TLV, not needed). Bullet stays on the TIS Android arm64 build (now
re-enabled in code on 42.20+). RakNet / multiplayer libraries stay as they are.

## What is done and proven on the phone (RFGYB08X13B, harness, no JVM)

Harness: `scratchpad/macho_test/macho_test.c` (Claude's session scratchpad, not in the repo; copy
it into `tools/` if you need it). Last run, all green:

```text
PASS: 9 wrapper-JNIEnv checks (thunks forward with the real env; CallIntMethodV /
      CallStaticVoidMethodV rebuilt from an Apple va_list; unknown method -> dropped, 0)
libprobe.dylib  loaded ... exceptions on   -> 6/6 PASS (catch in frame, 3-frame throw with
      destructors, class hierarchy by base ref, rethrow, catch(...), no-throw path)
libprobe2.dylib loaded ... exceptions on   -> 5/5 PASS (throw from callee, throw in handler,
      rethrow via call, callee rethrows to caller, rethrow same frame)
libLighting.dylib   loaded, exceptions on, 18/18 bridges
libPZPopMan.dylib   loaded natively: 59 exports, 156 binds, exceptions on, n_saveCell resolves
libPZPathFind.dylib loaded natively: 818 exports, 163 binds, exceptions on
```

`libprobe*.dylib` are real Mach-O arm64 dylibs with Apple compact unwind, built on Windows with
the NDK itself: `clang++ -target arm64-apple-macos11 -fexceptions -nostdinc++ -nostdinc` then
`ld.lld -flavor darwin -dylib -arch arm64 -platform_version macos 11.0 11.0 -undefined
dynamic_lookup -no_fixup_chains`. Sources: `scratchpad/unwind/probe.cpp`, `probe2.cpp`.

## New / changed source (app/src/main/cpp)

- `macho_eh.c` -> **new library `libzomdroid_macho_eh.so`**: NDK `libc++abi.a` + `libunwind.a`
  linked whole, `--exclude-libs,ALL`, exports only `zomdroid_macho_eh_{symbol,register,
  unregister,version}`. Why: `libc++_shared.so` carries its own libunwind with everything hidden
  (no `_Unwind_*`, no `__register_frame` in its dynsym), so frames registered anywhere else are
  invisible to its `__cxa_throw`. The dylib's `__cxa_*`, `_Unwind_*`, `__gxx_personality_v0`,
  `std::terminate` are bound to this library; typeinfo / `__cxxabiv1` vtables stay with
  libc++_shared. Registration walks our own .eh_frame and calls `__unw_add_dynamic_fde` per FDE.
  **Do not switch to `__unw_add_dynamic_eh_frame_section`**: it decodes each FDE against the last
  CIE parsed (broke every handler after a second CIE) and does not stop at the zero terminator
  (segfault past the buffer). Both reproduced on the phone.
- `macho_unwind.c/.h` -> compact unwind (`__unwind_info`, regular + compressed pages) to DWARF:
  FRAME mode = CFA fp+16, lr CFA-8, fp CFA-16, pairs x19.. then d8.. from CFA-24 down (matches
  LLVM CompactUnwinder_arm64); FRAMELESS = CFA sp+size. One "zPLR" CIE per personality (absptr
  everywhere), each CIE immediately followed by all its FDEs; functions without a personality
  join the first personality's CIE with LSDA 0. DWARF-mode or unknown encodings reject the lib
  if it imports `__cxa_throw`.
- `macho_jnienv.c/.h` + generated `macho_jni_table.h` (234 entries from `openjdk/jni.h`) ->
  wrapper JNIEnv. PopMan/PathFind/Clipper/Bullet use the inline `JNIEnv_::CallXMethod(...)`
  C++ wrappers, which pass an **Apple va_list** (pointer to 8-byte slots) to `CallXMethodV`;
  HotSpot expects the Linux 5-field va_list. 203 plain entries = 4-insn thunks (swap env, jump);
  31 `*V` entries unpack the Apple va_list by the method's Java signature and call `*A`.
  Signature provider: JVMTI `GetMethodName` (installed in `zomdroid_linker_init`).
- `macho_bridge.c` -> `macho_jni_bridge(..., wrap_env)`: when set, the shim parks x1-x7/d0-d7,
  calls `macho_jnienv_wrap(x0)`, restores, continues as before. Encodings verified vs assembler.
- `macho_loader.c/.h` -> EH runtime opened once; EH imports bound to it; `__unwind_info` found
  in `parse_commands`; tables translated + registered after mprotect, before constructors; layout
  guard narrowed to string/random_device imports NOT covered by `macho_string.cpp` (streams are
  allowed: see comment there); `macho_lib_wraps_env()` accessor; load line shows
  `, exceptions on` / `, JNIEnv wrapped`.
- `linker.c` -> signature provider, `macho_jni_bridge(..., macho_lib_wraps_env(jni_macho[i]))`.
- `CMakeLists.txt` -> `macho_unwind.c macho_jnienv.c` added to `zomdroidlinker`; new target
  `zomdroid_macho_eh` (archive paths via `${CMAKE_C_COMPILER} -print-file-name=...`).
  **Not yet verified through Gradle/CMake** — only built by hand with NDK 27 clang.
- Unchanged from before: `macho_string.cpp/.h` (Codex), `NativeLibraryWorkarounds.java` (Bullet).

## What is left, in order

1. **BUG — wrap_env never turns on.** Detection looks for exported `_ZN7JNIEnv_*`, but in the
   dylibs these are **local** symbols (`nm` type `t`: PopMan 5, PathFind 4, Lighting 0). Read
   LC_SYMTAB (nlist_64 + string table) in `macho_loader.c` and set `lib->wrap_env` when any
   symbol name starts with `__ZN7JNIEnv_`. Without this PopMan/PathFind JNI callbacks get
   garbage arguments.
2. **PopMan calls `GetJavaVM` once** (JNIEnv offset 1752). If it later uses `vm->GetEnv` /
   `AttachCurrentThread` on a worker thread, it gets the REAL env and its variadic calls break
   again. Find the call site (disassemble around the `ldr x?, [x?, #0x6d8]`), and if needed make
   the thunk for `GetJavaVM` return a wrapper JavaVM whose GetEnv/Attach* return
   `macho_jnienv_wrap(real)`. PathFind/Lighting do not call it.
3. **PathFind switch** (`patch/PathfindingWorkaround.java`, called from `GameLauncher` line ~67,
   before `ZOMDROID_MACHO_LIBS` is decided at ~273): write `Pathfind.UseNativeCode=true` only when
   the macOS PathFind will be used (switch on + 42.20+ + `MacosLibraries.isReady`), else `false`
   as today. Move the decision so both use the same condition.
4. **Fallback policy in `linker.c` dlopen hook**: if the macOS dylib for PathFind64 or PopMan64 is
   attempted and rejected, go to **box64 (Linux build)**, never to the TIS arm64 build (PopMan's
   lacks `n_saveCell`; PathFind's is the suspected wrong-route build). Currently it falls through
   to arm64 first.
5. **JNI boundary**: an exception escaping a JNI entry still ends in `std::terminate` (same as on
   macOS). The enthusiast's binary has a PopMan-specific `cpp_exception_jni_boundary` fix, so it
   may happen in practice. Option: for PopMan/PathFind generate a guarded shim with its own FDE
   and a small personality in `macho_eh.c` that catches, logs the type, returns 0. Do after a
   first in-game run shows whether it is needed.
6. **Build**: verify the CMake target builds and `libzomdroid_macho_eh.so` + `libc++_shared.so`
   land in the APK (`lib/arm64-v8a/`). Only `compileDebugJavaWithJavac` /
   `processDebugResources` are pre-approved; the APK needs Inna's command.
7. **In-game tests (Inna)**: 42.20 instance, switch on, all dylibs downloaded. Check native.log:
   `[macho] libPZPopMan.dylib loaded natively ... exceptions on, JNIEnv wrapped`, same for
   PathFind. Then: save, quit, reload (zombies present, no loss); place items on a table / use
   objects (routes); `ZOMDROID_JNI_STATS=1` should show PopMan64 gone from box64.

## Known limits (accepted)

- An exception raised by Android code (libc++_shared, bionic, the string shims) that has to
  unwind through a dylib frame still terminates (it uses libc++_shared's unwinder). Only
  out-of-memory / string-overflow paths do that.
- Apple and Android both agree on stream object layouts for what these dylibs import; not yet
  proven by a real save round-trip (item 7).

## Where the dylibs are

Analysis copies: `build/analysis_macos_dylibs/` (Lighting, PopMan, PathFind, Clipper, Bullet).
Game path on device: `<instance>/game/macos/lib*.dylib` (Codex's downloader + manifest).
