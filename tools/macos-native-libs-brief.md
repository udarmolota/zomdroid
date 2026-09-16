# Brief: running Project Zomboid's macOS ARM64 libraries natively (two-track plan)

Status: 2026-09-11, track B implemented and passed milestone B0 on the phone (loader harness,
no app): **Lighting loads and resolves; PopMan, PathFind, Bullet are refused by design (libc++
layout, see "Track B status"); Clipper refused (TLV); RakNet stays on its current path by
Inna's decision.** In practice v1 = Lighting, which is where the measured cost was. Uncommitted:
`macho_loader.c/.h`, `macho_bridge.c`, `linker.c`, `emulation.c/.h`, `CMakeLists.txt`,
`app/build.gradle.kts`. Track A in progress. Approval gates apply as usual: no source edit,
commit, push or APK build without Inna's explicit OK, one approval per action.

## 1. Why

Every JNI library the game ships for Linux (`libLighting64.so`, `libPZPopMan64.so`, ...) is x86_64
and runs through box64. Measured on 2026-09-10 with `ZOMDROID_JNI_STATS=1` (42.20.x, Snapdragon):

| what | measured |
|---|---|
| `Lighting64` total per 30 s | ~23 s of emulated time, i.e. ~80 % of one core |
| `LightingJNI.stateEndFrame` -> `DoLightingUpdateNew` | 47-58 ms per call, ~419 calls / 30 s |
| all other Lighting entry points (per-square getters/setters) | microseconds each, irrelevant |
| `PZPopMan64`, `fmod` | negligible in-game |

The per-square Java memo-cache idea is dead (the getters are cheap). The cost is one C++ routine
that is simply expensive and 3-4x slower under box64 than it would be natively.

TIS ships ARM64 Android builds in `android/arm64-v8a/`, but they are stale: Lighting there is an
older engine missing `squareSetLightTransmission` (49/50 exports) and PopMan is missing
`n_saveCell` (58/59) + libc++ file-stream typeinfo, which breaks world saving. The only current
ARM64 builds of these libraries are the macOS dylibs in the Steam macOS depot. An enthusiast's
closed-source Zomdroid fork already loads those dylibs through a custom in-process Mach-O loader and
runs visibly faster. We build our own loader: his blob has no source and no licence, and the first
format change from TIS would leave us unable to fix it.

## 2. What the dylibs are (inspected, arm64 slice)

Facts from `llvm-objdump` / `llvm-nm` on `libLighting.dylib` and `libPZPopMan.dylib` (42.20.x):

- Fat binaries: x86_64 + **arm64** (`CPU_SUBTYPE_ARM64_ALL`, not arm64e, no pointer auth).
- Platform macOS, minos 11.0, sdk 26.0. `LC_DYLD_INFO_ONLY` = classic rebase/bind/lazy-bind/export
  opcodes, **not** chained fixups. `LC_FUNCTION_STARTS`, `LC_CODE_SIGNATURE` (irrelevant when we
  map the file ourselves).
- Dependencies: only `/usr/lib/libc++.1.dylib` and `/usr/lib/libSystem.B.dylib`.
- Imports: Lighting 27, PopMan 115. All libc / libc++ / unwind:
  - libc: `memcpy memmove memset bzero memcmp strlen malloc free aligned_alloc malloc_size
    fopen fclose fread fwrite fflush fseeko ftello setbuf __stack_chk_fail __stack_chk_guard`
  - libc++: `std::__1::string`, `locale`, `ios_base`, `basic_filebuf/ifstream` typeinfo, exception
    classes, `std::__1::__next_prime`, `operator new/delete` variants. PopMan has 44 symbols in the
    `std::__1` namespace; Android's libc++ uses `std::__ndk1`, so those need a name remap. Both are
    libc++ ABI v1, same layouts.
  - unwind / C++ EH: `__cxa_*`, `__gxx_personality_v0`, `_Unwind_Resume`, `dyld_stub_binder`.
- Sections: `__text __stubs __stub_helper __gcc_except_tab __const __cstring __unwind_info __got
  __mod_init_func __la_symbol_ptr __data __common __bss`. **No `__eh_frame`** (compact unwind
  only), **no ObjC, no TLV**, static constructors present (`__mod_init_func`).
- JNIEnv usage: neither library calls a variadic JNIEnv function (`NewObject`, `Call*Method`
  without the `A`/`V` suffix), checked by scanning `ldr` offsets into the function table. This
  removes the nastiest ABI problem (Apple passes variadic args on the stack).
