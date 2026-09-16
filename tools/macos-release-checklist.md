# macOS native libraries release checklist

Scope: Lighting, PopMan, PathFind. Bullet/Clipper stay Android ARM64; multiplayer libraries unchanged. No APK/commit/push without explicit approval.

## Passed on 2026-09-12
- [x] Native and Java compilation; EH runtime present in merged native packaging inputs.
- [x] Device harness: JNI/JavaVM wrapping, worker attach/detach, 11 EH tests, actual dylib loading.
- [x] B42.20.0: all three dylibs loaded; PathFind preflight enabled native code.
- [x] First gameplay save, restart, load existing world, play and save again without observed native crash. This does not verify every saved entity.
- [x] CPU features: the dylibs are ARMv8.0 except 4 `LDAPR` (ARMv8.3 RCPC) each in PopMan and PathFind. On CPUs without `HWCAP_LRCPC` (Cortex-A53/A57/A72/A73) the loader rewrites them to `LDAR` before `mprotect`, skipping `LC_DATA_IN_CODE`. Verified on the phone with `ZOMDROID_MACHO_FORCE_LDAR=1`: exactly the 8 offsets found by host disassembly rewritten, all integration tests pass. Still worth one run on a real A53/A73 device.

## Before release
- [ ] Longer play: cross chunks, interact with zombies, save repeatedly and reload; verify items and population visually.
- [ ] Pathfinding: item placement on tables, doors, obstacles and vehicle routes.
- [ ] Lighting: handheld lights, headlights, rooms and transitions.
- [x] Disabled macOS option preserves existing launch behavior. Verified 2026-09-12 23:36 on the S25 (switch off, env vars reduced to `ZOMDROID_NATIVE_FMOD=1`): zero `[macho]` lines, Lighting and PopMan through box64, `Pathfind.UseNativeCode=false`, Bullet/Clipper/RakNet/ZNet/jassimp from android/arm64-v8a, no crash, world loaded. Note: two earlier attempts were invalid because a leftover user env var `ZOMDROID_MACHO_LIBS=1` overrode the switch. Fixed the same day: `GameLauncher` now sets `ZOMDROID_MACHO_LIBS` and `ZOMDROID_NATIVE_FMOD` from the per-instance switches AFTER the user's env vars, so the switches are the only authority. Native FMOD got its own switch in the same settings card (`native_fmod`, default off). Debug builds set `ZOMDROID_JNI_STATS=1` on their own. `ZOMDROID_MACHO_FORCE_LDAR=1` stays an env-only test knob.
- [ ] Missing/rejected dylib: PopMan/PathFind fall back to box64, not incompatible Android implementations; rejected PathFind keeps Java pathfinding.
- [ ] Missing EH runtime fails safely; no broken JNI raw-pointer fallback.
- [ ] Test compatible newer B42 versions and keep B41 behavior unchanged.
- [ ] Multiplayer client and phone-hosted server tested separately, including save/restart.
- [x] Remove excessive diagnostic logging; keep actual library selection and rejection reasons. Done 2026-09-16: per-symbol `[macho] bridge`, `[jni-bind] (macho)` and per-offset LDAPR lines only with ZOMDROID_NATIVE_VERBOSE=1 (set by the launcher for debug builds and instances with Debug on); the debug-only Bullet diagnostic was removed.
- [ ] Verify downloaded-library version/manifest handling and update behavior. Changed 2026-09-13: only the 3 used dylibs are downloaded or required (`MacosLibraries.NAMES`), the build-number status lines are gone, and a "Load from file" ZIP import writes the same manifest. To test: download on a fresh instance, import a ZIP (with extra files and nested folders) into a second one, a ZIP missing one dylib must fail without touching the existing set.
- [ ] Per-library switches ("Libraries in use", folded, all on by default): turning one off must send only that library to its regular path; PathFind off must keep `Pathfind.UseNativeCode=false`.
- [ ] Inspect packaged release native libraries when an APK build is authorized.

Known separate observations: SQLite libm.so.6 load failure, existing map_meta room IDs and missing vegetation tiles. Do not count these as proven Mach-O regressions.
Known limitation: exceptions originating inside Android C++ shims use a separate runtime. JNI exception boundary remains deferred pending evidence.
