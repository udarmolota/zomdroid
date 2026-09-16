# Brief: why the other macOS dylibs don't load yet, and what each one would take

Status 2026-09-11. Companion to `tools/macos-native-libs-brief.md` (the loader plan). The loader
(`app/src/main/cpp/macho_loader.c`, `macho_bridge.c`) loads `libLighting.dylib` in the game. The
other four are refused on purpose, before any of their code runs. This document records why, what
fixing each one would cost, and whether it's worth it.

## 1. Bottom line first

| library | what runs today on 42.20 | would the macOS dylib help FPS? | blocked by |
|---|---|---|---|
| Lighting | **macOS dylib, native** (new) | yes, this was the hot one | nothing |
| PZBullet | TIS Android arm64 `.so`, native | no, already native | string layout, variadic `printf`/`vsnprintf` |
| PZPathFind | TIS Android arm64 `.so`, native | no, already native | string layout, exceptions |
| PZClipper | TIS Android arm64 `.so`, native | no, already native | TLV, string layout, allocator detail |
| PZPopMan | Linux x86_64 `.so` via box64 (TIS arm64 build lacks `n_saveCell`) | negligible (see below) | string layout, exceptions |
| RakNet | unchanged | out of scope (Inna: multiplayer libs stay as they are) | - |

TIS's Android arm64 PathFind and Clipper are complete: their JNI export lists match Linux and
macOS exactly (13/13 and 19/19, checked 2026-09-11 on the files from the phone). Bullet was
already verified (97/97). So for these three the dylib would only swap one native build for
another.

**PopMan is the only one with something to gain**, and it isn't FPS. In the `ZOMDROID_JNI_STATS`
run PopMan was negligible in-game. What a native PopMan would give is one less library in box64
and freedom from the stale TIS arm64 build. The x86_64 build we run today *has* `n_saveCell`, so
saving already works.

**With FPS as the priority, none of the four remaining dylibs is worth doing now.** The FPS work
is Lighting (done, being measured) plus whatever the Lighting measurement points to next.

## 2. The blockers, one by one

### 2.1 Apple's `std::string` layout (blocks PopMan, PathFind, Clipper, Bullet)

Apple builds libc++ on arm64 with the *alternate* string layout
(`_LIBCPP_ABI_ALTERNATE_STRING_LAYOUT`): data pointer first, size second, and the short/long flag
in the last byte. Android's libc++ (`std::__ndk1`) uses the standard layout. The mangled names
differ only by namespace, which the loader already rewrites (`St3__1` to `St6__ndk1`), so the
symbols resolve fine. But the objects don't match. Code inlined into the dylib builds a string in
Apple layout and passes it to an out-of-line function in `libc++_shared.so`, which reads it in
Android layout and gets garbage.

What we saw on the phone (harness, no app): PopMan and PathFind construct
`std::random_device(const std::string&)` in a static initializer. The call goes into
`operator+(const char*, const string&)` in `libc++_shared`, which crashes in `memmove` with a
nonsense length while the loader is still inside `dlopen`. The loader now refuses these libraries
before any constructor runs (`[macho] ... rejected: uses std::string/iostream ...`).

The imports involved are a short, closed list. For PopMan:

- `basic_string`: `append(const char*)`, `append(const char*, size_t)`, `assign(const char*)`,
  `insert(size_t, const char*)`, `resize(size_t, char)`, `push_back(char)`, `~basic_string()`,
  `operator=(const basic_string&)`
- `operator+(const char*, const string&)`, `to_string(int)`
- `runtime_error(const string&)`
- `random_device(const string&)`, `~random_device()`, `random_device::operator()()`

PathFind, Clipper and Bullet use subsets of the same list.

**Fix:** implement those ~15 functions ourselves against Apple's string layout, and bind them in
place of the `libc++_shared` versions. Each one is small: read/write the three words, grow with
`malloc`/`realloc` via `operator new`/`delete`. `random_device` can be backed by `getrandom()`;
Apple's layout for it has no file descriptor. About 300 lines of C, plus a harness test per
function against known Apple-layout byte images. The enthusiast's closed loader visibly does
exactly this: its binary carries these same 15 Apple symbol names and a `PZMAC_DARWIN_STRING` log
tag.

**Cost:** 1-2 days including tests. **Risk:** low. The list is closed and every function is
simple.

### 2.2 Our guard is currently broader than the problem

The loader refuses any dylib that imports iostream classes (`basic_ostream`, `basic_istream`,
`basic_streambuf`, `cout`, `cerr`) in addition to strings. That was a conservative first cut.
Re-reading the libc++ sources, the stream *objects* these dylibs use are mostly their own template
instances (e.g. `basic_filebuf<char>` is compiled into PopMan, all Apple layout, self-consistent).
What they share with `libc++_shared` is the `basic_streambuf` / `ios_base` / `ostream` base
machinery, whose layout matches on both sides. The enthusiast's binary shims no stream symbols,
which supports this.

**Fix:** narrow the guard to the string/`random_device`/`runtime_error(string)` imports once 2.1
exists, and prove streams on the phone: the harness should call PopMan's save path into a temp
file and compare the bytes with the box64 run. **Cost:** half a day. **Risk:** medium until the
byte comparison passes.