- ABI hazards that remain (Apple arm64 vs Linux AAPCS64):
  1. Arguments beyond the 8 GPR / 8 FPR are packed by natural size on the Apple stack, 8-byte
     slots on Linux. `LightingJNI` has 8 natives with 9-20 Java args (`stateEndFrame` 10,
     `playerSet` 12, `squareSet` 9, `squareSetLightTransmission` 20, `addLight` 10,
     `addTempLight` 9, `addRoomLight` 9, `updateTorch` 14). Each needs a generated shim.
  2. An Apple callee assumes `char/short/bool` args were sign/zero-extended to 32 bits by the
     caller; HotSpot on Linux does not guarantee it. The shim extends `jboolean/jbyte/jchar/jshort`.
  3. C++ exceptions cannot unwind through dylib frames (no `__eh_frame`). Acceptable for v1: they
     do not fire in normal operation; on an error path the process aborts instead of throwing.

Bullet is a special case: the Android ARM64 `libPZBullet64.so` from TIS is current (97 identical
JNI exports vs macOS/Linux) and already loads natively since the dlopen refcount fix in `linker.c`.
Its dylib is a fallback only.

## 3. Contract between the two tracks

Everything below is fixed so the tracks can proceed independently. Change it only by agreement.

**Storage.** Downloaded dylibs live in the game directory next to TIS's own folder:
`<game dir>/macos/lib<Name>.dylib`, plus `<game dir>/macos/manifest.json` written by the
downloader:

```json
{"appBuildId": 0, "depotId": 0, "manifestGid": "...",
 "files": {"libLighting.dylib": {"size": 0, "sha256": "..."}}}
```

The native side never reads the manifest.

**Which files - revised 2026-09-11 after B0.** Only `libLighting.dylib` is usable today (see
"Track B status" for why the others are refused). The downloader may fetch just that one file;
the native side keeps the other four names so a future TIS build that passes the loader's guard
is picked up without an app change. `libRakNet.dylib` is out: the multiplayer libraries stay on
their current path (Inna, 2026-09-11).

Original list, confirmed by track A on 2026-09-11 from the user's authenticated
DepotDownloader manifest-only export, depot `108602`, manifest
`3684990203905087557` (2026-08-24 15:33:49), build directory `24909800`.
Source: `C:/Apps/DepotDownloader/depots/108602/24909800/manifest_108602_3684990203905087557.txt`.
All six are regular files under `Project Zomboid.app/Contents/Java/`:

| Basename | Exact size (bytes) |
|---|---:|
| `libLighting.dylib` | 393024 |
| `libPZPopMan.dylib` | 517232 |
| `libPZBullet.dylib` | 2095408 |
| `libPZClipper.dylib` | 493632 |
| `libPZPathFind.dylib` | 1002416 |
| `libRakNet.dylib` | 3399648 |

Total: 7901360 bytes. These sizes identify this manifest only; do not hard-code
them as expected sizes for future downloads. The export's File SHA is SHA-1,
not the SHA-256 required in our installed `manifest.json`.
Not wanted: fmod (we ship a native one), ZNet.

Branch note: `public` is now stable B42; the downloader's old assumption
`public = B41, unstable = B42` must not be reused for this feature. Resolve the
current branch manifest through Steam; never use the Linux manifest GID for macOS.

**Name mapping (native side).** JNI entry `<Name>64` -> `macos/lib<Name>.dylib`, i.e. `Lighting64`
-> `libLighting.dylib`, `PZPopMan64` -> `libPZPopMan.dylib`, `PZBullet64` -> `libPZBullet.dylib`,
`PZClipper64`, `PZPathFind64` likewise. `RakNet64` is not mapped.

**Switch.** Per-instance setting, exported to the game process as env `ZOMDROID_MACHO_LIBS=1`
(absent or `0` = off). The native side does nothing at all when it is not `1`.

**Resolution order in the `linker.c` `dlopen` hook for each `jni_libs[]` entry:**
1. `ZOMDROID_MACHO_LIBS=1` and `macos/lib<Name>.dylib` exists -> Mach-O loader. On any failure
   log and fall through.
2. `android/arm64-v8a/lib<Name>64.so` (existing path, bionic dlopen).
3. box64 emulation of the Linux `.so` (existing path).

**Log lines (native.log, grep-able by both tracks and in bug reports):**
- `[macho] <file> loaded natively: arm64 slice, <n> exports, <m> binds`
- `[macho] <file> rejected: <reason>` (then the fallback line the linker already prints)
- `[macho] bridge <JNI symbol> args=<types> stack-shim=<0|1>`
- `[jni-bind]` / `[linker]` lines stay as they are.

**Fallback behaviour.** A rejected dylib never breaks the launch: the entry silently takes the next
option. A dylib that loads but misbehaves is the player's switch to turn off.

## 4. Track A: Codex, app side (Java/Kotlin, resources)

Goal: the player flips one switch, the six files arrive from Steam by the player's own account,
always the current build, no archives from Telegram. Files:

- `app/src/main/java/com/zomdroid/steam/SteamGameDownloader.java`
  - `resolveLinuxDepot()` is hard-wired to `oslist` containing `linux`. Generalise to
    `resolveDepot(String os)`; `macos` for this feature. Keep the branch/buildid logging.
  - The file loop (`for (FileData f : files)` around line 403) already downloads per file from the
    manifest. Add an optional file filter (set of exact file names, matched against the sanitized
    relative path's basename) so only the wanted dylibs are fetched, into `<game dir>/macos/`.
    Keep the resume logic; the done-set must be keyed per depot so a Linux run and a macOS run do
    not confuse each other.
  - Write `manifest.json` as specified above after all files complete. Verify sizes; sha256 the
    files (0.4-2 MB each, cheap).
  - First task, before any code: list the macOS depot manifest for the branch and report the
    actual file names and sizes of the six candidates (they may live in a subfolder such as
    `Contents/...`). Update section 3 of this brief with the confirmed names.
- `app/src/main/java/com/zomdroid/game/InstanceSettings.java` + `SettingsFragment` /
  `fragment_settings.xml`: per-instance boolean "Native macOS libraries (experimental)" with a
  "Download / Update" action that runs the downloader flow above (reuse `SteamDownloadFragment`
  login + progress UI). Show the stored `appBuildId` and mark "update available" when it differs
  from the installed Linux build's id. Strings in all five locales (en, ru, zh-rCN, pt-rBR, in).
- `app/src/main/java/com/zomdroid/GameLauncher.java`: export `ZOMDROID_MACHO_LIBS=1` when the
  switch is on and the folder exists; user env vars still win (applied after, overwrite=true).
- `app/src/main/java/com/zomdroid/patch/NativeLibraryWorkarounds.java`: no change to the
  `.disabled` rename logic. Only make sure `prepareBulletDiagnostic` and the macOS switch do not
  fight: with the switch on, the dylib is tried first anyway.
- Bug report / log export: nothing new, `[macho]` lines land in native.log already.

Out of scope for A: anything under `app/src/main/cpp/`.

Artifact-free checks: `gradlew :app:compileDebugJavaWithJavac`, `gradlew :app:processDebugResources`.
No `assembleDebug`.

## 5. Track B: Claude, native loader (C, `app/src/main/cpp/`)

Goal: the `dlopen` / `dlsym` hooks in `linker.c` can hand the JVM a dylib's JNI functions.

- New `app/src/main/cpp/macho_loader.c/.h` (~1.5-2k lines), no dependency on box64:
  1. Fat header -> pick the `CPU_TYPE_ARM64` slice. Reject arm64e, reject a missing
     `LC_DYLD_INFO(_ONLY)`, reject any `LC_LOAD_DYLIB` outside libc++/libSystem, reject
     `__thread_vars` / ObjC sections. Every rejection is one `[macho] ... rejected:` line.
  2. Map segments with `mmap` (anonymous RW, copy, then `mprotect` per segment: `__TEXT` r-x,
     `__DATA_CONST` r after binding, `__DATA` rw). 16 KB Mach-O pages are fine on 4 KB bionic.
  3. Rebase opcodes (slide), bind + lazy-bind opcodes (bind everything eagerly, `__la_symbol_ptr`
     included; `dyld_stub_binder` bound to an abort stub that logs). Weak binds: treat as bind.
  4. Symbol resolution: strip the leading `_`; remap `_ZNSt3__1` -> `_ZNSt6__ndk1` (and the
     `_ZNKSt3__1`, `_ZTVNSt3__1`, `_ZTINSt3__1`, `_ZTSNSt3__1` forms); then `dlsym` from
     `libc++_shared.so`, `libc.so`, `libdl.so`. Explicit map for the few Darwin-only names:
     `malloc_size` -> `malloc_usable_size`, `__stack_chk_guard` -> bionic's. Any unresolved
     import = reject (print the full list, it is the debugging aid).
  5. Export trie parser -> `macho_dlsym(handle, name)`; the JNI hook asks for `Java_...` names.
  6. Run `__mod_init_func` entries (with `argc/argv/envp/apple/progname` = 0/NULL as dyld passes
     them; the libraries do not use them).
  7. Unwind: v1 registers nothing. Document that a C++ throw inside the dylib aborts. v2 option:
     translate compact unwind (`__unwind_info`; arm64 encodings are simple: frame-based with saved
     register pairs, or frameless) into a synthetic `.eh_frame` and `__register_frame` it.
- JNI bridges (in `linker.c`, next to the existing box64 bridge generation, which already knows
  `arg_types` / `ret_type` from `method_signature_to_types`):
  - at most 8 args in each register class and no sub-32-bit integer args: hand the JVM the dylib
    symbol directly.
  - otherwise assemble an AArch64 shim with the existing encoders (`base_movz/ldr/str/stp/ldp/
    add_imm/sub_imm/blr/ret`): re-pack stack args from 8-byte Linux slots into Apple natural-size
    packing, sign/zero-extend `jboolean/jbyte/jchar/jshort` register args, forward `env` / `cls`,
    preserve x18 as the existing trampoline does, return in place.
  - Log one `[macho] bridge ...` line per generated shim.
- `linker.c` `dlopen` hook: insert step 1 of the resolution order (section 3), mirroring the
  `jni_native_path` / cache handling (Mach-O handles are ours, not bionic's: no refcount issue,
  `dlclose` on them is a no-op).
- Syntax checks only: NDK `clang -fsyntax-only` with `-DANDROID`. No APK.

Out of scope for B: Java, resources, the Steam downloader.

### Track B status, 2026-09-11

Implemented as above, with these findings from checking every import of the six dylibs against
bionic (API 30) and NDK r27 `libc++_shared.so` before the first device run:

| dylib | imports | outcome |
|---|---:|---|
| `libLighting.dylib` | 25 | all resolve as-is |
| `libPZPopMan.dylib` | 113 | needs the `__mbstate_t` -> `mbstate_t` mangling rewrite (codecvt facet id); done |
| `libPZBullet.dylib` | 83 | 8 weak self-defined statics (`btTransform::getIdentity()` guards etc.) -> loader binds them to the image's own exports; `__chkstk_darwin`, `__sincos_stret`, `memset_pattern16` -> provided by the loader |
| `libPZPathFind.dylib` | 113 | same three classes of fix as Bullet/PopMan |
| `libPZClipper.dylib` | 109 | **uses thread-local variables** (`__thread_vars`, `_tlv_bootstrap`, `_tlv_atexit`): rejected by design in v1, stays on the current path. Supporting Apple TLV descriptors is a v2 item |
| `libRakNet.dylib` | - | dropped from the native map: multiplayer libraries stay as they are |

**B0 result (Samsung S25 Ultra, 2026-09-11, harness from `/data/local/tmp`):**

| dylib | result |
|---|---|
| `libLighting.dylib` | loaded: 177 exports, 32 binds, 1 static initializer, 192 KB; all probes resolve; 7 shims built, 2 direct |
| `libPZPopMan.dylib` | **crashed in a static initializer** on the first run (`std::random_device(const std::string&)` -> `operator+` -> memmove with garbage length); now refused before any of it runs |
| `libPZPathFind.dylib` | same crash, same cause; now refused |
| `libPZBullet.dylib` | loaded and resolved on the first run, but imports `std::string::assign` and `std::cout`: refused now, the TIS Android ARM64 build keeps serving it natively |
| `libPZClipper.dylib` | refused: thread-local storage |

**The libc++ finding.** Apple's libc++ on arm64 uses the *alternate* `std::string` layout (data
pointer first, long/short flag in the last byte) and a 128-byte `mbstate_t` inside every file
stream. The NDK's libc++ has neither. Inline code in a dylib builds such an object and hands it
to a `libc++_shared` function, which reads it with the other layout: garbage in, crash out. The
loader now scans the bind tables and refuses any dylib that imports `std::string`, iostream,
streambuf, filebuf, `random_device`, `cout/cerr/clog` from `std::__1`. Lighting imports none of
these (its only libc++ template import is `std::__next_prime`).