### 2.3 C++ exceptions can't unwind through dylib code (PopMan, PathFind, Clipper, Bullet)

The dylibs carry only Apple's compact unwind table (`__unwind_info`), no `__eh_frame`. Android's
unwinder only reads `__eh_frame`. So any `throw` inside a dylib aborts the process instead of
reaching its `catch`. Lighting never throws in practice. PopMan does: the enthusiast's binary has
a PopMan-specific fix named `cpp_exception_jni_boundary`, and its compact-unwind support reports
`runtime=llvm18-r27d`. That suggests he built LLVM's libunwind with compact-unwind support into
his loader.

**Fix, two options:**

1. Translate compact unwind into synthetic DWARF CFI at load time and register it with
   `__register_frame`. The arm64 encodings are few: frame-based with saved register pairs,
   frameless with a stack size, or "use DWARF" (which these files don't use). About 400 lines.
   No new runtime.
2. Build LLVM libunwind with `_LIBUNWIND_SUPPORT_COMPACT_UNWIND` for Android and use it for dylib
   frames. More build plumbing, and a second unwinder in the process.

Option 1 is preferred. **Cost:** 2-3 days. **Risk:** medium. A wrong CFI row gives wrong unwinds
only on error paths, which are hard to trigger in tests. The harness needs a forced-throw path.

### 2.4 Thread-local variables (Clipper)

Clipper declares thread-local storage (`__thread_vars`, `__thread_bss`) and imports
`_tlv_bootstrap` and `_tlv_atexit`. On macOS each TLV descriptor holds a thunk pointer that dyld
patches to a function returning the per-thread address. We'd have to do the same: allocate a
per-thread block via `pthread_key`, write an assembly thunk that returns
`block + descriptor offset` without disturbing registers other than x0 (the Apple TLV calling
convention preserves everything else), and run `_tlv_atexit` destructors at thread exit.

**Cost:** 1-2 days. **Risk:** medium. The register-preservation rule of the thunk is strict. The
enthusiast's loader implements this: its strings include TLV descriptor validation and
`tlv_descriptors`/`tlv_bytes` counters, plus a suppressed destructor on JVM thread detach.

### 2.5 Clipper's `nothrow` allocation / `stable_sort` scratch buffer

Clipper imports `operator new(size_t, const std::nothrow_t&)`, which `std::stable_sort` uses
through `get_temporary_buffer`. The enthusiast's binary has a dedicated "Clipper allocator split"
that serves that scratch buffer from an anonymous `mmap`. The reason isn't known to us yet.
Candidates: a mismatch between Apple's inlined `__libcpp_deallocate` and bionic's `operator
delete`, or a size/alignment variant that's missing. **Cost:** unknown until reproduced. **Risk:**
unknown. Only relevant if Clipper is ever attempted, and Clipper already runs natively from TIS.

### 2.6 Variadic C functions (Bullet)

Bullet's dylib imports `printf` and `vsnprintf`. Apple passes all variadic arguments on the stack,
and its `va_list` is a plain pointer. AAPCS64 (bionic) passes them in registers and uses a
5-field `va_list` struct. Calling bionic's `printf` from Apple code prints garbage or crashes, and
`vsnprintf` with an Apple `va_list` crashes. **Fix:** two small shims that rebuild an AAPCS64
`va_list` from the Apple stack area. The enthusiast's binary has exactly these fixes
(`darwin_va_list_to_aapcs64`, `float_stack_8_to_4` for Bullet). **Cost:** 1 day. **Risk:** low.
Only needed if Bullet's dylib is ever used. It isn't worth it: TIS's arm64 Bullet is current and
native.

Checked: Lighting, PopMan, PathFind and Clipper import no variadic C functions.

## 3. If we do go further: the order

Only PopMan has a reason to exist, and it's correctness and hygiene, not FPS:

1. 2.1 Apple-layout string functions (unblocks the static initializers)
2. 2.2 narrow the guard, prove the save path byte-for-byte against box64
3. 2.3 compact unwind to CFI (PopMan does throw)
4. then PopMan native, with the harness first and an in-game save test second

That's roughly a week. PathFind, Clipper and Bullet stay on TIS's arm64 builds with no plan to
change them. RakNet stays untouched.

## 4. What the enthusiast's binary tells us (strings only, no sources read)

His `libzomdroidlinker.so` (302 KB, statically carries its C++ runtime, no `libc++_shared`
dependency) logs under `PZMAC_*` tags. From the strings alone, he solved every blocker above:
Apple string functions (15), TLV descriptors, compact unwind via an LLVM 18 unwinder, the Clipper
allocator split, Darwin `va_list` bridging for Bullet and FMOD, and a PopMan exception boundary.
He also loads FMOD's dylibs, with several FMOD-specific workarounds (callbacks disabled, mutex
patching, capture disabled). We don't need that: our FMOD is native already.

He also has hand-named ABI fixes for individual Lighting functions (`isOpenAir_stack_16_to_12`,
`height_active_stack_repack`, `g_b_dot_float_stack_repack`, `12_float_stack_slots_8_to_4`). Our
bridge generator derives the same repacking from the Java signature for every function, so it
should already cover these. If Lighting ever shows wrong values in a specific place, those names
are the first thing to compare against our `[macho] bridge` log lines.