Getting PopMan/PathFind/Clipper (and Bullet's dylib) to load would need a libc++ built for
Android with Apple's ABI settings (`_LIBCPP_ABI_ALTERNATE_STRING_LAYOUT`, a Darwin-sized
`mbstate_t`, matching `random_device`/`recursive_mutex` layouts) - a separate project, a second
C++ runtime in the APK, and not worth it on today's numbers: those three libraries were
negligible in the JNI stats. Clipper would additionally need Apple TLV descriptors.

Other decisions taken:
- `app/build.gradle.kts` now passes `-DANDROID_STL=c++_shared` so `libc++_shared.so` is packaged;
  the loader `dlopen`s it at run time (no link-time dependency: if it is ever missing, the dylib
  is rejected and the launch goes on). Verify on the first build that `lib/arm64-v8a/` in the APK
  contains `libc++_shared.so`.
- `zomdroidlinker` links `libm` for the sincos shims.
- The bridge encodings (21 instruction forms) were verified against the NDK assembler.
- A standalone harness (`scratchpad/macho_test`, not in the repo) loads the dylibs and builds
  the eight long bridges on a device from `/data/local/tmp`, without the app. It is compiled and
  waiting for a connected phone: that run is milestone B0 and comes before any APK.
- Log line format as shipped: `[macho] <file> loaded natively: arm64 slice, N exports, M binds,
  K KB at <addr>`, `[macho] <file> rejected: <reason>`, `[macho] bridge <sym> args=<types>
  stack-shim=<0|1> extended=<n> apple-area=<bytes>`, `[jni-bind] ... (macho)`.

## 6. Milestones and acceptance

| # | milestone | acceptance (on Inna's phone, 42.20.x) |
|---|---|---|
| B0 | loader harness on the phone, no app | `macho_test` loads Lighting/PopMan/Bullet/PathFind from `/data/local/tmp`, resolves the probes, builds the eight bridges; Clipper is rejected with "thread-local storage" |
| B1 | Lighting loads via the loader, symbols resolve | `[macho] libLighting.dylib loaded natively`, `[jni-bind]` shows all 50 `LightingJNI` natives bound to it, game reaches IngameState |
| B2 | Lighting correct and fast | lighting visibly identical to the box64 run; `[JNISTAT]` no longer lists `Lighting64`; door/torch light delay gone; note FPS but do not claim it (ETC2 is the FPS lever) |
| A1 | depot listing | confirmed dylib names/sizes in section 3 |
| A2 | download + switch | the six files appear under `macos/`, `manifest.json` written, `ZOMDROID_MACHO_LIBS=1` in the process env |
| B3 | PopMan | world save works (`n_saveCell` path), no `[macho] rejected` |
| B4 | Clipper, PathFind, Bullet, RakNet | vehicles drive (Bullet), pathing/zombies normal, MP connects (RakNet) |
| both | release candidate | switch default OFF for the first release; the enthusiast's build is the reference for "works" |

Test protocol for each B milestone: same route, same instance, `ZOMDROID_JNI_STATS=1`, compare
`[JNISTAT]` interval totals before/after. FPS numbers are secondary and must be reported with the
ETC2 state.

## 7. Risks, in order of likelihood

1. A libc++ layout difference between Apple's libc++ and NDK r29's for something PopMan uses
   inline (streams, locale). Symptom: crash in a `std::__ndk1` function. Mitigation: it shows up
   immediately at B3, and the fallback keeps the player alive.
2. A JNI signature the shim generator mis-packs (float/int interleaving on the Apple stack).
   Symptom: wrong lighting values, not a crash. Mitigation: unit-test the packer against the eight
   long `LightingJNI` signatures on paper before the first run.
3. TIS changes the dylib format (chained fixups, arm64e) in a future build. Mitigation: explicit
   rejection + fallback; the log says what changed.
4. The Steam macOS depot layout differs from the Linux one (files under `Contents/...`). Track A's
   first task answers this before anything is written.

## 8. Ground rules (both tracks)

Track A implementation status: the Java download/settings/environment wiring is implemented.
The per-instance card is default-off and shown for B42.20+. Downloads select the current public
macOS depot, flatten only the six verified names, verify Steam SHA-1 and record SHA-256 in the
installed manifest. A staging directory is published only after verification, retaining the
previous complete directory for recovery. Existing/imported Linux installs without
`zomdroid-steam.json` display an unknown build instead of guessing compatibility. User environment
arguments still override the generated `ZOMDROID_MACHO_LIBS` value. Java/resources compilation
and four filesystem/filter unit tests pass; authenticated on-device download and native runtime
integration still require testing. No APK was built for this step.

- Code comments, log strings, this document: English. Chat with Inna: Russian.
- No edits without her OK for the specific change, no commits, no pushes, no APK builds.
  Artifact-free checks are fine (`compileDebugJavaWithJavac`, `processDebugResources`,
  `clang -fsyntax-only`).
- Do not touch each other's files. Interface changes go through this brief first.
- The dylibs are TIS property: never commit one, never ship one in the APK, never post a link.
